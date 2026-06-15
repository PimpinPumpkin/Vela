package app.carto.core.data.google.parse

import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.google.arr
import app.carto.core.data.google.at
import app.carto.core.data.google.dbl
import app.carto.core.data.google.str
import app.carto.core.model.LatLng
import app.carto.core.model.Maneuver
import app.carto.core.model.ManeuverType
import app.carto.core.model.Route
import app.carto.core.model.RouteLeg
import app.carto.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Parses the `/maps/preview/directions` response.
 *
 * Schema calibrated against a live capture (2026-06-15):
 *   routes        `root[0][1]`            (array of alternative routes)
 *   per route `r` summary at `r[0]`:
 *     distance m  `[2][0]`   ("10.6 miles" text at `[2][1]`)
 *     duration s  `[3][0]`   typical/no-traffic ("22 min" at `[3][1]`)
 *     traffic s   `[10][0][0]`  live duration_in_traffic ("18 min") — the goal
 *     start pt    `[7][3][2]` = [.., .., lat, lng]
 *     end pt      `[7][3][3]`
 *   steps         emitted as `<step maneuver='TURN_LEFT' meters='120'>…</step>`
 *                 markup strings scattered through the route subtree.
 *
 * The exact encoded overview-polyline field did not decode cleanly during
 * calibration, so geometry is currently APPROXIMATED from the in-bounds
 * coordinate points present in the route subtree. This is good enough to draw a
 * route and to place maneuvers for ETA/preview; tightening it to the true
 * per-step polyline is the one remaining calibration item (CALIBRATE: geometry).
 */
object DirectionsParser {

    fun parse(root: JsonElement): List<Route> {
        val routes = root.at(0, 1).arr()
            ?: throw CalibrationNeededException("directions routes (root[0][1])")
        val parsed = routes.mapNotNull { runCatching { parseRoute(it) }.getOrNull() }
        if (parsed.isEmpty()) throw CalibrationNeededException("directions: 0 routes parsed")
        return parsed
    }

    private fun parseRoute(route: JsonElement): Route? {
        val summary = route.at(0) ?: return null
        val distance = summary.at(2, 0).dbl() ?: return null
        val typicalDur = summary.at(3, 0).dbl() ?: return null
        val trafficDur = summary.at(10, 0, 0).dbl() // null when no live traffic (e.g. off-peak)

        val start = coord(summary.at(7, 3, 2))
        val end = coord(summary.at(7, 3, 3))
        val polyline = approximatePolyline(route, start, end)
        val maneuvers = placeManeuvers(collectSteps(route), polyline)

        return Route(
            polyline = polyline.ifEmpty { listOfNotNull(start, end) },
            legs = listOf(RouteLeg(distance, typicalDur, trafficDur, maneuvers)),
            distanceMeters = distance,
            durationSeconds = typicalDur,
            durationInTrafficSeconds = trafficDur,
            summary = summary.at(1).str(),
        )
    }

    private fun coord(node: JsonElement?): LatLng? {
        val lat = node.at(2).dbl()
        val lng = node.at(3).dbl()
        return if (lat != null && lng != null) LatLng(lat, lng) else null
    }

    // --- maneuvers ----------------------------------------------------------

    private val MANEUVER_ATTR = Regex("maneuver='([^']+)'")
    private val METERS_ATTR = Regex("meters='([0-9]+)'")
    private val TAGS = Regex("<[^>]+>")
    private val WS = Regex("\\s+")

    private fun collectSteps(route: JsonElement): List<Maneuver> {
        val raw = ArrayList<String>()
        fun walk(n: JsonElement) {
            when (n) {
                is JsonArray -> n.forEach(::walk)
                is JsonPrimitive -> n.str()?.let { if (it.contains("<step ")) raw.add(it) }
                else -> {}
            }
        }
        walk(route)
        return raw.map { parseStep(it) }
    }

