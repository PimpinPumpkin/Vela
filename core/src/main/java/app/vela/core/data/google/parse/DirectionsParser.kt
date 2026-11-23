package app.vela.core.data.google.parse

import app.vela.core.config.Calibration
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.int
import app.vela.core.data.google.long
import app.vela.core.data.google.str
import app.vela.core.model.LatLng
import app.vela.core.model.LegTime
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
 *   legs          `r[1][j]`, one per leg of a trip asked through stops, each with its own summary
 *                 at `[0]` in the route summary's shape (distance, typical, traffic at the same
 *                 indices; calibrated 2026-10-10 on a one-stop Davis fixture).
 *   steps         emitted as `<step maneuver='TURN' meters='120'>Turn <turn side='LEFT'>left
 *                 </turn> onto <roadlist><road>Elm St</road></roadlist></step>` markup strings
 *                 scattered through the route subtree. The maneuver attr is GENERIC ('TURN',
 *                 'ON_RAMP', 'ROUNDABOUT_ENTER_AND_EXIT', 'NAME_CHANGE', …) — the left/right +
 *                 slight/sharp live in the child `<turn side= type=>`, the road in `<road>`. NOTE:
 *                 roundabout steps carry NO `<road>` keyless ("take the 2nd exit", no "onto X").
 *
 * The exact encoded overview-polyline field did not decode cleanly during
 * calibration, so geometry is currently APPROXIMATED from the in-bounds
 * coordinate points present in the route subtree. This is good enough to draw a
 * route and to place maneuvers for ETA/preview; tightening it to the true
 * per-step polyline is the one remaining calibration item (CALIBRATE: geometry).
 */
object DirectionsParser {
    /** The legs' distances may differ from the trip's by this fraction and still be its legs. */
    private const val LEG_TILE_SLACK = 0.03

    /** [key]'s remote path when the bundle carries one, else the compiled [fallback] indices. */
    private fun JsonElement?.atPath(paths: Map<String, List<Int>>, key: String, vararg fallback: Int): JsonElement? {
        val p = paths[key] ?: fallback.toList()
        var n: JsonElement? = this
        for (i in p) { n = n.at(i) }
        return n
    }

    fun parse(root: JsonElement, paths: Map<String, List<Int>> = Calibration.DEFAULT_DIRECTIONS_PATHS): List<Route> {
        val P = paths
        val routes = root.atPath(P, "routes", 0, 1).arr()
            ?: throw CalibrationNeededException("directions routes (root[0][1])")
        // Google ships each route's real geometry as delta-encoded E7 coordinate
        // arrays at root[0][7][i] (index-aligned with the route summaries) — so the
        // drawn line follows the actual roads of *that* route, alternates included.
        val geoms = root.atPath(P, "geometries", 0, 7).arr()
        val parsed = routes.mapIndexedNotNull { i, r ->
            runCatching { parseRoute(r, decodeGeometry(geoms?.getOrNull(i)), P) }.getOrNull()
        }
        if (parsed.isEmpty()) throw CalibrationNeededException("directions: 0 routes parsed")
        return parsed
    }

