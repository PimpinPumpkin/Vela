package app.vela.core.location

/**
 * 1-D Kalman filter over the nav puck's **along-route position** (metres travelled along the
 * navigated polyline), the other half of [SpeedKalman].
 *
 * The puck's SPEED has been filtered since June; its POSITION never was. The snapped fix was
 * written straight into the motion model, so every metre of along-route GPS noise was a metre the
 * puck genuinely had to travel — once a second, for the whole drive. Smoothing downstream of that
 * cannot help: a smoother makes the movement gentler, it does not make it stop happening. This is
 * the missing measurement update (issue #251).
 *
 * The model is deliberately the simple one: the position is dead-reckoned forward at the modelled
 * speed ([predict]) and its variance grows; a fix folds in ([update]) weighted by that variance
 * against the fix's own reported accuracy. A clean 4 m fix pulls most of the way; a 25 m
 * urban-canyon one barely moves the estimate, which is exactly the fix that used to throw the
 * puck a car length down the road and back.
 *
 * The speed is NOT part of the state here — [SpeedKalman] already owns it, tuned and tested, and
 * folding the two into one 2-state filter measured only marginally better than driving this one
 * from that one, for a much larger change. Genuine DISCONTINUITIES (nav start, a re-acquire after
 * an outage, a persistent over-cap jump) are not noise to be averaged down: the caller calls
 * [reseed] and the estimate snaps.
 *
 * Pure math, no Android — unit-tested in `:core`. Units: seconds, metres, m/s.
 */
class AlongRouteFilter {

    /** Filtered metres along the route. Meaningless until the first [reseed]. */
    var alongM = 0.0
        private set

    /** Current estimate variance (m²) — how much doubt the estimate carries. */
    var variance = 0.0
        private set

    var seeded = false
        private set

    /**
     * Dead-reckon [advanceM] metres forward (the modelled speed integrated over this frame) and
     * grow the doubt by [dt] seconds' worth. No-op until seeded.
     */
    fun predict(advanceM: Double, dt: Double) {
        if (!seeded) return
        if (advanceM > 0.0) alongM += advanceM
        if (dt > 0.0) variance += Q * dt
    }

    /** Fold in a fix at [m] metres along the route, reported to [accuracyM] metres. */
    fun update(m: Double, accuracyM: Float?) {
        if (!seeded) return reseed(m, accuracyM)
        val r = measurementVariance(accuracyM)
        val k = variance / (variance + r)
        alongM += k * (m - alongM)
        variance = ((1.0 - k) * variance).coerceAtLeast(VAR_FLOOR)
    }

    /**
     * Snap the estimate to [m] — for a genuine discontinuity (nav start, re-acquire after an
     * outage, a persistent over-cap jump), which must NOT be averaged down like noise.
     */
    fun reseed(m: Double, accuracyM: Float?) {
        alongM = m
        variance = measurementVariance(accuracyM)
        seeded = true
    }

    /** Forget everything (nav ended). */
    fun reset() {
        alongM = 0.0
        variance = 0.0
        seeded = false
    }

    companion object {
        /**
         * Measurement variance (m²) for one fix's along-route position, from its reported accuracy.
         *
         * `Location.getAccuracy` is a 68% horizontal RADIUS; for an isotropic 2-D error that puts
         * the per-axis sigma at about `acc / 1.51`. Snapping to the route throws the LATERAL
         * component away entirely, so what is left to filter is the along-route projection — hence
         * the 0.66 factor rather than the raw radius. Floored at 2.5 m, because no fix deserves
         * more trust than the road geometry it is being snapped onto, and the accuracy itself is
         * clamped so one absurd reading can neither freeze nor blow up the estimate.
         */
        fun measurementVariance(accuracyM: Float?): Double {
            val acc = (accuracyM ?: DEFAULT_ACC_M).toDouble().coerceIn(1.0, 60.0)
            val sigma = (acc * 0.66).coerceAtLeast(2.5)
            return sigma * sigma
        }

        /** Assumed accuracy (m) when a fix reports none — matches `NavEngine.DEFAULT_ACC_M`. */
        const val DEFAULT_ACC_M = 12.0f

        /**
         * Variance growth while dead-reckoning (m² per second). Set from the speed estimate's own
         * uncertainty — [SpeedKalman] settles around ±0.7 m/s, so a second of blind reckoning is
         * worth about 0.5 m² of position doubt. Larger makes each fix pull harder (jumpier);
         * smaller trusts the reckoning further (smoother, but slower to admit it was wrong).
         */
        const val Q = 0.5

        /** Floor on the variance, so the estimate can never get so confident that a genuine
         *  correction is locked out — the same guard [SpeedKalman] keeps on its own. */
        const val VAR_FLOOR = 0.25
    }
}