    private fun parseStep(s: String): Maneuver {
        val type = mapType(MANEUVER_ATTR.find(s)?.groupValues?.get(1))
        val meters = METERS_ATTR.find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val text = s.replace(TAGS, " ").replace(WS, " ").trim()
        return Maneuver(type, text.ifEmpty { "Continue" }, LatLng(0.0, 0.0), meters, 0.0)
    }

    private fun mapType(s: String?): ManeuverType = when (s?.uppercase()) {
        "DEPART" -> ManeuverType.DEPART
        "DESTINATION", "ARRIVE" -> ManeuverType.ARRIVE
        "TURN_LEFT" -> ManeuverType.TURN_LEFT
        "TURN_RIGHT" -> ManeuverType.TURN_RIGHT
        "TURN_SLIGHT_LEFT" -> ManeuverType.SLIGHT_LEFT
        "TURN_SLIGHT_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "TURN_SHARP_LEFT" -> ManeuverType.SHARP_LEFT
        "TURN_SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
        "UTURN", "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverType.UTURN
        "STRAIGHT", "CONTINUE", "NAME_CHANGE" -> ManeuverType.STRAIGHT
        "MERGE", "MERGE_LEFT", "MERGE_RIGHT" -> ManeuverType.MERGE
        "FORK_LEFT" -> ManeuverType.FORK_LEFT
        "FORK_RIGHT" -> ManeuverType.FORK_RIGHT
        "RAMP_LEFT", "ON_RAMP_LEFT", "OFF_RAMP_LEFT" -> ManeuverType.RAMP_LEFT
        "RAMP_RIGHT", "ON_RAMP_RIGHT", "OFF_RAMP_RIGHT" -> ManeuverType.RAMP_RIGHT
        "KEEP_LEFT" -> ManeuverType.KEEP_LEFT
        "KEEP_RIGHT" -> ManeuverType.KEEP_RIGHT
        else -> if (s?.contains("ROUNDABOUT") == true) ManeuverType.ROUNDABOUT else ManeuverType.UNKNOWN
    }

    /** Position each maneuver along the polyline by its cumulative step distance. */
    private fun placeManeuvers(maneuvers: List<Maneuver>, polyline: List<LatLng>): List<Maneuver> {
        if (maneuvers.isEmpty() || polyline.size < 2) return maneuvers
        val total = maneuvers.sumOf { it.distanceMeters }.coerceAtLeast(1.0)
        var cum = 0.0
        return maneuvers.map { m ->
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, (cum / total).coerceIn(0.0, 1.0)))
        }
    }

    private fun pointAlong(poly: List<LatLng>, frac: Double): LatLng {
        val lengths = DoubleArray(poly.size - 1) { poly[it].distanceTo(poly[it + 1]) }
        val target = lengths.sum() * frac
        var acc = 0.0
        for (i in lengths.indices) {
            if (acc + lengths[i] >= target) {
                val f = if (lengths[i] == 0.0) 0.0 else (target - acc) / lengths[i]
                val a = poly[i]; val b = poly[i + 1]
                return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
            acc += lengths[i]
        }
        return poly.last()
    }

    // --- geometry (approximate; CALIBRATE) ----------------------------------

    private fun approximatePolyline(route: JsonElement, start: LatLng?, end: LatLng?): List<LatLng> {
        if (start == null || end == null) return emptyList()
        val minLat = min(start.lat, end.lat) - 0.25
        val maxLat = max(start.lat, end.lat) + 0.25
        val minLng = min(start.lng, end.lng) - 0.25
        val maxLng = max(start.lng, end.lng) + 0.25
        val pts = ArrayList<LatLng>()
        fun walk(n: JsonElement) {
            if (n is JsonArray) {
                for (i in 0 until n.size - 1) {
                    val a = n[i].dbl()
                    val b = n[i + 1].dbl()
                    if (a != null && b != null && a in minLat..maxLat && b in minLng..maxLng) {
                        pts.add(LatLng(a, b))
                    }
                }
                n.forEach(::walk)
            }
        }
        walk(route)
        val dedup = ArrayList<LatLng>()
        for (p in pts) if (dedup.isEmpty() || dedup.last() != p) dedup.add(p)
        return (listOf(start) + dedup + end)
    }
}
