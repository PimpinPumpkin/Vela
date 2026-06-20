package app.vela.core.data.google.parse

import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.int
import app.vela.core.data.google.long
import app.vela.core.data.google.str
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TrafficSpan
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
        // Google ships each route's real geometry as delta-encoded E7 coordinate
        // arrays at root[0][7][i] (index-aligned with the route summaries) — so the
        // drawn line follows the actual roads of *that* route, alternates included.
        val geoms = root.at(0, 7).arr()
        val parsed = routes.mapIndexedNotNull { i, r ->
            runCatching { parseRoute(r, decodeGeometry(geoms?.getOrNull(i))) }.getOrNull()
        }
        if (parsed.isEmpty()) throw CalibrationNeededException("directions: 0 routes parsed")
        return parsed
    }

    private fun parseRoute(route: JsonElement, googleGeometry: List<LatLng>?): Route? {
        val summary = route.at(0) ?: return null
        val distance = summary.at(2, 0).dbl() ?: return null
        val typicalDur = summary.at(3, 0).dbl() ?: return null
        val trafficDur = summary.at(10, 0, 0).dbl() // null when no live traffic (e.g. off-peak)

        val start = coord(summary.at(7, 3, 2))
        val end = coord(summary.at(7, 3, 3))
        // Google's own geometry when present; otherwise a straight start→end segment
        // (the data source can still snap that to an open router). Never a guess that
        // doubles back on itself.
        val polyline = googleGeometry?.takeIf { it.size >= 2 } ?: listOfNotNull(start, end)
        val maneuvers = placeManeuvers(collectSteps(route), polyline)

        return Route(
            polyline = polyline.ifEmpty { listOfNotNull(start, end) },
            legs = listOf(RouteLeg(distance, typicalDur, trafficDur, maneuvers)),
            distanceMeters = distance,
            durationSeconds = typicalDur,
            durationInTrafficSeconds = trafficDur,
            summary = summary.at(1).str(),
            trafficSpans = parseTrafficSpans(route),
        )
    }

    /** Per-segment live traffic: `route[3][5][0]` is a list of `[level, startMeters,
     *  lengthMeters]` — only the congested stretches (free-flow gaps are omitted).
     *  Note this hangs off the route node itself, NOT the `[0]` summary. Calibrated
     *  2026-06-19 against Davis→Sac + Berkeley→SF (levels 1=moderate, 2=heavy seen;
     *  span starts+lengths chain contiguously through each jam, sum < route length). */
    private fun parseTrafficSpans(route: JsonElement): List<TrafficSpan> {
        val arr = route.at(3, 5, 0).arr() ?: return emptyList()
        return arr.mapNotNull { s ->
            val level = s.at(0).int() ?: return@mapNotNull null
            val start = s.at(1).dbl() ?: return@mapNotNull null
            val len = s.at(2).dbl() ?: return@mapNotNull null
            // Only congested spans are listed; drop a zero-length or stray non-graded
            // (level < 1) entry so the route gradient never paints a free-flow stretch.
            if (len <= 0.0 || level < 1) null else TrafficSpan(level, start, len)
        }.sortedBy { it.startMeters } // gradient stops must walk start→end in order
    }

    /** Decode a route-geometry node: `[0]` = latitude deltas (E7, first element
     *  absolute), `[1]` = longitude deltas — into a real polyline. */
    private fun decodeGeometry(node: JsonElement?): List<LatLng>? {
        val lat = node.at(0).arr() ?: return null
        val lng = node.at(1).arr() ?: return null
        if (lat.size != lng.size || lat.size < 2) return null
        var la = 0L
        var ln = 0L
        return lat.indices.map { i ->
            la += lat[i].long() ?: 0L
            ln += lng[i].long() ?: 0L
            LatLng(la / 1e7, ln / 1e7)
        }
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
    // Google prefixes lane steps with "Use the right 2 lanes to …" / "Use any lane
    // to …". Pull that clause out as a separate hint and leave a clean instruction.
    private val LANE_PHRASE = Regex("^(Use (?:the .+? lanes?|any lane)) to ", RegexOption.IGNORE_CASE)

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
        val lane = LANE_PHRASE.find(text)
        val laneHint = lane?.groupValues?.get(1)?.trim()
        val instruction = (lane?.let { text.removeRange(it.range) } ?: text)
            .trim().replaceFirstChar { it.uppercase() }
        return Maneuver(type, instruction.ifEmpty { "Continue" }, LatLng(0.0, 0.0), meters, 0.0, laneHint = laneHint)
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

    /** Position each maneuver at its *actual* cumulative step distance along the
     *  route line. Using the polyline's own length as the denominator (not the
     *  summed step distances) matters: Google's step `meters` and its geometry
     *  length differ by a few percent, and dividing by the step-sum stretched every
     *  mid-route turn off its real spot — so tapping a step landed near, but not on,
     *  the actual turn. Matching the polyline length pins each turn where it is. */
    private fun placeManeuvers(maneuvers: List<Maneuver>, polyline: List<LatLng>): List<Maneuver> {
        if (maneuvers.isEmpty() || polyline.size < 2) return maneuvers
        val polyLength = (0 until polyline.size - 1)
            .sumOf { polyline[it].distanceTo(polyline[it + 1]) }
            .coerceAtLeast(1.0)
        var cum = 0.0
        return maneuvers.map { m ->
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, (cum / polyLength).coerceIn(0.0, 1.0)))
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

}
