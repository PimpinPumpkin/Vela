package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.TransitStep
import app.vela.core.model.distanceTo
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where a rider is on a transit leg (issue #880), for the guidance notification and live timeline:
 * which step and station the rider is currently at by GPS, how many stops are left, and when to
 * warn that the next one is theirs.
 */
object TransitProgress {
    /** With no stop list on the leg, the warning comes this close to the alighting stop. */
    const val ALERT_M = 600.0

    /** Maximum distance (meters) from a step's path/stops to consider the rider on that step. */
    private const val STEP_SNAP_M = 350.0

    /** Maximum distance (meters) from a stop to highlight it as the current station. */
    private const val STOP_SNAP_M = 450.0

    /** The leg's stops in order: boarding, the ones in between, alighting, those with a place. */
    private fun stops(step: TransitStep) =
        (listOfNotNull(step.boardStop) + step.intermediateStops + listOfNotNull(step.alightStop)).filter { it.location != null }

    /** All stops on [step] in order, whether or not each intermediate stop carries coordinates. */
    private fun allStops(step: TransitStep) =
        listOfNotNull(step.boardStop) + step.intermediateStops + listOfNotNull(step.alightStop)

    /** Flat-earth distance in meters from point [p] to segment [a]–[b], plus projection fraction t in 0..1. */
    private fun pointToSegment(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val midLatRad = Math.toRadians((a.lat + b.lat) * 0.5)
        val mPerDegLat = 111_132.0
        val mPerDegLng = 111_320.0 * cos(midLatRad)
        val ax = (a.lng - p.lng) * mPerDegLng
        val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lng - p.lng) * mPerDegLng
        val by = (b.lat - p.lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq < 1e-6) 0.0 else ((-ax * dx - ay * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return sqrt(projX * projX + projY * projY) to t
    }

    /** Minimum distance in meters from [here] to any stop, endpoint, or path segment of [step]. */
    fun minDistanceToStep(step: TransitStep, here: LatLng): Double? {
        val pts = buildList {
            step.path?.let { addAll(it) }
            if (isEmpty()) {
                step.walkFrom?.let { add(it) }
                step.boardStop?.location?.let { add(it) }
                step.intermediateStops.forEach { it.location?.let(::add) }
                step.alightStop?.location?.let { add(it) }
                step.walkTo?.let { add(it) }
            }
        }
        if (pts.isEmpty()) return null
        if (pts.size == 1) return here.distanceTo(pts[0])
        var best = Double.MAX_VALUE
        for (i in 0 until pts.lastIndex) {
            val (d, _) = pointToSegment(here, pts[i], pts[i + 1])
            if (d < best) best = d
        }
        return best
    }

    /**
     * Determine which step index in [steps] the user is currently on based on GPS position [here].
     * Falls back to [fallbackIndex] if [here] is null or far from the entire itinerary.
     */
    fun locateStepIndex(steps: List<TransitStep>, here: LatLng?, fallbackIndex: Int = 0): Int {
        if (steps.isEmpty()) return 0
        val safeFallback = fallbackIndex.coerceIn(0, steps.lastIndex)
        if (here == null) return safeFallback

        var bestIdx = -1
        var bestDist = Double.MAX_VALUE
        for (i in steps.indices) {
            val d = minDistanceToStep(steps[i], here) ?: continue
            // Slight preference (25 m tie-breaker) for the current or subsequent step at transfer points
            val effectiveD = if (i >= safeFallback) d else d + 25.0
            if (effectiveD < bestDist) {
                bestDist = effectiveD
                bestIdx = i
            }
        }
        return if (bestIdx >= 0 && bestDist <= STEP_SNAP_M) bestIdx else safeFallback
    }

    /**
     * Returns `(currentStopName, passedStopNames)` for a transit ride leg [step] given [here].
     * Uses exact stop coordinates when present, or interpolates between boarding and alighting
     * stops if intermediate stops lack coordinates.
     */
    fun activeStopInfo(step: TransitStep, here: LatLng?): Pair<String?, Set<String>> {
        if (step.line == null || here == null) return null to emptySet()
        val ordered = allStops(step)
        if (ordered.isEmpty()) return null to emptySet()

        val withCoords = stops(step)
        if (withCoords.isEmpty()) return null to emptySet()

        // If at least 3 stops have coordinates (or there are no intermediate stops), snap to the nearest stop with coordinates
        if (withCoords.size >= 3 || step.intermediateStops.isEmpty()) {
            val nearest = withCoords.minByOrNull { here.distanceTo(it.location!!) } ?: return null to emptySet()
            if (here.distanceTo(nearest.location!!) > STOP_SNAP_M) return null to emptySet()
            val idxInOrdered = ordered.indexOfFirst { it.name == nearest.name }
            val passed = if (idxInOrdered > 0) ordered.subList(0, idxInOrdered).mapTo(HashSet()) { it.name } else emptySet()
            return nearest.name to passed
        }

        // Fallback when only boardStop and alightStop have coordinates but intermediateStops exist:
        val startLoc = step.boardStop?.location
        val endLoc = step.alightStop?.location
        if (startLoc != null && endLoc != null) {
            val (dist, t) = pointToSegment(here, startLoc, endLoc)
            if (dist <= STOP_SNAP_M) {
                val idx = (t * ordered.lastIndex).roundToInt().coerceIn(0, ordered.lastIndex)
                val current = ordered[idx].name
                val passed = if (idx > 0) ordered.subList(0, idx).mapTo(HashSet()) { it.name } else emptySet()
                return current to passed
            }
        }
        return null to emptySet()
    }

    /**
     * Stops left to ride on [step] from [here]: the stops after the one nearest the phone, the
     * alighting stop included, 0 at it. Without a position, or with no stop list, the leg's own
     * stop count. Null for a walk, or when nothing says.
     */
    fun stopsLeft(step: TransitStep, here: LatLng?): Int? {
        if (step.line == null) return null
        val s = stops(step)
        if (here == null || s.size < 2) return step.numStops
        val nearest = s.indices.minByOrNull { here.distanceTo(s[it].location!!) } ?: return step.numStops
        return s.size - 1 - nearest
    }

    /** Whether to warn "get off at the next stop" on [step] at [here]: one stop left by the stop
     *  list, or, with no stop list, within [ALERT_M] of the alighting stop. */
    fun nextIsYours(step: TransitStep, here: LatLng?): Boolean {
        if (step.line == null || here == null) return false
        if (stops(step).size >= 3) return stopsLeft(step, here) == 1
        val end = step.alightStop?.location ?: return false
        return here.distanceTo(end) <= ALERT_M
    }

    /**
     * Determines which walking sub-step index in [steps] is currently active based on GPS [here].
     * Returns -1 if [steps] is empty, 0 if [here] is null.
     */
    fun activeWalkStepIndex(
        steps: List<app.vela.core.model.TransitWalkStep>,
        here: LatLng?,
        walkFrom: LatLng? = null,
        walkTo: LatLng? = null,
    ): Int {
        if (steps.isEmpty()) return -1
        if (steps.size == 1 || here == null) return 0

        var bestIdx = 0
        var bestDist = Double.MAX_VALUE

        for (i in steps.indices) {
            val start = steps[i].location ?: if (i == 0) walkFrom else null
            val end = if (i < steps.lastIndex) steps[i + 1].location else walkTo

            val d = if (start != null && end != null) {
                pointToSegment(here, start, end).first
            } else if (start != null) {
                here.distanceTo(start)
            } else if (end != null) {
                here.distanceTo(end)
            } else {
                Double.MAX_VALUE
            }

            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }
}
