package app.vela.core.data

import app.vela.core.VelaConfig
import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Bike routes that prefer bike lanes and quiet streets (issue #401), from the FOSSGIS Valhalla
 * server (fair use, no key, the OSRM backends' sibling).
 *
 * OSRM's bicycle profile weights speed, so it happily puts you on an arterial with no lane when
 * that is two minutes faster. Valhalla's bicycle costing has a `use_roads` knob: near zero it
 * hunts for signed cycle routes, cycleways, lanes and residential streets, the behavior people
 * describe as Google-like. Probed 2026-09-14 on the Davis fixture: the same trip came back as
 * 26 maneuvers along a cycleway corridor at 0.1 and as four turns down a county road at 0.9.
 *
 * Maneuvers are translated into the OSRM grammar ([RouteGeometry.osrmType], [RouteGeometry.osrmPhrase])
 * so the banner, the voice and the step list read exactly as they do for every other route,
 * localized by the active NavStrings rather than by Valhalla's own English text.
 */
object ValhallaRouter {
    private const val BASE = "https://valhalla1.openstreetmap.de/route"
    private const val PRECISION = 6 // Valhalla shapes are polyline6
    private val ROUTE_NUMBER = Regex("""^(I|US|SR|CR|CA|[A-Z]{1,3})[ -]?\d+""")

    /** Costing knobs. `use_roads` low is the whole point; `use_hills` middling so a flat detour
     *  is not taken to absurd lengths; a hybrid bike tolerates the odd gravel path. */
    private const val USE_ROADS = 0.1
    private const val USE_HILLS = 0.5
    private const val BICYCLE_TYPE = "Hybrid"

    private val json = Json { ignoreUnknownKeys = true }

    /** Safety-weighted bicycle route(s) through [points] (origin, stops, destination). Alternates
     *  only for a plain origin-to-destination trip, like OSRM. Empty on any failure. */
    fun route(http: OkHttpClient, points: List<LatLng>, alternates: Boolean, tries: Int = 2): List<Route> {
        if (points.size < 2) return emptyList()
        val body = buildJsonObject {
            putJsonArray("locations") {
                points.forEachIndexed { i, p ->
                    add(buildJsonObject {
                        put("lat", p.lat); put("lon", p.lng)
                        // Stops are places to pass through, not to stop at: "through" keeps the
                        // leg from ending with a U-turn back onto the same street.
                        if (i in 1 until points.lastIndex) put("type", "through")
                    })
                }
            }
            put("costing", "bicycle")
            putJsonObject("costing_options") {
                putJsonObject("bicycle") {
                    put("bicycle_type", BICYCLE_TYPE)
                    put("use_roads", USE_ROADS)
                    put("use_hills", USE_HILLS)
                }
            }
            put("units", "kilometers")
            if (alternates && points.size == 2) put("alternates", 2)
        }.toString()
        val req = Request.Builder()
            .url(BASE)
            .header("User-Agent", VelaConfig.USER_AGENT)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        repeat(tries) { attempt ->
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return parse(resp.body?.string().orEmpty())
                    }
                    if (resp.code in 400..499) return emptyList()
                }
            } catch (e: Exception) {
                // network blip: retry
            }
            if (attempt < tries - 1) runCatching { Thread.sleep(200L * (attempt + 1)) }
        }
        return emptyList()
    }

    /**
     * MAP MATCHING (2026-10-02): the road network's own steps for a line that was drawn by
     * someone else (Google's route). `trace_route` snaps [shape] onto the graph and answers with
     * the maneuvers of the roads it matched, so a street name here is the name of the road the
     * line is on, not of the nearest label. Null on any failure, and null when the match does not
     * FOLLOW the line: the service answers a line shifted 40 m off the roads with a confident,
     * wrong route, so the result is kept only when it stays within [MATCH_OFF_M] of the input
     * both ways and its length agrees within [MATCH_LENGTH_SLACK]. One request, no retry (the
     * caller has a fallback), at most 200 km (the server's own limit; callers send stretches).
     */
    fun match(http: OkHttpClient, shape: List<LatLng>, costing: String = "auto", timeoutMs: Long = 3_000): Route? =
        matchWithEdges(http, shape, costing, timeoutMs)?.route

    /** A match and the edges its turn names were checked against (null = none came back). */
    class Match(val route: Route, val edges: List<Edge>?, val names: IntArray)

    /** The road pieces under [shape], for checking names from another router. Null on failure. */
    fun edges(http: OkHttpClient, shape: List<LatLng>, costing: String = "auto", timeoutMs: Long = 2_500): List<Edge>? {
        if (shape.size < 2) return null
        val client = http.newBuilder().callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        return matchedEdges(client, shape, costing)
    }

    /** Maneuver types whose road is a street turned onto. */
    private val TURN_MANEUVERS = setOf(
        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
        ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.CONTINUE, ManeuverType.STRAIGHT,
    )

    /**
     * [maneuvers] (any router's) with each turn's street put through [checkedRoad] against
     * [edges]. A turn that cannot be found among the edges keeps its name when [keepUnplaced],
     * else loses it. [tally] counts kept, renamed, bare, unplaced.
     */
    fun recheck(maneuvers: List<Maneuver>, edges: List<Edge>, keepUnplaced: Boolean, tally: IntArray? = null): List<Maneuver> {
        val check = NameCheck(edges)
        return maneuvers.map { m ->
            val stated = m.road
            if (stated == null || m.type !in TURN_MANEUVERS) return@map m
            val r = check.road(stated, m.location, m.distanceMeters, unplaced = UNPLACED)
            when {
                r === UNPLACED -> { tally?.let { it[3]++ }; if (keepUnplaced) m else bare(m) }
                r == null -> { tally?.let { it[2]++ }; bare(m) }
                r.equals(stated, ignoreCase = true) -> { tally?.let { it[0]++ }; m }
                m.instruction.contains(stated) -> { tally?.let { it[1]++ }; m.copy(instruction = m.instruction.replace(stated, r), road = r, ref = null) }
                else -> { tally?.let { it[2]++ }; bare(m) }
            }
        }
    }

    fun recheck(route: Route, edges: List<Edge>, keepUnplaced: Boolean, tally: IntArray? = null): Route {
        val all = recheck(route.maneuvers, edges, keepUnplaced, tally)
        var k = 0
        return route.copy(legs = route.legs.map { leg -> leg.copy(maneuvers = all.subList(k, k + leg.maneuvers.size).toList()).also { k += leg.maneuvers.size } })
    }

    private fun bare(m: Maneuver) = m.copy(instruction = m.instructionNoRoad ?: m.instruction, road = null, ref = null)
    private val UNPLACED = String(charArrayOf('?'))

    fun matchWithEdges(
        http: OkHttpClient, shape: List<LatLng>, costing: String = "auto", timeoutMs: Long = 3_000,
        startSlackM: Double = 0.0, endSlackM: Double = 0.0,
    ): Match? {
        if (shape.size < 2) return null
        val body = buildJsonObject {
            putJsonArray("shape") { shape.forEach { p -> add(buildJsonObject { put("lat", p.lat); put("lon", p.lng) }) } }
            put("costing", costing)
            put("shape_match", "map_snap")
            put("units", "kilometers")
        }.toString()
        val req = Request.Builder()
            .url(BASE.removeSuffix("/route") + "/trace_route")
            .header("User-Agent", VelaConfig.VELA_UA)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val client = http.newBuilder().callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        // The matched path's own edges, asked for alongside: every turn's street name is checked
        // against them (see [checkedRoad]). No edges = no street names on turns.
        val edgesAsync = java.util.concurrent.CompletableFuture.supplyAsync { matchedEdges(client, shape, costing) }
        val text = runCatching {
            client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        }.getOrNull() ?: return null
        val edges = runCatching { edgesAsync.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        val check = NameCheck(edges)
        val matched = parse(text, check).firstOrNull() ?: return null
        return if (followsLine(matched.polyline, shape, startSlackM = startSlackM, endSlackM = endSlackM)) Match(matched, edges, check.tally) else null
    }

    /** One edge of the matched path: the names the road carries there, in travel order. */
    data class Edge(val names: List<String>, val lengthM: Double, val begin: LatLng, val soft: Boolean)

    /** The edges `trace_attributes` matched [shape] to, or null. [Edge.soft] marks the pieces
     *  inside a junction (internal edges, turn channels), which carry whichever street's name
     *  the mapper gave them. */
    private fun matchedEdges(client: OkHttpClient, shape: List<LatLng>, costing: String): List<Edge>? {
        val body = buildJsonObject {
            putJsonArray("shape") { shape.forEach { p -> add(buildJsonObject { put("lat", p.lat); put("lon", p.lng) }) } }
            put("costing", costing)
            put("shape_match", "map_snap")
            putJsonObject("filters") {
                putJsonArray("attributes") {
                    listOf("edge.names", "edge.length", "edge.begin_shape_index", "edge.internal_intersection", "edge.use", "shape").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
                put("action", "include")
            }
        }.toString()
        val req = Request.Builder()
            .url(BASE.removeSuffix("/route") + "/trace_attributes")
            .header("User-Agent", VelaConfig.VELA_UA)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val text = runCatching {
            client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        }.getOrNull() ?: return null
        return parseEdges(text)
    }

    /** Public for the parser test. */
    fun parseEdges(text: String): List<Edge>? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val pts = root["shape"]?.jsonPrimitive?.contentOrNull?.let { PolylineCodec.decode(it, PRECISION) } ?: return null
        val out = root["edges"]?.jsonArray?.mapNotNull { el ->
            val e = el.jsonObject
            val at = pts.getOrNull(e["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null) ?: return@mapNotNull null
            Edge(
                names = e["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                lengthM = (e["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000.0,
                begin = at,
                soft = e["internal_intersection"]?.jsonPrimitive?.contentOrNull == "true" || e["use"]?.jsonPrimitive?.contentOrNull == "turn_channel",
            )
        }
        return out?.takeIf { it.isNotEmpty() }
    }

    /**
     * Checks a turn's street name against the road the matched path is on after the turn
     * (2026-10-02). The service's own step text skips junction pieces when it picks a name, so a
     * left onto a street that becomes a bridge 120 m on was "Turn left onto <the bridge>", and a
     * right onto a bridge off an embankment was "stay on <the embankment>" because the first 6 m
     * still carry that name. The edges say what the road is called where the car is.
     * No edges at all (the second request failed): turns go out without a street name.
     */
    internal class NameCheck(private val edges: List<Edge>?) {
        private var cursor = 0
        /** Turns kept, renamed, left bare (by the parser that uses this check). */
        val tally = IntArray(3)

        /** The name to say for a turn at [at] that the service calls [stated], or null for none. */
        fun road(stated: String, at: LatLng, stepLenM: Double, unplaced: String? = null): String? {
            val es = edges ?: return null
            // The edge that BEGINS at the turn: the nearest one going forward, not the first one
            // near it (the last piece of the street being left starts a few meters before).
            var best = -1; var bestD = EDGE_AT_M
            var i = cursor
            while (i < es.size) {
                val d = es[i].begin.distanceTo(at)
                if (d < bestD) { best = i; bestD = d } else if (best >= 0 && d > bestD + EDGE_PAST_M) break
                i++
            }
            if (best < 0) return unplaced
            cursor = best
            return checkedRoad(stated, stepLenM, es.subList(best, es.size))
        }
    }

    private class Run(val names: List<String>, val startM: Double, var lenM: Double, var soft: Boolean) {
        val named get() = names.isNotEmpty()
        fun has(n: String) = names.any { it.equals(n, ignoreCase = true) }
        val primary get() = names.firstOrNull { !ROUTE_NUMBER.containsMatchIn(it) } ?: names.firstOrNull()
    }

    /**
     * [stated] if the path really is on that street after the turn: it starts within a short
     * lead-in (unnamed pieces, junction pieces, stubs under [STUB_M]) and holds [HOLD_M], or half
     * the step when the step is shorter. Otherwise the first street the path stays on for
     * [RENAME_HOLD_M] with nothing but stubs before it; otherwise null (say no name).
     */
    internal fun checkedRoad(stated: String, stepLenM: Double, after: List<Edge>): String? {
        val runs = ArrayList<Run>()
        var acc = 0.0
        for (e in after) {
            val last = runs.lastOrNull()
            val same = last != null && (if (last.named) last.primary?.let { p -> e.names.any { it.equals(p, ignoreCase = true) } } == true else e.names.isEmpty())
            if (same) { last!!.lenM += e.lengthM; last.soft = last.soft && e.soft } else runs += Run(e.names, acc, e.lengthM, e.soft)
            acc += e.lengthM
            if (acc > LOOK_M) break
        }
        val at = runs.indexOfFirst { it.has(stated) }
        if (at >= 0) {
            val lead = runs.subList(0, at)
            val namedLead = lead.filter { it.named }
            val leadOk = runs[at].startM <= LEAD_MAX_M &&
                namedLead.all { it.soft || it.lenM <= STUB_M } && namedLead.sumOf { it.lenM } <= NAMED_LEAD_MAX_M
            if (leadOk && runs[at].lenM >= minOf(HOLD_M, stepLenM * 0.5)) return stated
        }
        for (r in runs) {
            if (!r.named) continue
            if (r.lenM >= RENAME_HOLD_M && !r.has(stated)) return r.primary
            if (r.lenM > STUB_M) return null
        }
        return null
    }

    const val EDGE_AT_M = 12.0
    const val EDGE_PAST_M = 40.0
    const val LOOK_M = 300.0
    const val HOLD_M = 20.0
    const val RENAME_HOLD_M = 40.0
    const val STUB_M = 15.0
    const val LEAD_MAX_M = 150.0
    const val NAMED_LEAD_MAX_M = 40.0
    /** Valhalla maneuver types whose name is a street the car turns onto (not a ramp's
     *  destination, a merge, a roundabout or the start). */
    private val TURN_TYPES = setOf(7, 8, 9, 10, 11, 14, 15, 16, 22)

    /** How far a matched route may sit from the line it was matched to, and how much their
     *  lengths may differ. Two drawings of one road differ by a lane or two; another road does not. */
    const val MATCH_OFF_M = 22.0
    const val MATCH_LENGTH_SLACK = 0.06

    /** True when [matched] and [line] are the same path: lengths agree and each stays beside
     *  the other along its whole length (sampled every 30 m, both directions). */
    fun followsLine(matched: List<LatLng>, line: List<LatLng>, offM: Double = MATCH_OFF_M, startSlackM: Double = 0.0, endSlackM: Double = 0.0): Boolean {
        if (matched.size < 2 || line.size < 2) return false
        fun cum(l: List<LatLng>) = DoubleArray(l.size).also { c -> for (i in 1 until l.size) c[i] = c[i - 1] + l[i - 1].distanceTo(l[i]) }
        val cm = cum(matched); val cl = cum(line)
        val lm = cm.last(); val ll = cl.last()
        if (ll <= 0.0 || kotlin.math.abs(lm - ll) > ll * MATCH_LENGTH_SLACK + 30.0 + startSlackM + endSlackM) return false
        // The slack is for the LINE's own ends at the start and end of a trip: a line that begins
        // by circling a parking lot is matched to the street beside it (and the match may go
        // round by the street where the line cuts through the lot), and that is the same trip.
        // Past the slack both have to lie on each other everywhere.
        fun beside(a: List<LatLng>, ca: DoubleArray, b: List<LatLng>, cb: DoubleArray, skipStart: Double = 0.0, skipEnd: Double = 0.0): Boolean {
            val grid = RouteGeometry.SegmentGrid(b, cb)
            var m = skipStart
            while (m <= ca.last() - skipEnd) {
                if (grid.along(app.vela.core.nav.RouteProjection.pointAt(a, ca, m), offM) == null) return false
                m += 30.0
            }
            return true
        }
        return beside(matched, cm, line, cl, startSlackM, endSlackM) && beside(line, cl, matched, cm, startSlackM, endSlackM)
    }

    /** The main trip first, then any alternates. Public for the parser test. */
    fun parse(text: String): List<Route> = parse(text, null)

    /** [check] set = a map match: turn names are checked against the matched edges. */
    internal fun parse(text: String, check: NameCheck?): List<Route> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val main = root["trip"]?.jsonObject?.let { parseTrip(it, check) } ?: return emptyList()
        val alts = root["alternates"]?.jsonArray?.mapNotNull { it.jsonObject["trip"]?.jsonObject?.let { t -> parseTrip(t) } }.orEmpty()
        return listOf(main) + alts
    }

    private fun parseTrip(trip: JsonObject, check: NameCheck? = null): Route? {
        val legs = trip["legs"]?.jsonArray?.map { it.jsonObject } ?: return null
        if (legs.isEmpty()) return null
        val polyline = ArrayList<LatLng>()
        val raw = ArrayList<Maneuver>()
        legs.forEachIndexed { li, leg ->
            val shape = leg["shape"]?.jsonPrimitive?.contentOrNull?.let { PolylineCodec.decode(it, PRECISION) } ?: return null
            if (shape.size < 2) return null
            // Legs share their joint point; drop the duplicate so the line stays clean.
            polyline.addAll(if (li == 0) shape else shape.drop(1))
            val mans = leg["maneuvers"]?.jsonArray?.map { it.jsonObject } ?: return null
            val steps = mans.map { m ->
                val vType = m["type"]?.jsonPrimitive?.intOrNull ?: 0
                RouteGeometry.RbStep(
                    type = osrmGrammar(vType, null, false).first,
                    bearingBefore = m["bearing_before"]?.jsonPrimitive?.doubleOrNull,
                    bearingAfter = m["bearing_after"]?.jsonPrimitive?.doubleOrNull,
                )
            }
            val geoms = RouteGeometry.roundaboutGeoms(steps)
            var prevRoad: String? = null
            mans.forEachIndexed { i, m ->
                val vType = m["type"]?.jsonPrimitive?.intOrNull ?: 0
                // The street AT the turn. When the road changes its name along the step, Valhalla puts
                // the name it starts with in `begin_street_names` and the one it carries on with in
                // `street_names` ("Turn right onto Atlantic Avenue. Continue on Commercial Street"):
                // the instruction has to name the first, or it names a street the driver is not
                // turning onto (found by the naming study, 2026-10-02).
                val names = (m["begin_street_names"] ?: m["street_names"])?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                // A route number among the names ("US 50" beside "Capital City Freeway") is the
                // shield; the first real name is the road. A road with only a number keeps it.
                // (Not a heritage designation: "US 40 Historic" on a downtown street is on no sign.)
                val statedRef = names.firstOrNull { ROUTE_NUMBER.containsMatchIn(it) && !it.contains("historic", ignoreCase = true) }
                val stated = (names.firstOrNull { !ROUTE_NUMBER.containsMatchIn(it) } ?: names.firstOrNull())?.takeIf { it.isNotBlank() }
                val beginAt = shape.getOrNull(m["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: 0) ?: shape.first()
                val road = if (check != null && stated != null && vType in TURN_TYPES)
                    check.road(stated, beginAt, (m["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000.0)
                        .also { check.tally[if (it == null) 2 else if (it == stated) 0 else 1]++ } else stated
                val ref = statedRef?.takeIf { road == stated }
                // The sign at a ramp or exit: its number, and where it says it goes (the route
                // numbers first, then the towns, the open router's "I 5 North: Redding" form).
                val sign = m["sign"]?.jsonObject
                fun texts(key: String) = sign?.get(key)?.jsonArray?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val exitNo = texts("exit_number_elements").firstOrNull()
                val branch = texts("exit_branch_elements"); val toward = texts("exit_toward_elements")
                val dest = when {
                    branch.isNotEmpty() && toward.isNotEmpty() -> branch.joinToString(", ") + ": " + toward.joinToString(", ")
                    branch.isNotEmpty() -> branch.joinToString(", ")
                    toward.isNotEmpty() -> toward.joinToString(", ")
                    else -> null
                }
                val sameRoad = road != null && road == prevRoad
                val (type, mod) = osrmGrammar(vType, road, sameRoad)
                val rbExit = m["roundabout_exit_count"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 && type == "roundabout" }
                val begin = m["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: 0
                val end = m["end_shape_index"]?.jsonPrimitive?.intOrNull ?: begin
                val at = shape.getOrNull(if (type == "arrive") end else begin) ?: shape.last()
                val side = if (type == "arrive") mod else null
                raw += Maneuver(
                    type = RouteGeometry.osrmType(type, mod),
                    instruction = RouteGeometry.osrmPhrase(type, mod, road, dest, exitNo, rbExit),
                    instructionNoRoad = RouteGeometry.osrmPhrase(type, mod, null, dest, exitNo, rbExit),
                    ref = ref,
                    roundaboutExit = rbExit,
                    side = side,
                    location = at,
                    distanceMeters = (m["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000.0,
                    durationSeconds = m["time"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    road = road,
                    roundabout = geoms.getOrNull(i),
                )
                if (road != null) prevRoad = road
            }
        }
        if (polyline.size < 2) return null
        // Interior legs end with an arrive and begin with a depart; fold those into the previous
        // maneuver so the trip reads continuous and the step lengths keep tiling the polyline,
        // the same rule parseOsrmRoute applies to via boundaries.
        val last = raw.lastIndex
        val folded = mutableListOf<Maneuver>()
        raw.forEachIndexed { i, m ->
            val boundary = (m.type == ManeuverType.DEPART && i != 0) || (m.type == ManeuverType.ARRIVE && i != last)
            if (!boundary) {
                folded += m
            } else if (folded.isNotEmpty() && m.distanceMeters > 0.0) {
                val prev = folded.removeAt(folded.lastIndex)
                folded += prev.copy(distanceMeters = prev.distanceMeters + m.distanceMeters, durationSeconds = prev.durationSeconds + m.durationSeconds)
            }
        }
        val maneuvers = RouteGeometry.foldRenames(RouteGeometry.consolidateExits(folded))
        if (maneuvers.size < 2) return null
        // The steps' own sums, not the trip summary: Valhalla rounds each step to whole meters
        // and the summary separately, and NavEngine locates maneuvers by a prefix sum of steps,
        // so the route's length must be the one the steps tile (a few meters apart otherwise).
        val dist = maneuvers.sumOf { it.distanceMeters }
        val dur = maneuvers.sumOf { it.durationSeconds }
        return Route(
            source = app.vela.core.model.RouteSource.VALHALLA,
            polyline = polyline,
            legs = listOf(RouteLeg(dist, dur, null, maneuvers)),
            distanceMeters = dist,
            durationSeconds = dur,
            durationInTrafficSeconds = null,
            summary = maneuvers.filter { it.road != null }.maxByOrNull { it.distanceMeters }?.road,
        )
    }

    /**
     * Valhalla maneuver type -> OSRM (type, modifier), the grammar the rest of nav speaks.
     * A slight bend that keeps the road name is Valhalla's "bear left to stay on X": a rename-class
     * non-event, folded silent like OSRM's "new name", never a spoken turn. Numbers per Valhalla's
     * TripDirections maneuver enum.
     */
    internal fun osrmGrammar(vType: Int, road: String?, sameRoad: Boolean): Pair<String, String?> = when (vType) {
        1 -> "depart" to null
        2 -> "depart" to "right"
        3 -> "depart" to "left"
        4 -> "arrive" to null
        5 -> "arrive" to "right"
        6 -> "arrive" to "left"
        7 -> "new name" to "straight"      // becomes
        8 -> "continue" to "straight"
        9 -> if (sameRoad) "new name" to "straight" else "turn" to "slight right"
        10 -> "turn" to "right"
        11 -> "turn" to "sharp right"
        12 -> "turn" to "uturn"            // U-turn right
        13 -> "turn" to "uturn"            // U-turn left
        14 -> "turn" to "sharp left"
        15 -> "turn" to "left"
        16 -> if (sameRoad) "new name" to "straight" else "turn" to "slight left"
        17 -> "on ramp" to "straight"
        18 -> "on ramp" to "right"
        19 -> "on ramp" to "left"
        20 -> "off ramp" to "right"
        21 -> "off ramp" to "left"
        22 -> "continue" to "straight"     // stay straight
        23 -> "fork" to "slight right"
        24 -> "fork" to "slight left"
        25 -> "merge" to null
        26 -> "roundabout" to null
        27 -> "exit roundabout" to null
        37 -> "merge" to "slight right"
        38 -> "merge" to "slight left"
        else -> "continue" to "straight"   // ferries, transit, steps, elevators: nothing to steer
    }
}
