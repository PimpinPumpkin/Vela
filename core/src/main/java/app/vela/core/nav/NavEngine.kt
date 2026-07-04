package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.distanceTo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure turn-by-turn logic: given a [Route], the previous [NavState] and a fresh
 * location, return the next state plus any [NavEvent]s (voice lines, reroute
 * request, arrival). Pure and side-effect free so it unit-tests without Android
 * — the ViewModel feeds it the location stream and performs the events.
 */
object NavEngine {
    private const val OFF_ROUTE_M = 45.0
    private const val OFF_ROUTE_HITS = 4      // debounce GPS jitter before rerouting
    private const val ARRIVE_RADIUS_M = 25.0
    private const val ON_ROUTE_M = 60.0       // within this of the windowed route → keep tracking progress
    private const val STOP_ON_ROUTE_M = 150.0 // a waypoint farther than this from the line isn't on this route
    private val PROMPT_DISTANCES = listOf(400, 150) // metres before a maneuver

    fun update(route: Route, state: NavState, loc: LatLng, imperial: Boolean = false): Pair<NavState, List<NavEvent>> {
        val events = mutableListOf<NavEvent>()
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty() || state.arrived) return state to events

        val idx = state.stepIndex.coerceIn(0, maneuvers.lastIndex)
        val target = maneuvers[idx]
        // Off-route: distance to the route line, debounced over several fixes.
        val distToPath = minDistanceToPath(loc, route.polyline)
        val offHits = if (distToPath > OFF_ROUTE_M) state.offRouteHits + 1 else 0
        val offRoute = offHits >= OFF_ROUTE_HITS
        if (offRoute && !state.offRoute) events += NavEvent.RerouteNeeded

        // Forward progress along the route (monotonic). Project the fix onto the polyline
        // within a window around how far we'd already travelled — NOT globally — so a route
        // that passes near itself (switchback / cloverleaf / parallel return leg) can't make
        // "remaining" collapse by matching a far leg. Only re-acquire globally when we've
        // clearly left the window (a reroute or a big GPS gap); when genuinely off-route we
        // hold the last progress rather than snapping it to a wrong leg.
        val cum = cumulative(route.polyline)
        val total = cum.lastOrNull() ?: 0.0
        val traveled = if (route.polyline.size < 2) 0.0 else {
            val (wM, wD) = projectAlong(route.polyline, cum, loc, state.traveledM - 60.0, state.traveledM + 600.0)
            if (wD <= ON_ROUTE_M && state.traveledM <= total) maxOf(state.traveledM, wM)
            else {
                val (gM, gD) = projectAlong(route.polyline, cum, loc, 0.0, total)
                if (gD <= ON_ROUTE_M) gM else state.traveledM.coerceIn(0.0, total)
            }
        }

        // Distance to the CURRENT maneuver measured ALONG the route, not crow-flies. Crow-flies
        // fired the prompt + advanced the step whenever the maneuver was geographically near,
        // even if miles ahead along the road — a highway curving back near an exit announced
        // "take the exit" miles early, then skipped the real one. The maneuver sits ON the line,
        // so project it onto the whole route and subtract how far we've travelled.
        val dtn = if (route.polyline.size < 2) loc.distanceTo(target.location)
            else (projectAlong(route.polyline, cum, target.location, 0.0, total).first - traveled).coerceAtLeast(0.0)

        var stepIndex = idx
        var spoken = state.spoken

        // The DEPART ("Head out / Head east on …") maneuver sits at the start point (dtn ≈ 0), and it's
        // ALREADY spoken by NavSession.start ("Starting navigation. Head east on F St"). Announcing it
        // here again — the approach prompts AND the interrupt on arrival — is what cut the start prompt
        // off with "a similar direction". Skip it entirely and advance silently to the first real turn,
        // which announces itself as you approach it (Google does the same).
        val isDepart = target.type == ManeuverType.DEPART
        // "Continue onto the road you're ALREADY on" is redundant to SAY — you don't do anything at it —
        // and Google stays silent on these (OSRM emits a step at every road-name change / slight bend). So
        // suppress the VOICE for a CONTINUE whose road is unchanged from the current step; a real road-NAME
        // change ("new name") still gets announced. The step stays on the map + in the step list either way.
        val prevRoad = if (idx > 0) maneuvers[idx - 1].road else null
        val redundantContinue = target.type == ManeuverType.CONTINUE && (target.road == null || target.road == prevRoad)
        val voiceSilent = isDepart || redundantContinue
        if (target.type != ManeuverType.ARRIVE && !voiceSilent) {
            // Lane guidance from OSRM's per-lane data (same info as the banner arrows) — spoken at the FAR
            // prompt only, Google-style as a PREFACE ("…use the right 2 lanes to take exit 172 toward
            // Sacramento"), so the lanes come BEFORE the maneuver, not tacked on after it.
            val lane = app.vela.core.model.laneGuidance(target.lanes)
            for (p in PROMPT_DISTANCES) {
                if (dtn <= p && p !in spoken) {
                    spoken = spoken + p
                    val instruction = if (p == PROMPT_DISTANCES.first() && lane != null)
                        nav().useLanesToDo(lane.side, lane.count, target.instruction)
                    else target.instruction
                    val say = nav().inThen(spokenDistance(p.toDouble(), imperial), instruction)
                    events += NavEvent.Speak(say)
                    // A light "get ready" tick at the closest pre-turn prompt, so
                    // bikers/walkers feel the turn coming without looking or hearing.
                    if (p == PROMPT_DISTANCES.last()) events += NavEvent.Haptic(target.type, approaching = true)
                }
            }
        }