    private fun parseRoute(route: JsonElement, googleGeometry: List<LatLng>?, P: Map<String, List<Int>>): Route? {
        val summary = route.atPath(P, "summary", 0) ?: return null
        val distance = summary.atPath(P, "distance", 2, 0).dbl() ?: return null
        val typicalDur = summary.atPath(P, "typical", 3, 0).dbl() ?: return null
        val trafficDur = summary.atPath(P, "traffic", 10, 0, 0).dbl() // null when no live traffic (e.g. off-peak)
        // Typical best→worst spread: summary[10][4] = [lowSeconds, highSeconds, "label"].
        // Google's own depart-time planning hint ("usually 1 hr 8 min to 1 hr 27 min"),
        // present on longer trips; absent (null) on short/no-traffic ones.
        val typicalLow = summary.atPath(P, "typicalLow", 10, 4, 0).dbl()
        val typicalHigh = summary.atPath(P, "typicalHigh", 10, 4, 1).dbl()

        val start = coord(summary.atPath(P, "start", 7, 3, 2))
        val end = coord(summary.atPath(P, "end", 7, 3, 3))
        // Google's own geometry when present; otherwise a straight start→end segment
        // (the data source can still snap that to an open router). Never a guess that
        // doubles back on itself.
        val polyline = googleGeometry?.takeIf { it.size >= 2 } ?: listOfNotNull(start, end)
        // A reply with no step list still drives: a start and an arrival, so the drive tracks
        // progress and arrives (the nav engine does nothing on a route with no steps).
        val maneuvers = placeManeuvers(collectSteps(route), polyline).ifEmpty { bareSteps(polyline, distance, typicalDur) }
        // The legs of a trip asked through stops, each timed on its own. Kept only when they tile
        // the trip's distance; a direct trip's single leg says nothing the summary does not.
        val legTimes = route.atPath(P, "legs", 1).arr()?.mapNotNull { leg ->
            val s = leg.atPath(P, "legSummary", 0)
            val d = s.atPath(P, "distance", 2, 0).dbl() ?: return@mapNotNull null
            val t = s.atPath(P, "typical", 3, 0).dbl() ?: return@mapNotNull null
            LegTime(d, t, s.atPath(P, "traffic", 10, 0, 0).dbl())
        }.orEmpty().takeIf { it.size >= 2 && kotlin.math.abs(it.sumOf { l -> l.distanceMeters } - distance) <= distance * LEG_TILE_SLACK }.orEmpty()

        return Route(
            source = app.vela.core.model.RouteSource.GOOGLE_NAMED,
            polyline = polyline.ifEmpty { listOfNotNull(start, end) },
            legs = listOf(RouteLeg(distance, typicalDur, trafficDur, maneuvers)),
            legTimes = legTimes,
            distanceMeters = distance,
            durationSeconds = typicalDur,
            durationInTrafficSeconds = trafficDur,
            summary = summary.atPath(P, "summaryText", 1).str(),
            trafficSpans = parseTrafficSpans(route, P),
            typicalLowSeconds = typicalLow,
            typicalHighSeconds = typicalHigh,
            exitSigns = runCatching { exitSigns(route) }.getOrDefault(emptyList()),
            stepNames = runCatching { stepNames(route) }.getOrDefault(emptyList()),
        )
    }

