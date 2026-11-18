package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * FreeFused for the dot when no drive is on: one [PositionFilter] fed every source the phone has.
 *
 * - GPS fixes, with their Doppler speed and course, as guidance takes them.
 * - Wi-Fi and cell fixes ([network]) when the GPS is quiet or worse than they are, weighted by
 *   their own accuracy: a 1,500 m cell fix barely moves a 40 m estimate, and the first GPS fix
 *   after a network-only spell lands at once. They never re-anchor a model the GPS kept recently.
 * - Steps ([step]), from the accelerometer, taken toward the compass heading: the dot moves with
 *   each step between fixes, and stops when the steps stop instead of carrying on to the next fix.
 *
 * Walking is learned on the way. The step length comes from the GPS speed over the step rate,
 * and the angle between the compass and the way the GPS says the walker goes, so a phone held
 * crooked still steps the right way. A phone whose compass does not follow the walk (in a pocket)
 * steps along the model's own course instead. Steps count only below [STEP_IGNORE_MPS] of GPS
 * speed, so a car's vibration never walks the dot. Pure logic, no Android: the view model feeds
 * it, on the main thread.
 */
class DotFix {
    private val filter = PositionFilter(TravelMode.DRIVE).apply { softGate = true }

    /** The last GPS speed from a fix good enough to trust it, and when. */
    private var goodSpeed: Double? = null
    private var goodSpeedT = Double.NaN
    private var lastGpsT = Double.NaN
    private var lastGpsAcc = Double.NaN
    private var lastGpsSpeed: Double? = null
    private var lastStepT = Double.NaN
    private val stepTimes = ArrayDeque<Double>()

    /** Meters walked on steps since the last good GPS fix: what a walk on steps alone has drifted. */
    private var stepMetersSinceFix = 0.0

    /** Meters per step, learned while walking with a good fix ([learn]). */
    var stepLengthM = DEFAULT_STEP_M
        private set

    // The compass-to-course angle, as a weighted circular mean.
    private var offSin = 0.0
    private var offCos = 0.0
    private var offWeight = 0.0
    private var offSamples = 0

    /** The last compass reading (degrees, screen up), for learning the compass offset at a fix. */
    var compassDeg: Double? = null

    /** Steps landed in the last [WALK_HOLD_S] and the GPS is not saying vehicle speed. */
    fun walkingAt(tSec: Double): Boolean =
        !lastStepT.isNaN() && tSec - lastStepT <= WALK_HOLD_S && (lastGpsSpeed ?: 0.0) < STEP_IGNORE_MPS

    val position: LatLng? get() = filter.position
    val accuracyM: Double get() = filter.accuracyM

    /** A GPS fix. */
    fun gps(
        fix: LatLng, tSec: Double, accuracyM: Double?, speedMps: Double?, speedAccMps: Double?,
        bearingDeg: Double?, bearingAccDeg: Double?, still: Boolean,
    ): PositionFilter.Out {
        val walking = walkingAt(tSec)
        filter.mode = if (walking) TravelMode.WALK else TravelMode.DRIVE
        // A fix this wide (indoors, under trees) has a Doppler speed and course of no use: taken,
        // they read as motion and walk the dot off on its own.
        val trusted = (accuracyM ?: 10.0) <= DOPPLER_MAX_ACC_M
        @Suppress("NAME_SHADOWING") val speedMps = speedMps.takeIf { trusted }
        @Suppress("NAME_SHADOWING") val bearingDeg = bearingDeg.takeIf { trusted }
        if (trusted && speedMps != null) { goodSpeed = speedMps; goodSpeedT = tSec }
        val lastGood = goodSpeed?.takeIf { tSec - goodSpeedT <= GOOD_SPEED_HOLD_S }
        // A walker, someone standing, a phone on a table: let the position follow new fixes
        // instead of trusting where it sat to a meter. A car at speed keeps guidance's tuning.
        filter.positionNoise = if (walking || (lastGood ?: 0.0) < VEHICLE_MPS) ON_FOOT_POSITION_NOISE else 0.0
        // Nothing says it moves (no step, no speed it can trust, no recent vehicle speed): at rest.
        if (!trusted && !walking && (lastGood ?: 0.0) < VEHICLE_MPS) filter.holdStill()
        // Stopped: no step for a while and the GPS says not moving. Without this the position would
        // carry on at the last step's speed until the next fix pulled it back.
        if (walking && tSec - lastStepT > STOP_AFTER_S && (speedMps ?: 0.0) < STOP_MAX_MPS) filter.holdStill()
        if (walking) learn(tSec, accuracyM, speedMps, speedAccMps, bearingDeg, bearingAccDeg)
        val out = filter.update(fix, tSec, accuracyM, speedMps, speedAccMps.takeIf { trusted }, bearingDeg, bearingAccDeg.takeIf { trusted }, still)
        if (out.accepted && (accuracyM ?: 10.0) <= GOOD_FIX_M) stepMetersSinceFix = 0.0
        lastGpsT = tSec
        lastGpsAcc = accuracyM ?: 10.0
        lastGpsSpeed = speedMps
        return out
    }

