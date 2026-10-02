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
}