    /** Per-segment live traffic: `route[3][5][0]` is a list of `[level, startMeters,
     *  lengthMeters]` — only the congested stretches (free-flow gaps are omitted).
     *  Note this hangs off the route node itself, NOT the `[0]` summary. Calibrated
     *  2026-06-19 against Davis→Sac + Berkeley→SF (levels 1=moderate, 2=heavy seen;
     *  span starts+lengths chain contiguously through each jam, sum < route length). */
    private fun parseTrafficSpans(route: JsonElement, P: Map<String, List<Int>>): List<TrafficSpan> {
        val arr = route.atPath(P, "spans", 3, 5, 0).arr() ?: return emptyList()
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

    /** A start at the first point of [poly] covering the whole trip, then the arrival. */
    internal fun bareSteps(poly: List<LatLng>, distance: Double, duration: Double): List<Maneuver> {
        if (poly.size < 2) return emptyList()
        val phrase = app.vela.core.data.RouteGeometry::osrmPhrase
        return listOf(
            Maneuver(ManeuverType.DEPART, phrase("depart", null, null, null, null, null), poly.first(), distance, duration),
            Maneuver(ManeuverType.ARRIVE, phrase("arrive", null, null, null, null, null), poly.last(), 0.0, 0.0),
        )
    }

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

    // The keyless feed carries the turn DIRECTION in a child <turn side='LEFT'|'RIGHT' type='SLIGHT'
    // |'SHARP'> element — NOT in the maneuver attribute, which is the generic 'TURN' / 'ON_RAMP' /
    // 'ROUNDABOUT_ENTER_AND_EXIT'. (Verified against a live www.google.com directions capture.)
    private val SIDE_ATTR = Regex("<turn\\b[^>]*\\bside='([^']+)'", RegexOption.IGNORE_CASE)
    private val SEVERITY_ATTR = Regex("<turn\\b[^>]*\\btype='([^']+)'", RegexOption.IGNORE_CASE)

    internal fun parseStep(s: String): Maneuver {
        val token = MANEUVER_ATTR.find(s)?.groupValues?.get(1)
        val side = SIDE_ATTR.find(s)?.groupValues?.get(1)
        val severity = SEVERITY_ATTR.find(s)?.groupValues?.get(1)
        val type = mapType(token, side, severity)
        val meters = METERS_ATTR.find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val text = s.replace(TAGS, " ").replace(WS, " ").trim()
        val lane = LANE_PHRASE.find(text)
        val laneHint = lane?.groupValues?.get(1)?.trim()
        val instruction = (lane?.let { text.removeRange(it.range) } ?: text)
            .trim().replaceFirstChar { it.uppercase() }
        return Maneuver(type, instruction.ifEmpty { "Continue" }, LatLng(0.0, 0.0), meters, 0.0, laneHint = laneHint)
    }

    /** Map Google's keyless maneuver [token] (+ the child `<turn>` [side]/[severity]) to a
     *  [ManeuverType]. The feed uses a GENERIC token with left/right + slight/sharp carried
     *  separately, so reading only the token left every plain turn and ramp as UNKNOWN — a generic
     *  arrow and the wrong direction-coded haptic. Old explicit *_LEFT/_RIGHT tokens stay handled in
     *  case the feed varies. */
    internal fun mapType(token: String?, side: String?, severity: String?): ManeuverType {
        val left = side.equals("LEFT", ignoreCase = true)
        val right = side.equals("RIGHT", ignoreCase = true)
        val slight = severity.equals("SLIGHT", ignoreCase = true)
        val sharp = severity.equals("SHARP", ignoreCase = true)
        fun lr(l: ManeuverType, r: ManeuverType, fallback: ManeuverType) = if (left) l else if (right) r else fallback
        return when (token?.uppercase()) {
            "DEPART" -> ManeuverType.DEPART
            "DESTINATION", "ARRIVE" -> ManeuverType.ARRIVE
            "TURN_LEFT" -> ManeuverType.TURN_LEFT
            "TURN_RIGHT" -> ManeuverType.TURN_RIGHT
            "TURN_SLIGHT_LEFT" -> ManeuverType.SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT" -> ManeuverType.SLIGHT_RIGHT
            "TURN_SHARP_LEFT" -> ManeuverType.SHARP_LEFT
            "TURN_SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
            "TURN" -> when {
                slight -> lr(ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT, ManeuverType.STRAIGHT)
                sharp -> lr(ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.STRAIGHT)
                else -> lr(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.STRAIGHT)
            }
            "UTURN", "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverType.UTURN
            "STRAIGHT", "CONTINUE", "NAME_CHANGE" -> ManeuverType.STRAIGHT
            "MERGE", "MERGE_LEFT", "MERGE_RIGHT" -> ManeuverType.MERGE
            "FORK", "FORK_LEFT", "FORK_RIGHT" -> lr(ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.STRAIGHT)
            "ON_RAMP", "OFF_RAMP", "RAMP",
            "RAMP_LEFT", "ON_RAMP_LEFT", "OFF_RAMP_LEFT",
            "RAMP_RIGHT", "ON_RAMP_RIGHT", "OFF_RAMP_RIGHT" ->
                lr(ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.MERGE)
            "KEEP", "KEEP_LEFT", "KEEP_RIGHT" -> lr(ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT, ManeuverType.STRAIGHT)
            else -> when {
                token?.contains("ROUNDABOUT") == true -> ManeuverType.ROUNDABOUT
                left || right -> lr(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.UNKNOWN)
                else -> ManeuverType.UNKNOWN
            }
        }
    }

    /** Position each maneuver at its *actual* cumulative step distance along the
     *  route line. Using the polyline's own length as the denominator (not the
     *  summed step distances) matters: Google's step `meters` and its geometry
     *  length differ by a few percent, and dividing by the step-sum stretched every
     *  mid-route turn off its real spot — so tapping a step landed near, but not on,
     *  the actual turn. Matching the polyline length pins each turn where it is. */
    internal fun placeManeuvers(maneuvers: List<Maneuver>, polyline: List<LatLng>): List<Maneuver> {
        if (maneuvers.isEmpty() || polyline.size < 2) return maneuvers
        // Place each turn by its fraction of the STEP-DISTANCE total, NOT the polyline length.
        // The two often disagree (Google's per-step meters vs our decoded geometry can differ by
        // a lot — seen 3.3 km of steps on a 6.4 km polyline), and dividing by polyLength then
        // crammed every turn into the first half of the route, landing them on the wrong roads
        // ("turn onto a road miles away"). Fraction-of-step-total maps each turn to the right
        // PROPORTIONAL point along the geometry regardless of the absolute-length mismatch.
        val stepTotal = maneuvers.sumOf { it.distanceMeters }.coerceAtLeast(1.0)
        var cum = 0.0
        val lastIdx = maneuvers.lastIndex
        return maneuvers.mapIndexed { i, m ->
            // Each turn sits at the START of its step (cumulative distance of the steps BEFORE it),
            // then we add this step's length. The final maneuver (ARRIVE) is pinned to the route
            // end (1.0) so a few-percent step/geometry drift can't undershoot the destination.
            val frac = if (i == lastIdx) 1.0 else (cum / stepTotal).coerceIn(0.0, 1.0)
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, frac))
        }
    }

    // --- exit signs ---------------------------------------------------------

    /**
     * Google's exits on [route], for the steps the map tiles name (`LineNamer`), which know where
     * an exit is but not its number or signs. Every step of the route is in the reply, also the
     * ones the `<step>` markup leaves out (the keyless list is short because only some carry it):
     * `[1]` the markup or "", `[7][2]` = `[_, _, lat, lng]` the maneuver's own point, `[14]` the
     * whole instruction as `[kind, [text, 1?]]` parts, the `1` marking a name ("Take exit ",
     * "4A"*, " to merge onto ", "I-5 N"*, " toward ", "Redding"*). The step sits first in an array
     * whose other parts carry its icon (`dir-tt-ramp-right` on a step leaving a highway).
     * Calibrated 2026-11-14 on Davis, Sacramento, San Francisco, Los Angeles, Boston, Houston,
     * Denver and London trips. The reply is always English (`hl=en`), so the words around the
     * names say what each name is.
     */
    internal fun exitSigns(route: JsonElement): List<app.vela.core.model.ExitSign> =
        ArrayList<app.vela.core.model.ExitSign>().also { out -> forEachStep(route) { st, left -> stepExit(st, left)?.let { out += it } } }

    /**
     * Every step of [route] that names the street it goes onto or the side it turns, at the
     * step's own point, for the turns the map tiles leave unnamed (`LineNamer`). The street is the
     * name after "onto ", "stay on " or "becomes " ("Turn [right] onto [E 8th St]", "[L St] turns
     * [left] and becomes [2nd St]"), the side a name that is "left" or "right". Same parts as
     * [exitSigns], calibrated on the same trips.
     */
    internal fun stepNames(route: JsonElement): List<app.vela.core.model.StepName> =
        ArrayList<app.vela.core.model.StepName>().also { out -> forEachStep(route) { st, _ -> stepName(st)?.let { out += it } } }

    /** Calls [f] with every step of [route] and the side its ramp icon shows (null without one). */
    private fun forEachStep(route: JsonElement, f: (JsonArray, Boolean?) -> Unit) {
        fun isStep(n: JsonElement?): Boolean {
            val a = n.arr() ?: return false
            return a.size > 14 && a[14] is JsonArray && a.at(7, 2, 2).dbl() != null && a.at(7, 2, 3).dbl() != null
        }
        fun walk(n: JsonElement) {
            val a = n as? JsonArray ?: return
            if (a.isNotEmpty() && isStep(a[0])) {
                val icon = a.drop(1).firstNotNullOfOrNull { p -> p.arr()?.firstNotNullOfOrNull { RAMP_ICON.find(it.str().orEmpty()) } }
                f(a[0] as JsonArray, icon?.groupValues?.get(1)?.let { it == "left" })
                return
            }
            if (isStep(a)) { f(a, null); return }
            a.forEach(::walk)
        }
        walk(route)
    }

    /** A step's instruction parts, with names next to each other joined: "Int", "'", "l Airport"
     *  is one sign. */
    private data class StepPart(val text: String, val name: Boolean)
    private fun stepParts(step: JsonArray): List<StepPart> {
        val parts = ArrayList<StepPart>()
        for (f in step[14] as JsonArray) {
            val inner = f.at(1).arr() ?: continue
            val t = inner.getOrNull(0).str() ?: continue
            val name = inner.size > 1
            val last = parts.lastOrNull()
            if (name && last?.name == true) parts[parts.lastIndex] = StepPart(last.text + t, true) else parts += StepPart(t, name)
        }
        return parts
    }

    private fun stepName(step: JsonArray): app.vela.core.model.StepName? {
        val lat = step.at(7, 2, 2).dbl() ?: return null
        val lng = step.at(7, 2, 3).dbl() ?: return null
        val parts = stepParts(step)
        var road: String? = null
        var left: Boolean? = null
        for ((k, p) in parts.withIndex()) {
            if (!p.name) continue
            val t = p.text.trim()
            val before = parts.getOrNull(k - 1)?.takeIf { !it.name }?.text.orEmpty().lowercase()
            when {
                left == null && (t.equals("left", true) || t.equals("right", true)) -> left = t.equals("left", true)
                road == null && t.isNotEmpty() && (before.endsWith("onto ") || before.endsWith("stay on ") || before.endsWith("becomes ")) -> road = t
            }
        }
        if (road == null && left == null) return null
        return app.vela.core.model.StepName(LatLng(lat, lng), road, left)
    }

    private enum class Part { NUMBER, TOWARD, ONTO, FOR, OTHER }
    private val EXIT_WORD = Regex("\\b(exit|junction)\\b", RegexOption.IGNORE_CASE)
    private val RAMP_ICON = Regex("dir-tt-ramp-(left|right)")
    private val ROUTE_NUMBER = Regex("^[A-Z]{1,3}-?[0-9]{1,4}[A-Z]?( [NSEW]| North| South| East| West)?$")

    /** The exit [step] is, or null when it is no exit: its instruction names an exit or a motorway
     *  junction, or its icon is a ramp off the road ([left] the side it shows), and it is no
     *  roundabout's. */
    private fun stepExit(step: JsonArray, left: Boolean?): app.vela.core.model.ExitSign? {
        val lat = step.at(7, 2, 2).dbl() ?: return null
        val lng = step.at(7, 2, 3).dbl() ?: return null
        val parts = stepParts(step)
        val text = parts.joinToString("") { it.text }
        if ("roundabout" in text.lowercase() || !(left != null || EXIT_WORD.containsMatchIn(text))) return null
        // What each name is, from the words just before it; a "/" between two names keeps the
        // first one's kind ("toward Hollywood/I-110").
        var number: String? = null
        val refs = LinkedHashSet<String>(); val places = LinkedHashSet<String>()
        var kind = Part.OTHER
        for ((k, p) in parts.withIndex()) {
            if (!p.name) continue
            val before = parts.getOrNull(k - 1)?.takeIf { !it.name }?.text.orEmpty().lowercase()
            val after = parts.getOrNull(k + 1)?.takeIf { !it.name }?.text.orEmpty().lowercase()
            kind = when {
                before.trim() == "/" -> kind
                before.endsWith("exit ") || before.endsWith("junction ") -> Part.NUMBER
                before.endsWith("toward ") || before.endsWith("signs for ") || before.endsWith("ramp to ") -> Part.TOWARD
                // "Take the Jackson Gap St exit": the exit by its sign.
                before.endsWith("the ") && after.trimStart().startsWith("exit") -> Part.TOWARD
                before.endsWith(" for ") -> Part.FOR
                before.endsWith("onto ") -> Part.ONTO
                else -> Part.OTHER
            }
            val name = p.text.trim()
            when (kind) {
                Part.NUMBER -> if (number == null) number = name
                Part.TOWARD, Part.FOR, Part.ONTO -> if (ROUTE_NUMBER.matches(name)) refs += name else if (kind != Part.ONTO) places += name
                Part.OTHER -> {}
            }
        }
        // A sign that only repeats the exit number says nothing ("exit 147 toward 147/...").
        number?.let { n -> places.remove(n); refs.remove(n) }
        val dest = when {
            refs.isNotEmpty() && places.isNotEmpty() -> refs.joinToString(", ") + ": " + places.joinToString(", ")
            refs.isNotEmpty() -> refs.joinToString(", ")
            places.isNotEmpty() -> places.joinToString(", ")
            else -> null
        }
        if (number == null && dest == null) return null
        return app.vela.core.model.ExitSign(LatLng(lat, lng), number, dest, left)
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