    /** A Wi-Fi or cell fix: null when it is not taken (the GPS is fresh and better). */
    fun network(fix: LatLng, tSec: Double, accuracyM: Double?): PositionFilter.Out? {
        val acc = accuracyM ?: NETWORK_DEFAULT_ACC_M
        val gpsAge = if (lastGpsT.isNaN()) Double.MAX_VALUE else tSec - lastGpsT
        if (gpsAge < NETWORK_GPS_FRESH_S && lastGpsAcc <= acc) return null
        filter.mode = if (walkingAt(tSec)) TravelMode.WALK else TravelMode.DRIVE
        val lastGood = goodSpeed?.takeIf { tSec - goodSpeedT <= GOOD_SPEED_HOLD_S }
        if ((lastGood ?: 0.0) < VEHICLE_MPS) filter.positionNoise = ON_FOOT_POSITION_NOISE
        return filter.update(fix, tSec, acc, null, null, null, null, still = false, canReanchor = gpsAge > NETWORK_REANCHOR_AFTER_S)
    }

    /** A step that landed at [tSec], [intervalS] after the one before. Null when it is not taken
     *  (vehicle speed, or no position yet). */
    fun step(tSec: Double, intervalS: Double): PositionFilter.Out? {
        val gpsAge = if (lastGpsT.isNaN()) Double.MAX_VALUE else tSec - lastGpsT
        if (gpsAge < VEHICLE_HOLD_S && (lastGpsSpeed ?: 0.0) >= STEP_IGNORE_MPS) return null
        lastStepT = tSec
        stepTimes.addLast(tSec)
        while (stepTimes.isNotEmpty() && tSec - stepTimes.first() > CADENCE_WINDOW_S) stepTimes.removeFirst()
        filter.mode = TravelMode.WALK
        val (heading, acc) = headingForStep() ?: (null to 0.0)
        val out = filter.step(tSec, stepLengthM, intervalS, heading, acc) ?: return null
        // On steps alone the error grows with the distance (a step length or a compass a little
        // off), not with its square root, which is all the per-step noise gives.
        stepMetersSinceFix += stepLengthM
        if (gpsAge > STEP_DRIFT_AFTER_S) filter.inflate(stepMetersSinceFix * STEP_DRIFT_FRACTION)
        return PositionFilter.Out(out.position, true, filter.accuracyM / 1.5)
    }

    /** The heading a step is taken toward, and how far it is believed: the compass plus the learned
     *  offset; the compass alone, loosely, before anything is learned; none when what was learned
     *  says the compass does not follow the walk. */
    private fun headingForStep(): Pair<Double, Double>? {
        val c = compassDeg ?: return null
        if (offSamples < OFFSET_MIN_SAMPLES) return c to UNLEARNED_HEADING_ACC_DEG
        val r = hypot(offSin, offCos) / offWeight.coerceAtLeast(1e-9)
        if (r < OFFSET_MIN_AGREEMENT) return null
        val off = Math.toDegrees(atan2(offSin, offCos))
        return ((c + off + 360.0) % 360.0) to (LEARNED_HEADING_ACC_DEG + (1.0 - r) * 60.0)
    }

