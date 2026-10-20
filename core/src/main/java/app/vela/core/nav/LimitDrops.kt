package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.LimitSpan

/**
 * Warning before the posted limit on the route drops sharply (issue #857): where the drops are,
 * and when to announce one.
 *
 * The limits come as [LimitSpan]s in meters along the route's polyline, either from the offline
 * router's own segments or sampled ahead of the car with the speed badge's lookup ([sample]).
 * Pure, so the cases that would make it nag or lie are tested: an untagged gap is not a drop, a
 * cross street's limit read at a junction is not a drop, and a drop the driver is already under
 * says nothing.
 */
object LimitDrops {

    /** One drop on the route: at [atM] meters along it the limit goes from [fromKmh] to [toKmh]. */
    data class Drop(val atM: Double, val fromKmh: Double, val toKmh: Double) {
        /** Stays the same while the spans behind it are re-read or extended. */
        val key: Long get() = Math.round(atM / 10.0)
    }

    /** Spacing of the samples along the route when the route carries no limits of its own. */
    const val SAMPLE_STEP_M = 25.0

    /** No sample this close to a maneuver: the lookup snaps to the nearest road within 20 to 25 m,
     *  which at a turn is as often the street being left or joined. */
    const val TURN_SKIP_M = 30.0

    /** An unknown stretch shorter than this keeps the limit before it, as the badge does
     *  (`SPEED_LIMIT_FORGET_M`). A longer one starts over: an untagged road is never a drop. */
    const val GAP_BRIDGE_M = 300.0

    /** A limit has to hold this long to count. Shorter stretches are junction snaps, slip roads
     *  and mistagged pieces of way, and are treated as unknown. */
    const val MIN_HOLD_M = 150.0

    /** A drop is measured from the highest limit within this distance before it, so a limit that
     *  steps down in short stages (100, 70, 50) is one drop of 50. */
    const val REF_WINDOW_M = 500.0

    /** Seconds of warning aimed for, floored and capped: about 1 km at 100 km/h, 500 m at 50. */
    const val LEAD_SECONDS = 36.0
    const val MIN_LEAD_M = 300.0
    const val MAX_LEAD_M = 1000.0

    /** km/h over the new limit that still counts as already slow enough: the speed badge's own
     *  tolerance, as in [SpeedingAlerts]. */
    const val SLOW_TOLERANCE_KMH = 5.0

    fun leadM(speedMps: Double): Double = (speedMps * LEAD_SECONDS).coerceIn(MIN_LEAD_M, MAX_LEAD_M)

    /**
     * Read the limits between [fromM] and [toM] along [poly] ([cum] from
     * [RouteProjection.cumulative]) every [SAMPLE_STEP_M], skipping points within [TURN_SKIP_M] of
     * a maneuver ([turnsM], meters along the route, any order). Equal neighbors merge into one
     * span; a skipped or unknown sample ends it. Blocking when [lookup] is.
     */
    fun sample(
        poly: List<LatLng>,
        cum: DoubleArray,
        fromM: Double,
        toM: Double,
        turnsM: List<Double>,
        lookup: (LatLng) -> Double?,
    ): List<LimitSpan> {
        if (poly.size < 2) return emptyList()
        val end = minOf(toM, cum.last())
        val turns = turnsM.sorted()
        val out = ArrayList<LimitSpan>()
        var runStart = 0.0
        var runEnd = 0.0
        var runKmh: Double? = null
        fun close() {
            val k = runKmh ?: return
            out.add(LimitSpan(runStart, runEnd - runStart, k))
            runKmh = null
        }
        var m = maxOf(0.0, fromM)
        while (m <= end) {
            val kmh = if (nearAny(turns, m, TURN_SKIP_M)) null else lookup(RouteProjection.pointAt(poly, cum, m))
            val lo = m - SAMPLE_STEP_M / 2
            val hi = m + SAMPLE_STEP_M / 2
            if (kmh == null) {
                close()
            } else if (runKmh == kmh) {
                runEnd = hi
            } else {
                close()
                runStart = lo; runEnd = hi; runKmh = kmh
            }
            m += SAMPLE_STEP_M
        }
        close()
        return out
    }

    /**
     * The drops of at least [minDropKmh] in [spans] (meters along the route, any order). Spans
     * shorter than [MIN_HOLD_M] are left out first, gaps up to [GAP_BRIDGE_M] keep the limit
     * before them, and each drop is measured from the highest limit in the [REF_WINDOW_M] before
     * it since the last drop.
     */
    fun find(spans: List<LimitSpan>, minDropKmh: Double): List<Drop> {
        val held = merge(spans).filter { it.lengthMeters >= MIN_HOLD_M }
        val out = ArrayList<Drop>()
        // Limits seen since the last reset or drop: (end meters, km/h).
        val recent = ArrayList<Pair<Double, Double>>()
        var lastEnd = Double.NEGATIVE_INFINITY
        for (s in held) {
            if (s.startMeters - lastEnd > GAP_BRIDGE_M) recent.clear()
            val ref = recent.filter { it.first >= s.startMeters - REF_WINDOW_M }.maxOfOrNull { it.second }
            if (ref != null && ref - s.kmh >= minDropKmh) {
                out.add(Drop(s.startMeters, ref, s.kmh))
                recent.clear()
            }
            recent.add(s.startMeters + s.lengthMeters to s.kmh)
            lastEnd = s.startMeters + s.lengthMeters
        }
        return out
    }

    /**
     * The drop to announce now, or null: the nearest one not in [announced] (by [Drop.key]) that
     * lies ahead of [traveledM] within [leadM]. Nothing while crawling or stopped. A drop behind
     * the car is never returned.
     */
    fun due(drops: List<Drop>, traveledM: Double, speedMps: Double, announced: Set<Long>): Drop? {
        if (speedMps < CameraAlerts.MOVING_FLOOR_MPS) return null
        val lead = leadM(speedMps)
        return drops
            .filter { it.key !in announced && it.atM - traveledM > 0.0 && it.atM - traveledM <= lead }
            .minByOrNull { it.atM }
    }

    /** True when a driver at [speedKmh] needs no warning about [drop]: already at its new limit. */
    fun alreadySlow(drop: Drop, speedKmh: Double): Boolean = speedKmh <= drop.toKmh + SLOW_TOLERANCE_KMH

    /** Sorted, with touching or overlapping spans of the same limit joined. */
    internal fun merge(spans: List<LimitSpan>): List<LimitSpan> {
        val out = ArrayList<LimitSpan>()
        for (s in spans.filter { it.lengthMeters > 0.0 }.sortedBy { it.startMeters }) {
            val last = out.lastOrNull()
            val lastEnd = last?.let { it.startMeters + it.lengthMeters }
            if (last != null && lastEnd != null && last.kmh == s.kmh && s.startMeters <= lastEnd + 1.0) {
                out[out.lastIndex] = last.copy(lengthMeters = maxOf(lastEnd, s.startMeters + s.lengthMeters) - last.startMeters)
            } else {
                out.add(s)
            }
        }
        return out
    }

    private fun nearAny(sorted: List<Double>, m: Double, within: Double): Boolean {
        if (sorted.isEmpty()) return false
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < m) lo = mid + 1 else hi = mid
        }
        if (lo < sorted.size && sorted[lo] - m <= within) return true
        return lo > 0 && m - sorted[lo - 1] <= within
    }
}