        if (dtn <= ARRIVE_RADIUS_M) {
            if (idx < maneuvers.lastIndex) {
                if (!voiceSilent) {
                    events += NavEvent.Speak(target.instruction, interrupt = true)
                    events += NavEvent.Haptic(target.type) // firm, direction-coded buzz at the turn
                }
                stepIndex = idx + 1
                spoken = emptySet()
            } else {
                events += NavEvent.Arrived
                events += NavEvent.Speak(nav().arrived(), interrupt = true)
                return state.copy(
                    arrived = true,
                    distanceToNextManeuver = 0.0,
                    remainingDistance = 0.0,
                    remainingDuration = 0.0,
                ) to events
            }
        }
        val remaining = (total - traveled).coerceAtLeast(0.0)
        val speed = avgSpeed(route)
        val newTarget = maneuvers[stepIndex]
        // Distance to the next turn measured ALONG the road, not crow-flies (which on a long
        // curved step reads wildly wrong) — the maneuver's projection ahead of our progress.
        val distToNext = when {
            newTarget.type == ManeuverType.ARRIVE -> remaining
            route.polyline.size < 2 -> loc.distanceTo(newTarget.location)
            else -> (projectAlong(route.polyline, cum, newTarget.location, traveled, total).first - traveled).coerceAtLeast(0.0)
        }
        val newState = state.copy(
            stepIndex = stepIndex,
            distanceToNextManeuver = distToNext,
            remainingDistance = remaining,
            remainingDuration = remaining / speed,
            offRoute = offRoute,
            offRouteHits = offHits,
            spoken = spoken,
            traveledM = traveled,
        )
        return newState to events
    }

    /** The active language's nav strings (spoken frame + distance + arrival), English by default. */
    private fun nav() = app.vela.core.i18n.NavStringsRegistry.current()

    /** A distance phrased for SPEECH, honouring the imperial/metric preference — now localized via the
     *  active [NavStrings] (English is byte-identical to the old inline logic). */
    private fun spokenDistance(meters: Double, imperial: Boolean): String = nav().spokenDistance(meters, imperial)

    private fun avgSpeed(route: Route): Double {
        val dur = route.durationInTrafficSeconds ?: route.durationSeconds
        return if (dur > 0) (route.distanceMeters / dur).coerceAtLeast(1.0) else 13.4
    }

    private fun minDistanceToPath(p: LatLng, path: List<LatLng>): Double {
        if (path.size < 2) return if (path.isEmpty()) Double.MAX_VALUE else p.distanceTo(path[0])
        var min = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val d = pointToSegmentMeters(p, path[i], path[i + 1])
            if (d < min) min = d
        }
        return min
    }

    /** For each intermediate [stops] waypoint, the metres-along-[route] of its nearest point on the route
     *  line — the "you're passing this stop" mark that drives the per-stop arrival cue — or null when the
     *  stop sits farther than [STOP_ON_ROUTE_M] from the line (not really on this route). Marks are
     *  NON-DECREASING: each stop is projected only onto the route AFTER the previous stop's mark, so an
     *  out-and-back route that passes a later stop's location on the way to an earlier one can't hand the
     *  later stop its first (wrong) pass and fire its cue early. Pure + testable. */
    fun stopMarks(route: Route, stops: List<LatLng>): List<Double?> {
        if (stops.isEmpty() || route.polyline.size < 2) return stops.map { null }
        val cum = cumulative(route.polyline)
        val total = cum.lastOrNull() ?: 0.0
        var from = 0.0
        return stops.map { s ->
            // Nudge the window start past the previous mark: a segment ENDING exactly at `from` still
            // "overlaps" the window, and its projection (before `from`) would win projectAlong's
            // strictly-less tie against the true later pass. Clamp the result for the same reason —
            // ordering is what the cue logic needs; a metre of positional slack is irrelevant at
            // STOP_ARRIVE tolerances.
            val (m, d) = projectAlong(route.polyline, cum, s, from + 0.5, total)
            if (d <= STOP_ON_ROUTE_M) { from = m.coerceAtLeast(from); from } else null
        }
    }

    /** Cumulative geometric length (m) of [path] at each vertex (cum[0] = 0). */
    internal fun cumulative(path: List<LatLng>): DoubleArray {
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + path[i - 1].distanceTo(path[i])
        return cum
    }

    /** Nearest projection of [p] onto [path], searched only among segments overlapping the
     *  along-route window `[loM, hiM]`. Returns (metres-along-route, perpendicular-metres).
     *  Windowing is what stops a route that passes near itself from matching a far leg; a
     *  caller passes the window around the last known progress. Returns the clamped window
     *  start with a huge distance if no segment falls in the window. */
    internal fun projectAlong(path: List<LatLng>, cum: DoubleArray, p: LatLng, loM: Double, hiM: Double): Pair<Double, Double> {
        val total = cum.lastOrNull() ?: 0.0
        var bestD = Double.MAX_VALUE
        var bestAlong = loM.coerceIn(0.0, total)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        for (i in 0 until path.size - 1) {
            if (cum[i + 1] < loM || cum[i] > hiM) continue // segment entirely outside the window
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.lng - p.lng) * mPerDegLng
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestD) {
                bestD = d
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return bestAlong to bestD
    }

    /** Shortest distance (metres) from [p] to segment [a]-[b], local ENU planar. */
    private fun pointToSegmentMeters(p: LatLng, a: LatLng, b: LatLng): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * mPerDegLng
        val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lng - p.lng) * mPerDegLng
        val by = (b.lat - p.lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(ax, ay)
        val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}