    /** Step length and compass offset from a walking fix the GPS is sure of. */
    private fun learn(tSec: Double, accuracyM: Double?, speedMps: Double?, speedAccMps: Double?, bearingDeg: Double?, bearingAccDeg: Double?) {
        val sp = speedMps ?: return
        if ((accuracyM ?: 99.0) > LEARN_MAX_ACC_M || sp !in LEARN_MIN_MPS..LEARN_MAX_MPS) return
        if ((speedAccMps ?: 0.0) > LEARN_MAX_SPEED_ACC) return
        if (stepTimes.size >= 4 && tSec - stepTimes.last() <= 1.0) {
            val span = (stepTimes.last() - stepTimes.first()).coerceAtLeast(0.5)
            val cadence = (stepTimes.size - 1) / span
            if (cadence in 0.8..4.0) {
                stepLengthM = (stepLengthM + (sp / cadence - stepLengthM) * LEARN_RATE).coerceIn(MIN_STEP_M, MAX_STEP_M)
            }
        }
        val c = compassDeg ?: return
        val b = bearingDeg ?: return
        if ((bearingAccDeg ?: 20.0) > LEARN_MAX_BEARING_ACC) return
        val d = Math.toRadians(((b - c + 540.0) % 360.0) - 180.0)
        offSin = offSin * (1 - LEARN_RATE) + sin(d) * LEARN_RATE
        offCos = offCos * (1 - LEARN_RATE) + cos(d) * LEARN_RATE
        offWeight = offWeight * (1 - LEARN_RATE) + LEARN_RATE
        offSamples++
    }

    /** Forget the position (a new session, a replay ended); what was learned about the walker stays. */
    fun reset() {
        filter.reset()
        lastGpsT = Double.NaN; lastGpsAcc = Double.NaN; lastGpsSpeed = null
        goodSpeed = null; goodSpeedT = Double.NaN
        lastStepT = Double.NaN; stepTimes.clear()
    }

    companion object {
        const val DEFAULT_STEP_M = 0.72
        /** A fix wider than this gives no speed or course the dot takes. */
        const val DOPPLER_MAX_ACC_M = 20.0
        /** How long a trusted speed says what the phone is doing. */
        const val GOOD_SPEED_HOLD_S = 10.0
        /** At or above this trusted speed the phone is in a vehicle. */
        const val VEHICLE_MPS = 3.0
        /** Position noise on foot and at rest, m²/s: with a 10 m fix the dot moves about a seventh
         *  of the way to each new one, with a 5 m fix a quarter, the way the old smoothing felt. */
        const val ON_FOOT_POSITION_NOISE = 1.0
        /** A GPS fix this accurate ends a walk on steps alone. */
        const val GOOD_FIX_M = 20.0
        /** Steps with no GPS fix for this long widen the dot's circle with the distance walked... */
        const val STEP_DRIFT_AFTER_S = 3.0
        /** ...by this fraction of it. */
        const val STEP_DRIFT_FRACTION = 0.1
        const val MIN_STEP_M = 0.4
        const val MAX_STEP_M = 1.2
        /** Walking, for the tuning, until this long after the last step. */
        const val WALK_HOLD_S = 2.5
        /** No step for this long and the GPS under [STOP_MAX_MPS]: the walker stopped. */
        const val STOP_AFTER_S = 1.2
        const val STOP_MAX_MPS = 0.6
        /** GPS speed at or above this is a vehicle: steps are vibration and are not taken. */
        const val STEP_IGNORE_MPS = 4.0
        /** ...for this long after such a fix. */
        const val VEHICLE_HOLD_S = 5.0
        /** A network fix is skipped while a GPS fix this recent is at least as accurate. */
        const val NETWORK_GPS_FRESH_S = 3.0
        /** A network fix may re-anchor the model only once the GPS has been quiet this long. */
        const val NETWORK_REANCHOR_AFTER_S = 30.0
        const val NETWORK_DEFAULT_ACC_M = 500.0
        const val CADENCE_WINDOW_S = 4.0
        const val LEARN_MAX_ACC_M = 15.0
        const val LEARN_MIN_MPS = 0.6
        const val LEARN_MAX_MPS = 3.5
        const val LEARN_MAX_SPEED_ACC = 0.6
        const val LEARN_MAX_BEARING_ACC = 25.0
        const val LEARN_RATE = 0.15
        const val OFFSET_MIN_SAMPLES = 4
        /** The compass-to-course angles must agree this well (a resultant of 1 is all the same). */
        const val OFFSET_MIN_AGREEMENT = 0.75
        const val UNLEARNED_HEADING_ACC_DEG = 35.0
        const val LEARNED_HEADING_ACC_DEG = 12.0
    }
}
