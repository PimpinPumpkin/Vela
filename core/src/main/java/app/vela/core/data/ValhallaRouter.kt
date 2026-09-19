package app.vela.core.data

import app.vela.core.VelaConfig
import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
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

    /** The main trip first, then any alternates. Public for the parser test. */
    fun parse(text: String): List<Route> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val main = root["trip"]?.jsonObject?.let { parseTrip(it) } ?: return emptyList()
        val alts = root["alternates"]?.jsonArray?.mapNotNull { it.jsonObject["trip"]?.jsonObject?.let { t -> parseTrip(t) } }.orEmpty()
        return listOf(main) + alts
    }

    private fun parseTrip(trip: JsonObject): Route? {
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
                val names = m["street_names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val road = names.firstOrNull()?.takeIf { it.isNotBlank() }
                val sameRoad = road != null && road == prevRoad
                val (type, mod) = osrmGrammar(vType, road, sameRoad)
                val rbExit = m["roundabout_exit_count"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 && type == "roundabout" }
                val begin = m["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: 0
                val end = m["end_shape_index"]?.jsonPrimitive?.intOrNull ?: begin
                val at = shape.getOrNull(if (type == "arrive") end else begin) ?: shape.last()
                val side = if (type == "arrive") mod else null
                raw += Maneuver(
                    type = RouteGeometry.osrmType(type, mod),
                    instruction = RouteGeometry.osrmPhrase(type, mod, road, null, null, rbExit),
                    instructionNoRoad = RouteGeometry.osrmPhrase(type, mod, null, null, null, rbExit),
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
