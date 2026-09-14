package app.vela.core.nav

/**
 * Deciding WHEN to say you are over the posted limit (issue #404). Opt-in, off by default.
 *
 * Pure timing, so the annoying cases are testable: a GPS blip over the limit must not speak,
 * a long stretch over it must speak once and not nag, and slowing down then speeding up again
 * must speak again. The tolerance matches the speed widget's red state (5 km/h, about 3 mph)
 * so the voice never contradicts the badge.
 */
class SpeedingAlerts {
    /** km/h over the posted limit before it counts as speeding (the widget's own threshold). */
    var toleranceKmh = 5.0

    /** How long you must stay over the limit before it speaks: filters GPS speed noise and the
     *  second it takes to ease off after a downhill or a passing move. */
    var holdMs = 4_000L

    /** How long you must stay back under the limit before the next exceedance speaks again. */
    var rearmMs = 8_000L

    /** Never more often than this while over the limit, whatever the re-arm state does. */
    var minGapMs = 45_000L

    private var overSinceMs = NONE
    private var underSinceMs = NONE
    private var armed = true
    private var lastSpokenMs = Long.MIN_VALUE / 2

    /** Feed each fix; true when the alert should be spoken now. A null speed or limit resets
     *  the hold (nothing to compare) without touching the arm state. */
    fun update(speedKmh: Double?, limitKmh: Double?, nowMs: Long): Boolean {
        if (speedKmh == null || limitKmh == null || limitKmh <= 0.0) {
            overSinceMs = NONE
            return false
        }
        val over = speedKmh > limitKmh + toleranceKmh
        if (!over) {
            overSinceMs = NONE
            if (underSinceMs == NONE) underSinceMs = nowMs
            if (nowMs - underSinceMs >= rearmMs) armed = true
            return false
        }
        underSinceMs = NONE
        if (overSinceMs == NONE) overSinceMs = nowMs
        if (!armed || nowMs - overSinceMs < holdMs || nowMs - lastSpokenMs < minGapMs) return false
        armed = false
        lastSpokenMs = nowMs
        return true
    }

    /** Forget everything (a drive ended or began). */
    fun reset() {
        overSinceMs = NONE
        underSinceMs = NONE
        armed = true
        lastSpokenMs = Long.MIN_VALUE / 2
    }

    private companion object {
        /** "Not started": a clock value no real timestamp uses (0 is a legal elapsedRealtime). */
        const val NONE = -1L
    }
}
