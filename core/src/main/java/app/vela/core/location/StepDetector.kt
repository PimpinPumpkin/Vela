package app.vela.core.location

import kotlin.math.sqrt

/**
 * Steps from the accelerometer, for moving the dot between GPS fixes while walking. Android's own
 * step detector needs the physical activity permission; this reads the same signal with none.
 *
 * Each sample's magnitude, gravity included, has its running mean taken off (gravity and a
 * tilted phone) and is smoothed to the band a walk lives in. A step is a peak over [PEAK_MPS2]
 * after a dip under [DIP_MPS2], at least [MIN_GAP_S] after the last one. A step counts only in a
 * rhythm: the one before it at most [MAX_GAP_S] earlier, and its gap within [RHYTHM_RATIO] of
 * the gap before, so a bump, the phone set down or a hand fidgeting while sitting is never a
 * step. The first two steps of a walk are not reported; the third, in rhythm, is. Pure logic, no
 * Android.
 */
class StepDetector {
    /** One step: when it landed, and the time since the step before it. */
    class Step(val tSec: Double, val intervalS: Double)

    private var mean = Double.NaN
    private var smooth = 0.0
    private var lastT = Double.NaN
    private var armed = false
    private var peak = 0.0
    private var peakT = 0.0
    private var lastStepT = Double.NaN
    private var lastGap = Double.NaN
    /** Steps in rhythm so far, the first one of the run not counted. */
    private var inRhythm = 0

    /** Feed one accelerometer sample (m/s², device axes) at [tSec]; returns a step when one lands. */
    fun sample(tSec: Double, ax: Double, ay: Double, az: Double): Step? {
        val m = sqrt(ax * ax + ay * ay + az * az)
        if (lastT.isNaN() || tSec - lastT > 1.0 || tSec <= lastT) {
            mean = m; smooth = 0.0; lastT = tSec; armed = false; peak = 0.0
            return null
        }
        val dt = tSec - lastT
        lastT = tSec
        mean += (m - mean) * (dt / (MEAN_TAU_S + dt))
        smooth += ((m - mean) - smooth) * (dt / (SMOOTH_TAU_S + dt))
        val a = smooth
        if (!armed) {
            if (a < DIP_MPS2) { armed = true; peak = 0.0 }
            return null
        }
        if (a > peak) { peak = a; peakT = tSec }
        // The peak is over once the signal falls back under half of it.
        if (peak > PEAK_MPS2 && a < peak / 2) {
            armed = false
            val t = peakT
            peak = 0.0
            val gap = if (lastStepT.isNaN()) Double.NaN else t - lastStepT
            if (!gap.isNaN() && gap < MIN_GAP_S) return null
            lastStepT = t
            val rhythmic = !gap.isNaN() && gap <= MAX_GAP_S &&
                (lastGap.isNaN() || gap / lastGap in (1.0 / RHYTHM_RATIO)..RHYTHM_RATIO)
            inRhythm = if (rhythmic) inRhythm + 1 else 0
            lastGap = if (!gap.isNaN() && gap <= MAX_GAP_S) gap else Double.NaN
            return if (inRhythm >= 2) Step(t, gap) else null
        }
        return null
    }

    fun reset() {
        mean = Double.NaN; smooth = 0.0; lastT = Double.NaN; armed = false; peak = 0.0; lastStepT = Double.NaN
        lastGap = Double.NaN; inRhythm = 0
    }

    companion object {
        /** A step's peak, m/s² over the running mean. A walk with the phone in hand peaks at 1.5 to
         *  4; road vibration through a mount stays under 1 once smoothed. */
        const val PEAK_MPS2 = 1.2
        const val DIP_MPS2 = -0.6
        /** Steps closer than this are one step (a sprint is about 0.25 s a step). */
        const val MIN_GAP_S = 0.28
        /** A step more than this after the last one starts a new walk instead of continuing one. */
        const val MAX_GAP_S = 1.5
        /** A step's gap may differ from the one before by at most this factor either way. */
        const val RHYTHM_RATIO = 1.6
        private const val MEAN_TAU_S = 1.0
        /** Smoothing toward the walk band: a time constant of 40 ms is a cutoff near 4 Hz. */
        private const val SMOOTH_TAU_S = 0.04
    }
}
