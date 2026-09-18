package app.vela.core.nav

import kotlin.math.roundToInt

/**
 * What a mid-drive stop costs, in minutes, for the in-drive "add this as a stop?" card.
 *
 * The two figures come from different places on purpose: the baseline is the drive's own live
 * remaining time (traffic-aware and already calibrated against the road you are on) and the
 * candidate figure is one fetched route through the tapped place. Comparing them is only honest
 * within a minute or so, so a difference under [MIN_SHOW_S] is not shown at all rather than
 * rendered as "+0 min", and anything past [MAX_PLAUSIBLE_S] is treated as a bad fetch (a route
 * that failed over to a different engine, or a candidate the router could not reach) rather than
 * as a real detour.
 */
object DetourEstimate {
    /** Below this the two figures are not far enough apart to mean anything. */
    const val MIN_SHOW_S = 20.0

    /** Past this the comparison is not a detour, it is a broken fetch. */
    const val MAX_PLAUSIBLE_S = 3 * 60 * 60.0

    /** Minutes the stop adds, or null when there is nothing honest to show. */
    fun minutesAdded(baselineSeconds: Double, viaSeconds: Double): Int? {
        if (baselineSeconds <= 0.0 || viaSeconds <= 0.0) return null
        val delta = viaSeconds - baselineSeconds
        if (delta < MIN_SHOW_S || delta > MAX_PLAUSIBLE_S) return null
        return (delta / 60.0).roundToInt().coerceAtLeast(1)
    }
}
