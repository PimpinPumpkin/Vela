package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume
import org.junit.Test
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * How often strict naming puts the WRONG street on a turn, and how often it leaves one bare, for a
 * sweep of its thresholds. On demand only:
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*NamingStudyTest' -DvelaStudy=12 --rerun-tasks
 *
 * (the number is routes per area). The open router's own routes are the ground truth: every turn
 * on them carries the name of the street entered, from the same OpenStreetMap data the tiles are
 * cut from. Each route's line is named from the tiles alone and compared turn by turn. Reads the
 * report from the test results XML (system-out).
 */
class NamingStudyTest {
    private data class Area(val name: String, val s: Double, val w: Double, val n: Double, val e: Double)

    private val areas = listOf(
        Area("Davis grid", 38.535, -121.765, 38.565, -121.720),
        Area("Portland short blocks", 45.512, -122.690, 45.532, -122.660),
        Area("Boston old streets", 42.350, -71.075, 42.368, -71.050),
        Area("Prague old town", 50.078, 14.410, 50.094, 14.435),
        Area("Los Angeles freeways", 34.020, -118.330, 34.090, -118.220),
        Area("Houston suburbs", 29.700, -95.560, 29.760, -95.470),
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun same(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x == y || x.contains(y) || y.contains(x)
    }

    @Test fun sweepStrictNamingThresholds() = runBlocking {
        val per = System.getProperty("velaStudy")?.toIntOrNull()
        Assume.assumeTrue("set -DvelaStudy=<routes per area>", per != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val ua = "VelaMaps-naming-study (github.com/PimpinPumpkin/Vela)"
        fun get(url: String): ByteArray? = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
        // The live tile path, the way the app finds it: style -> TileJSON -> tiles template.
        val style = String(get("https://tiles.openfreemap.org/styles/liberty")!!)
        val tileJson = Regex("\"openmaptiles\"\\s*:\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"").find(style)!!.groupValues[1]
        val template = Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(String(get(tileJson)!!))!!.groupValues[1]
        RoadNameTiles.fetch = { z, x, y -> get(template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")) }

        val runs = listOf(30.0, 45.0, 60.0, 90.0)
        val offs = listOf(15.0, 20.0, 30.0)
        // [config] -> counts: right, wrong, bare (truth named, ours unnamed), missed (no turn of ours near a named truth turn)
        val tally = HashMap<String, IntArray>()
        val wrongExamples = HashMap<String, MutableList<String>>()
        val byArea = HashMap<String, IntArray>() // at the shipped setting
        var routes = 0
        val rnd = Random(20261002)
        for (a in areas) {
            var got = 0; var tries = 0
            while (got < per!! && tries < per * 3) {
                tries++
                fun pt() = LatLng(a.s + rnd.nextDouble() * (a.n - a.s), a.w + rnd.nextDouble() * (a.e - a.w))
                val o = pt(); val d = pt()
                if (o.distanceTo(d) < 800.0) continue
                Thread.sleep(1100) // one request a second to the community router
                val r = RouteGeometry.route(http, o, d, TravelMode.DRIVE).firstOrNull() ?: continue
                val lines = RoadNameTiles.linesAlong(r.polyline) ?: continue
                got++; routes++
                val truth = r.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
                for (run in runs) for (off in offs) {
                    LineNamer.STRICT_RUN_M = run; LineNamer.STRICT_FAR_RUN_M = run * 100.0 / 60.0; LineNamer.STRICT_MAX_OFF_M = off
                    val key = "run ${run.toInt()} m, match ${off.toInt()} m"
                    val t = tally.getOrPut(key) { IntArray(4) }
                    val ours = LineNamer.name(r.copy(legs = emptyList()), lines, TravelMode.DRIVE, strict = true)
                        ?.maneuvers?.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE } ?: continue
                    val shipped = run == 60.0 && off == 30.0
                    val at = if (shipped) byArea.getOrPut(a.name) { IntArray(4) } else null
                    for (tm in truth) {
                        val name = tm.road?.takeIf { it.isNotBlank() } ?: continue
                        val near = ours.minByOrNull { it.location.distanceTo(tm.location) }?.takeIf { it.location.distanceTo(tm.location) <= 40.0 }
                        val slot = when {
                            near == null -> 3
                            near.road == null -> 2
                            same(near.road!!, name) -> 0
                            else -> 1
                        }
                        t[slot]++; at?.let { it[slot]++ }
                        if (slot == 1) wrongExamples.getOrPut(key) { ArrayList() }.let { if (it.size < 6) it += "${a.name}: said '${near!!.road}', truth '$name' (${tm.type})" }
                    }
                }
            }
            println("STUDY area ${a.name}: $got routes")
        }
        println("STUDY routes=$routes")
        println("STUDY config | right | WRONG | bare | missed | wrong% of named | named% of truth")
        for (run in runs) for (off in offs) {
            val key = "run ${run.toInt()} m, match ${off.toInt()} m"
            val t = tally[key] ?: continue
            val named = t[0] + t[1]; val all = t.sum()
            println("STUDY $key | ${t[0]} | ${t[1]} | ${t[2]} | ${t[3]} | ${"%.1f".format(if (named > 0) 100.0 * t[1] / named else 0.0)} | ${"%.0f".format(if (all > 0) 100.0 * t[0] / all else 0.0)}")
        }
        for ((k, v) in byArea) println("STUDY shipped (60/30) in $k: right ${v[0]} wrong ${v[1]} bare ${v[2]} missed ${v[3]}")
        for ((k, v) in wrongExamples) v.forEach { println("STUDY wrong @ $k: $it") }
        LineNamer.STRICT_RUN_M = 60.0; LineNamer.STRICT_FAR_RUN_M = 100.0; LineNamer.STRICT_MAX_OFF_M = 30.0
    }

    /** The same comparison for the MAP MATCH: each open-router line is matched by
     *  ValhallaRouter.match and its steps compared with the router's own names. */
    @Test fun mapMatchAgainstTheRoutersNames() = runBlocking {
        val per = System.getProperty("velaStudy")?.toIntOrNull()
        Assume.assumeTrue("set -DvelaStudy=<routes per area>", per != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val t = IntArray(4); var routes = 0; var refused = 0
        val wrong = ArrayList<String>()
        val rnd = Random(20261002)
        for (a in areas) {
            var got = 0; var tries = 0
            while (got < per!! && tries < per * 3) {
                tries++
                fun pt() = LatLng(a.s + rnd.nextDouble() * (a.n - a.s), a.w + rnd.nextDouble() * (a.e - a.w))
                val o = pt(); val d = pt()
                if (o.distanceTo(d) < 800.0) continue
                Thread.sleep(1100)
                val r = RouteGeometry.route(http, o, d, TravelMode.DRIVE).firstOrNull() ?: continue
                Thread.sleep(600)
                val m = app.vela.core.data.ValhallaRouter.match(http, r.polyline, timeoutMs = 20_000)
                got++; routes++
                if (m == null) { refused++; continue }
                val ours = m.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
                for (tm in r.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }) {
                    val name = tm.road?.takeIf { it.isNotBlank() } ?: continue
                    val near = ours.minByOrNull { it.location.distanceTo(tm.location) }?.takeIf { it.location.distanceTo(tm.location) <= 40.0 }
                    // The two routers cut a junction into steps differently (one says "left onto
                    // B" where the other says "left onto A" for 30 m, then "right onto B"): the
                    // name counts as given when any matcher step within 60 m carries it.
                    val around = ours.filter { it.location.distanceTo(tm.location) <= 60.0 }
                    val slot = when {
                        near == null -> 3
                        around.any { it.road != null && same(it.road!!, name) } -> 0
                        near.road == null -> 2
                        same(near.road!!, name) || (near.ref != null && same(near.ref!!, name)) -> 0
                        // The same road under its number: the router calls it "Hollywood Freeway"
                        // (ref US 101), the matcher "US 101 South". Not a wrong name.
                        tm.ref != null && (same(near.road!!, tm.ref!!) || (near.ref != null && same(near.ref!!, tm.ref!!))) -> 0
                        else -> 1
                    }
                    t[slot]++
                    if (slot == 1 && wrong.size < 25) wrong += "${a.name}: said '${near!!.road}' (ref ${near.ref}), truth '$name' (${tm.type})"
                    if (slot == 1 && wrong.size <= 25) {
                        fun ctx(ms: List<app.vela.core.model.Maneuver>) = ms.filter { it.location.distanceTo(tm.location) <= 250.0 }
                            .joinToString(" | ") { "${it.type} '${it.road}' ref=${it.ref} ${it.location.distanceTo(tm.location).toInt()}m len=${it.distanceMeters.toInt()}" }
                        wrong += "   at ${"%.5f,%.5f".format(tm.location.lat, tm.location.lng)} trip ${"%.5f,%.5f".format(o.lat, o.lng)} -> ${"%.5f,%.5f".format(d.lat, d.lng)}\n   router:  ${ctx(r.maneuvers)}\n   matcher: ${ctx(m.maneuvers)}"
                    }
                }
            }
            println("MATCH area ${a.name}: $got routes")
        }
        val named = t[0] + t[1]
        println("MATCH routes=$routes refused=$refused | right ${t[0]} | WRONG ${t[1]} | bare ${t[2]} | missed ${t[3]} | wrong% of named ${"%.1f".format(if (named > 0) 100.0 * t[1] / named else 0.0)}")
        wrong.forEach { println("MATCH wrong: $it") }
    }

    /** What the edge check does to the open router's OWN turn names: how many it keeps, renames
     *  or strips, with every rename and strip printed for reading by hand. */
    @Test fun checkTheOpenRoutersNames() = runBlocking {
        val per = System.getProperty("velaStudy")?.toIntOrNull()
        Assume.assumeTrue("set -DvelaStudy=<routes per area>", per != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val t = IntArray(4); var routes = 0; var noEdges = 0
        val changed = ArrayList<String>()
        val rnd = Random(20261002)
        for (a in areas) {
            var got = 0; var tries = 0
            while (got < per!! && tries < per * 3) {
                tries++
                fun pt() = LatLng(a.s + rnd.nextDouble() * (a.n - a.s), a.w + rnd.nextDouble() * (a.e - a.w))
                val o = pt(); val d = pt()
                if (o.distanceTo(d) < 800.0) continue
                Thread.sleep(1100)
                val r = RouteGeometry.route(http, o, d, TravelMode.DRIVE).firstOrNull() ?: continue
                Thread.sleep(600)
                got++; routes++
                val edges = app.vela.core.data.ValhallaRouter.edges(http, r.polyline, timeoutMs = 20_000)
                if (edges == null) { noEdges++; continue }
                val after = app.vela.core.data.ValhallaRouter.recheck(r.maneuvers, edges, keepUnplaced = true, tally = t)
                r.maneuvers.zip(after).forEach { (x, y) ->
                    if (x.road != y.road && changed.size < 60) {
                        val i = edges.indices.minByOrNull { edges[it].begin.distanceTo(x.location) }!!
                        var acc = 0.0
                        val path = edges.drop(i).takeWhile { e -> (acc < 160.0).also { acc += e.lengthM } }
                            .joinToString(" > ") { "${it.names.joinToString("/").ifEmpty { "-" }}${if (it.soft) "*" else ""} ${it.lengthM.toInt()}" }
                        changed += "${a.name}: ${x.type} '${x.road}' (step ${x.distanceMeters.toInt()} m) -> '${y.road}' | path: $path"
                    }
                }
            }
        }
        println("OPENCHECK routes=$routes noEdges=$noEdges | kept ${t[0]} | renamed ${t[1]} | bare ${t[2]} | unplaced ${t[3]}")
        changed.forEach { println("OPENCHECK $it") }
    }

    /**
     * THE WHOLE HYBRID, END TO END. A stand-in for Google's line: the open router's route through
     * a third random point, so it leaves the direct route and comes back like a route around a
     * closure does. Its own steps are the truth. The hybrid is built as the app builds it
     * (stretches, map match with the name check, tiles strict, bare; the open route's names
     * checked; no lane detail) and then (1) every truth turn is looked up in it, (2) every turn it
     * says is looked up in the truth, (3) StepAudit checks its steps against the line.
     */
    @Test fun theHybridEndToEnd() = runBlocking {
        val per = System.getProperty("velaStudy")?.toIntOrNull()
        Assume.assumeTrue("set -DvelaStudy=<routes per area>", per != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val t = IntArray(4); var routes = 0; var same = 0; var notPlaced = 0; var extra = 0; var said = 0
        val src = IntArray(3)
        var auditTurns = 0; var auditAgree = 0; var otherWay = 0; var noBend = 0; var unsaid = 0
        val notes = ArrayList<String>()
        val rnd = Random(20261003)
        // -DvelaOne="lat,lng;lat,lng;lat,lng" (start; via; end) runs that one trip and prints every piece.
        val one = System.getProperty("velaOne")?.split(";")?.map { it.split(",").let { c -> LatLng(c[0].trim().toDouble(), c[1].trim().toDouble()) } }
        fun dump(tag: String, ms: List<app.vela.core.model.Maneuver>) { var at = 0.0; ms.forEach { println("ONE $tag at=${at.toInt()} ${it.type} '${it.road}' len=${it.distanceMeters.toInt()} @${"%.5f,%.5f".format(it.location.lat, it.location.lng)} | ${it.instruction}"); at += it.distanceMeters } }
        val turnTypes = setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT)
        val hardTurns = setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT)
        for (a in areas) {
            var got = 0; var tries = 0
            while (got < per!! && tries < per * 4) {
                tries++
                fun pt() = LatLng(a.s + rnd.nextDouble() * (a.n - a.s), a.w + rnd.nextDouble() * (a.e - a.w))
                val o = one?.get(0) ?: pt(); val d = one?.get(2) ?: pt(); val v = one?.get(1) ?: pt()
                if (one != null && (a != areas.first() || got > 0)) break
                if (o.distanceTo(d) < 800.0) continue
                Thread.sleep(1100)
                val open = RouteGeometry.route(http, o, d, TravelMode.DRIVE).firstOrNull() ?: continue
                Thread.sleep(1100)
                val google = RouteGeometry.routeVia(http, listOf(o, v, d), TravelMode.DRIVE).firstOrNull() ?: continue
                if (one != null) { dump("truth", google.maneuvers); dump("open", open.maneuvers) }
                // A via that makes the route double back on itself is not a route anyone is given.
                if (google.maneuvers.any { it.type == ManeuverType.UTURN } || google.distanceMeters > open.distanceMeters * 2.5) continue
                // ...nor one that runs back along a road it has just driven (out to the via and back).
                run {
                    val gc = app.vela.core.nav.RouteProjection.cumulative(google.polyline)
                    val pts = (0..(gc.last() / 25).toInt()).map { app.vela.core.nav.RouteProjection.pointAt(google.polyline, gc, it * 25.0) }
                    pts.indices.any { i -> (i + 4 until pts.size).any { j -> pts[i].distanceTo(pts[j]) < 9.0 } }
                }.let { if (it) continue }
                val st = HybridRoute.stretchesFor(google.polyline, open)
                got++; routes++
                if (st.isEmpty()) { same++; continue }
                Thread.sleep(600)
                val openChecked = app.vela.core.data.ValhallaRouter.edges(http, open.polyline, timeoutMs = 20_000)
                    ?.let { app.vela.core.data.ValhallaRouter.recheck(open, it, keepUnplaced = true) } ?: open
                val named = st.map { s ->
                    Thread.sleep(600)
                    val piece = HybridRoute.slice(google.polyline, s.fromM, s.toM)
                    val m = app.vela.core.data.ValhallaRouter.matchWithEdges(
                        http, piece, timeoutMs = 20_000,
                        startSlackM = if (s.fromM <= 0.0) 150.0 else 0.0,
                        endSlackM = if (s.toM >= google.distanceMeters - 1.0) 150.0 else 0.0,
                    )
                    if (one != null) { println("ONE stretch ${s.fromM.toInt()}..${s.toM.toInt()} matched=${m != null}"); m?.let { dump("match", it.route.maneuvers) } }
                    if (m != null) { src[0]++; return@map s to m.route.maneuvers }
                    val sub = google.copy(polyline = piece, distanceMeters = s.toM - s.fromM, legs = emptyList(), trafficSpans = emptyList())
                    val lines = RoadNameTiles.linesAlong(piece).orEmpty()
                    src[if (lines.isEmpty()) 2 else 1]++
                    s to LineNamer.name(sub, lines, TravelMode.DRIVE, strict = true)?.maneuvers
                }
                val hybrid = if (named.any { it.second == null }) null else
                    HybridRoute.stitch(google.copy(legs = emptyList()), openChecked, named.map { it.first to it.second!! })
                if (hybrid == null) { notPlaced++; continue }
                if (one != null) dump("hybrid", hybrid.maneuvers)
                val ours = hybrid.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
                val truth = google.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
                fun ctx(ms: List<app.vela.core.model.Maneuver>, at: LatLng) = ms.filter { it.location.distanceTo(at) <= 200.0 }
                    .joinToString(" | ") { "${it.type} '${it.road}' ${it.location.distanceTo(at).toInt()}m len=${it.distanceMeters.toInt()}" }
                for (tm in truth.filter { it.type in turnTypes }) {
                    val name = tm.road?.takeIf { it.isNotBlank() } ?: continue
                    val near = ours.minByOrNull { it.location.distanceTo(tm.location) }?.takeIf { it.location.distanceTo(tm.location) <= 40.0 }
                    val around = ours.filter { it.location.distanceTo(tm.location) <= 60.0 }
                    val slot = when {
                        around.any { it.road != null && same(it.road!!, name) } -> 0
                        near == null -> 3
                        near.road == null -> 2
                        else -> 1
                    }
                    t[slot]++
                    if ((slot == 1 || slot == 3) && notes.size < 60) notes += "${a.name}: ${if (slot == 1) "NAME" else "MISSED"} truth ${tm.type} '$name' at ${"%.5f,%.5f".format(tm.location.lat, tm.location.lng)} trip ${"%.5f,%.5f".format(o.lat, o.lng)} via ${"%.5f,%.5f".format(v.lat, v.lng)} to ${"%.5f,%.5f".format(d.lat, d.lng)}\n     truth:  ${ctx(google.maneuvers, tm.location)}\n     hybrid: ${ctx(hybrid.maneuvers, tm.location)}"
                }
                for (hm in ours.filter { it.type in hardTurns }) {
                    said++
                    if (google.maneuvers.none { it.location.distanceTo(hm.location) <= 60.0 }) {
                        extra++
                        if (notes.size < 60) notes += "${a.name}: EXTRA hybrid ${hm.type} '${hm.road}' at ${"%.5f,%.5f".format(hm.location.lat, hm.location.lng)} trip ${"%.5f,%.5f".format(o.lat, o.lng)} via ${"%.5f,%.5f".format(v.lat, v.lng)} to ${"%.5f,%.5f".format(d.lat, d.lng)}\n     truth:  ${ctx(google.maneuvers, hm.location)}\n     hybrid: ${ctx(hybrid.maneuvers, hm.location)}"
                    }
                }
                val audit = app.vela.core.nav.StepAudit.check(hybrid)
                auditTurns += audit.turns; auditAgree += audit.agree; otherWay += audit.otherWay; noBend += audit.noBend; unsaid += audit.unsaid
                audit.findings.forEach { f ->
                    if (notes.size < 60) {
                        val cum = app.vela.core.nav.RouteProjection.cumulative(hybrid.polyline)
                        val at = app.vela.core.nav.RouteProjection.pointAt(hybrid.polyline, cum, f.atM)
                        notes += "${a.name}: AUDIT ${f.what} at ${"%.5f,%.5f".format(at.lat, at.lng)} trip ${"%.5f,%.5f".format(o.lat, o.lng)} via ${"%.5f,%.5f".format(v.lat, v.lng)} to ${"%.5f,%.5f".format(d.lat, d.lng)}\n     truth:  ${ctx(google.maneuvers, at)}\n     hybrid: ${ctx(hybrid.maneuvers, at)}"
                    }
                }
            }
            println("E2E area ${a.name}: $got routes")
        }
        println("E2E routes=$routes sameWay=$same notPlaced=$notPlaced | stretches matched ${src[0]} tiles ${src[1]} bare ${src[2]}")
        println("E2E truth turns: named right ${t[0]} | WRONG NAME ${t[1]} | bare ${t[2]} | MISSED ${t[3]}")
        println("E2E hybrid hard turns said $said, with no truth step within 60 m: $extra")
        println("E2E audit: $auditTurns turns, $auditAgree agree, $otherWay other way, $noBend no bend, $unsaid bends unsaid")
        notes.forEach { println("E2E $it") }
    }

    /**
     * REAL GOOGLE LINES, replayed. Google's directions cannot be fetched from a desk, so lines are
     * captured on a phone (adb shell setprop log.tag.VelaCapture DEBUG, plan a trip, read the
     * VelaCapture lines) and kept in src/test/resources/google_lines/<name>.txt as an encoded
     * polyline. Each is built into the hybrid the way the app does (the open router's route between
     * the line's ends, stretches, match with its checks, tiles, bare) and checked three ways:
     * StepAudit (steps against the line), the name check against the road pieces under the WHOLE
     * line, and NavReplay over a simulated drive (every turn announced, in time, with a turn-now
     * cue). -DvelaLines=<dir> reads another folder; -DvelaStudy=1 turns it on.
     */
    @Test fun replayCapturedLines() = runBlocking {
        Assume.assumeTrue("set -DvelaStudy=1", System.getProperty("velaStudy") != null)
        val dir = System.getProperty("velaLines")?.let { java.io.File(it) }
            ?: java.io.File(javaClass.getResource("/google_lines")!!.toURI())
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        var trips = 0; var notPlaced = 0; var noStretch = 0; var baseline = 0
        val changed = java.util.Collections.synchronizedList(ArrayList<String>())
        app.vela.core.data.ValhallaRouter.onChanged = { stated, len, runs, out -> if (stated.isNotEmpty()) changed += "'$stated' (step ${len.toInt()} m) -> '$out' | $runs" }
        val src = IntArray(3); val audit = IntArray(5); val names = IntArray(3); var suspects = 0; var steps = 0; var lanes = 0; var turnsOnStretch = 0
        val notes = ArrayList<String>()
        for (f in dir.listFiles { x -> x.name.endsWith(".txt") }!!.sortedBy { it.name }) {
            val line = app.vela.core.data.google.PolylineCodec.decode(f.readText().trim())
            if (line.size < 5) continue
            val len = app.vela.core.nav.RouteProjection.cumulative(line).last()
            val google = app.vela.core.model.Route(
                polyline = line, legs = emptyList(), distanceMeters = len, durationSeconds = len / 13.0, durationInTrafficSeconds = null,
            )
            Thread.sleep(1100)
            val open = RouteGeometry.route(http, line.first(), line.last(), TravelMode.DRIVE).firstOrNull() ?: continue
            trips++
            // Baseline: the open router's own route down its own line, through the same replay.
            baseline += app.vela.core.nav.NavReplay.analyze(open, app.vela.core.location.DemoTrace.fromRoute(open.polyline).map { LatLng(it.lat, it.lng) })
                .suspects.count { it.type != ManeuverType.STRAIGHT }
            val st = HybridRoute.stretchesFor(line, open)
            val tag = f.name.removeSuffix(".txt")
            if (st.isEmpty()) { noStretch++; continue }
            Thread.sleep(600)
            val openChecked = app.vela.core.data.ValhallaRouter.edges(http, open.polyline, timeoutMs = 20_000)
                ?.let { app.vela.core.data.ValhallaRouter.recheck(open, it, keepUnplaced = true) } ?: open
            val untrusted = ArrayList<HybridRoute.Stretch>()
            val named = st.map { s ->
                Thread.sleep(600)
                val piece = HybridRoute.slice(line, s.fromM, s.toM)
                var why = ""
                app.vela.core.data.ValhallaRouter.onMatchFail = { why = it }
                val m = app.vela.core.data.ValhallaRouter.matchWithEdges(
                    http, piece, timeoutMs = 20_000,
                    startSlackM = if (s.fromM <= 0.0) 150.0 else 0.0, endSlackM = if (s.toM >= len - 1.0) 150.0 else 0.0,
                )
                app.vela.core.data.ValhallaRouter.onMatchFail = null
                if (m != null) { src[0]++; for (k in 0..2) names[k] += m.names[k]; m.off.forEach { untrusted += HybridRoute.Stretch(s.fromM + it.first, s.fromM + it.second) }; return@map s to m.route.maneuvers }
                notes += "$tag: NO MATCH for stretch ${s.fromM.toInt()}..${s.toM.toInt()} m: $why"
                val sub = google.copy(polyline = piece, distanceMeters = s.toM - s.fromM)
                val lines = RoadNameTiles.linesAlong(piece).orEmpty()
                src[if (lines.isEmpty()) 2 else 1]++
                s to LineNamer.name(sub, lines, TravelMode.DRIVE, strict = true)?.maneuvers
            }
            val hybrid = if (named.any { it.second == null }) null else HybridRoute.stitch(google, openChecked, named.map { it.first to it.second!! }, untrusted)
            if (hybrid == null) { notPlaced++; notes += "$tag: NOT PLACED (${st.size} stretches)"; continue }
            steps += hybrid.maneuvers.size; lanes += hybrid.maneuvers.count { it.lanes.isNotEmpty() }
            val a = app.vela.core.nav.StepAudit.check(hybrid)
            audit[0] += a.turns; audit[1] += a.agree; audit[2] += a.otherWay; audit[3] += a.noBend; audit[4] += a.unsaid
            a.findings.forEach { notes += "$tag: AUDIT ${it.atM.toInt()} m ${it.what}" }
            // Names, against the road pieces under the whole line.
            if (len <= 180_000.0) {
                Thread.sleep(600)
                app.vela.core.data.ValhallaRouter.edges(http, line, timeoutMs = 25_000)?.let { edges ->
                    val tally = IntArray(4)
                    val re = app.vela.core.data.ValhallaRouter.recheck(hybrid.maneuvers, edges, keepUnplaced = true, tally = tally)
                    hybrid.maneuvers.zip(re).forEach { (x, y) -> if (x.road != y.road) notes += "$tag: NAME '${x.road}' fails the whole-line check (would be '${y.road}') | ${x.instruction}" }
                }
            }
            // A simulated drive down the line.
            val fixes = app.vela.core.location.DemoTrace.fromRoute(line).map { LatLng(it.lat, it.lng) }
            val rep = app.vela.core.nav.NavReplay.analyze(hybrid, fixes)
            suspects += rep.suspects.count { it.type != ManeuverType.STRAIGHT }
            rep.suspects.forEach { notes += "$tag: DRIVE [${it.index}] ${it.type} \"${it.instruction.take(50)}\" ${it.flags.joinToString("; ")}" }
            var at = 0.0
            println("LINES $tag ${len.toInt()} m, ${st.size} stretch(es), ${hybrid.maneuvers.size} steps | ${a.summary()} | drive suspects ${rep.suspects.size}")
            if (System.getProperty("velaOne") != null) rep.summary().lines().forEach { println("LINES    $it") }
            if (System.getProperty("velaOne") != null) {
                var stN = app.vela.core.nav.NavState()
                fixes.forEachIndexed { i, p -> val (nx, _) = app.vela.core.nav.NavEngine.update(hybrid, stN, p, true); stN = nx
                    if (i in 245..290) println("LINES    FIX $i ${"%.5f,%.5f".format(p.lat, p.lng)} traveled=${nx.traveledM.toInt()} step=${nx.stepIndex} next=${nx.distanceToNextManeuver.toInt()}") }
            }
            if (System.getProperty("velaOne") != null) hybrid.maneuvers.forEach { println("LINES    ${at.toInt()} ${it.type} lanes=${it.lanes.size} | ${it.instruction}"); at += it.distanceMeters }
        }
        println("LINES trips=$trips sameWay=$noStretch notPlaced=$notPlaced | stretches matched ${src[0]} tiles ${src[1]} bare ${src[2]} | matched turn names kept ${names[0]} renamed ${names[1]} bare ${names[2]}")
        println("LINES audit: ${audit[0]} turns, ${audit[1]} agree, ${audit[2]} other way, ${audit[3]} no bend, ${audit[4]} bends unsaid | drive suspects $suspects (the open router's own routes: $baseline) | steps $steps, with lanes $lanes")
        notes.filterNot { " STRAIGHT " in it && "DRIVE" in it }.forEach { println("LINES $it") }
        app.vela.core.data.ValhallaRouter.onChanged = null
        changed.distinct().forEach { println("LINES CHANGED $it") }
    }

    /**
     * HOW MANY LANE ARROWS COULD THERE BE? For every step on a matched stretch of the captured
     * lines, the open router is asked for 150 m either side of that one junction along Google's
     * line and its step there is read: lanes or none. That is the ceiling for lane arrows on the
     * stretches, to hold against what the app carries (logged per step by VelaSteps on a phone).
     */
    @Test fun laneCeilingOnCapturedLines() = runBlocking {
        Assume.assumeTrue("set -DvelaStudy=1", System.getProperty("velaStudy") != null)
        val dir = System.getProperty("velaLines")?.let { java.io.File(it) }
            ?: java.io.File(javaClass.getResource("/google_lines")!!.toURI())
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        var steps = 0; var withLanes = 0; var none = 0; var cannotTell = 0; var openSteps = 0; var openLanes = 0
        val byType = HashMap<String, IntArray>()
        for (f in dir.listFiles { x -> x.name.endsWith(".txt") }!!.sortedBy { it.name }) {
            val line = app.vela.core.data.google.PolylineCodec.decode(f.readText().trim())
            if (line.size < 5) continue
            val cum = app.vela.core.nav.RouteProjection.cumulative(line)
            val len = cum.last()
            val google = app.vela.core.model.Route(polyline = line, legs = emptyList(), distanceMeters = len, durationSeconds = len / 13.0, durationInTrafficSeconds = null)
            Thread.sleep(1100)
            val open = RouteGeometry.route(http, line.first(), line.last(), TravelMode.DRIVE).firstOrNull() ?: continue
            openSteps += open.maneuvers.size - 2; openLanes += open.maneuvers.count { it.lanes.isNotEmpty() }
            val st = HybridRoute.stretchesFor(line, open)
            if (st.isEmpty()) continue
            val untrusted = ArrayList<HybridRoute.Stretch>()
            val named = st.mapNotNull { s ->
                Thread.sleep(600)
                val m = app.vela.core.data.ValhallaRouter.matchWithEdges(
                    http, HybridRoute.slice(line, s.fromM, s.toM), timeoutMs = 20_000,
                    startSlackM = if (s.fromM <= 0.0) 150.0 else 0.0, endSlackM = if (s.toM >= len - 1.0) 150.0 else 0.0,
                ) ?: return@mapNotNull null
                m.off.forEach { untrusted += HybridRoute.Stretch(s.fromM + it.first, s.fromM + it.second) }
                s to m.route.maneuvers
            }
            if (named.size != st.size) continue
            val hybrid = HybridRoute.stitch(google, open, named, untrusted) ?: continue
            val grid = RouteGeometry.SegmentGrid(line, cum)
            var at = 0.0
            for (m in hybrid.maneuvers) {
                val here = at; at += m.distanceMeters
                if (m.type == ManeuverType.DEPART || m.type == ManeuverType.ARRIVE) continue
                if (st.none { here >= it.fromM && here <= it.toM }) continue
                if (here < 160.0 || here > len - 160.0) continue
                steps++
                val a = app.vela.core.nav.RouteProjection.pointAt(line, cum, here - 150.0)
                val b = app.vela.core.nav.RouteProjection.pointAt(line, cum, here + 150.0)
                Thread.sleep(1100)
                val r = RouteGeometry.routeVia(http, listOf(a, b), TravelMode.DRIVE, tries = 1, waypointBearings = listOf(RouteGeometry.headingOnLine(line, a), null)).firstOrNull()
                val rc = r?.let { app.vela.core.nav.RouteProjection.cumulative(it.polyline) }
                val same = r != null && rc != null && rc.last() < 400.0 &&
                    (0..(rc.last() / 30).toInt()).all { grid.along(app.vela.core.nav.RouteProjection.pointAt(r.polyline, rc, it * 30.0), 15.0) != null }
                val key = m.type.name
                val row = byType.getOrPut(key) { IntArray(3) }
                if (!same) { cannotTell++; row[2]++; continue }
                val laned = r!!.maneuvers.any { it.lanes.isNotEmpty() && it.location.distanceTo(m.location) <= 25.0 }
                if (laned) { withLanes++; row[0]++ } else { none++; row[1]++ }
            }
            println("LANES ${f.name}: so far $steps steps, $withLanes with lane data, $none without, $cannotTell not told")
        }
        println("LANES stretch steps $steps | the open router has lanes at $withLanes | none at $none | could not tell at $cannotTell (its path there is not the line)")
        println("LANES for scale, the open router's own routes: $openLanes of $openSteps steps carry lanes")
        byType.toSortedMap().forEach { (k, v) -> println("LANES   $k: lanes ${v[0]}, none ${v[1]}, not told ${v[2]}") }
    }
}
