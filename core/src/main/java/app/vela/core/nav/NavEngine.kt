package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.distanceTo
import kotlin.math.cos
import kotlin.math.hypot

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
    private val PROMPT_DISTANCES = listOf(400, 150) // metres before a maneuver

    fun update(route: Route, state: NavState, loc: LatLng): Pair<NavState, List<NavEvent>> {
        val events = mutableListOf<NavEvent>()
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty() || state.arrived) return state to events

        val idx = state.stepIndex.coerceIn(0, maneuvers.lastIndex)
        val target = maneuvers[idx]
        val dtn = loc.distanceTo(target.location)

        // Off-route: distance to the route line, debounced over several fixes.
        val distToPath = minDistanceToPath(loc, route.polyline)
        val offHits = if (distToPath > OFF_ROUTE_M) state.offRouteHits + 1 else 0
        val offRoute = offHits >= OFF_ROUTE_HITS
        if (offRoute && !state.offRoute) events += NavEvent.RerouteNeeded

        var stepIndex = idx
        var spoken = state.spoken

        if (target.type != ManeuverType.ARRIVE) {
            for (p in PROMPT_DISTANCES) {
                if (dtn <= p && p !in spoken) {
                    spoken = spoken + p
                    events += NavEvent.Speak("In $p meters, ${target.instruction}")
                    // A light "get ready" tick at the closest pre-turn prompt, so
                    // bikers/walkers feel the turn coming without looking or hearing.
                    if (p == PROMPT_DISTANCES.last()) events += NavEvent.Haptic(target.type, approaching = true)
                }
            }
        }

        if (dtn <= ARRIVE_RADIUS_M) {
            if (idx < maneuvers.lastIndex) {
                events += NavEvent.Speak(target.instruction, interrupt = true)
                events += NavEvent.Haptic(target.type) // firm, direction-coded buzz at the turn
                stepIndex = idx + 1
                spoken = emptySet()
            } else {
                events += NavEvent.Arrived
                events += NavEvent.Speak("You have arrived", interrupt = true)
                return state.copy(
                    arrived = true,
                    distanceToNextManeuver = 0.0,
                    remainingDistance = 0.0,
                    remainingDuration = 0.0,
                ) to events
            }
        }

        val remaining = remainingAlongPath(loc, route.polyline)
        val speed = avgSpeed(route)
        val newTarget = maneuvers[stepIndex]
        val newState = state.copy(
            stepIndex = stepIndex,
            distanceToNextManeuver = if (stepIndex == idx) dtn else loc.distanceTo(newTarget.location),
            remainingDistance = remaining,
            remainingDuration = remaining / speed,
            offRoute = offRoute,
            offRouteHits = offHits,
            spoken = spoken,
        )
        return newState to events
    }

    /** Average speed implied by the route — prefers the traffic-aware duration. */
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

    private fun remainingAlongPath(p: LatLng, path: List<LatLng>): Double {
        if (path.size < 2) return 0.0
        var nearest = 0
        var min = Double.MAX_VALUE
        for (i in path.indices) {
            val d = p.distanceTo(path[i])
            if (d < min) {
                min = d
                nearest = i
            }
        }
        var sum = p.distanceTo(path[nearest])
        for (j in nearest until path.size - 1) sum += path[j].distanceTo(path[j + 1])
        return sum
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
