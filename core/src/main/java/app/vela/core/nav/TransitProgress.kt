package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.TransitStep
import app.vela.core.model.distanceTo

/**
 * Where a rider is on a transit leg (issue #880), for the guidance notification: how many stops
 * are left and when to warn that the next one is theirs.
 */
object TransitProgress {
    /** With no stop list on the leg, the warning comes this close to the alighting stop. */
    const val ALERT_M = 600.0

    /** The leg's stops in order: boarding, the ones in between, alighting, those with a place. */
    private fun stops(step: TransitStep) =
        (listOfNotNull(step.boardStop) + step.intermediateStops + listOfNotNull(step.alightStop)).filter { it.location != null }

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
}
