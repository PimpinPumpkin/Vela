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
    private val filter = PositionFilter(TravelMode.DRIVE)

    /**
     * Where one source's fixes have been landing relative to the dot, as running means: east and
     * north offsets and the mean square distance. Scatter around the dot averages out; an offset
     * that holds its direction means the dot, not the fixes, is wrong ([offset]).
     */
    private class Drift {
        private var e = 0.0
        private var n = 0.0
        private var sq = 0.0
        private var count = 0
        private var held = 0
        fun add(ye: Double, yn: Double) {
            e += (ye - e) * DRIFT_RATE; n += (yn - n) * DRIFT_RATE
            sq += (ye * ye + yn * yn - sq) * DRIFT_RATE
            count++
            val m = hypot(e, n)
            held = if (count >= DRIFT_MIN_FIXES && m > DRIFT_MIN_M && m > DRIFT_RATIO * kotlin.math.sqrt(sq)) held + 1 else 0
        }
        /** The offset in meters once it has held for [DRIFT_HOLD_FIXES] fixes in a row, or null
         *  while the fixes only scatter (a chance run of them one way does not last that long). */
        fun offset(): Double? = if (held >= DRIFT_HOLD_FIXES) hypot(e, n) else null
        fun reset() { e = 0.0; n = 0.0; sq = 0.0; count = 0; held = 0 }
    }
    private val gpsDrift = Drift()
    private val netDrift = Drift()

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

    /** After a gap the dot was carried over ([carryOver]): its circle shows at least this, which
     *  each GPS fix taken lowers to its own accuracy. */
    private var staleFloorM = 0.0

    val position: LatLng? get() = filter.position
    val accuracyM: Double get() = maxOf(filter.accuracyM, staleFloorM)

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
        carryOver(fix, tSec, accuracyM ?: 10.0)
        // Stationary: no step and no speed the GPS can be trusted for. The phone is not going
        // anywhere, so the dot holds and averages the fixes instead of chasing their scatter
        // (indoors they jump tens of meters a second). Walking, steps move it; moving with no steps
        // (a car, a bike, a bag), the trusted speed does, as in guidance.
        val stationary = !walking && (lastGood ?: 0.0) < MOVE_MPS
        filter.positionNoise = if (walking) WALK_POSITION_NOISE else 0.0
        if (stationary) filter.holdStill()
        settleDrift(gpsDrift, fix)
        // Stopped: no step for a while and the GPS says not moving. Without this the position would
        // carry on at the last step's speed until the next fix pulled it back.
        if (walking && tSec - lastStepT > STOP_AFTER_S && (speedMps ?: 0.0) < STOP_MAX_MPS) filter.holdStill()
        if (walking) learn(tSec, accuracyM, speedMps, speedAccMps, bearingDeg, bearingAccDeg)
        // Stationary, the fix is taken as one at rest (speed 0, at rest): it is averaged in, and one
        // far off is never jumped to. Only a held drift ([settleDrift]) moves the dot.
        val out = if (stationary) filter.update(fix, tSec, accuracyM, 0.0, STATIONARY_SPEED_ACC, null, null, still = true)
        else filter.update(fix, tSec, accuracyM, speedMps, speedAccMps.takeIf { trusted }, bearingDeg, bearingAccDeg.takeIf { trusted }, still)
        if (out.accepted && (accuracyM ?: 10.0) <= GOOD_FIX_M) stepMetersSinceFix = 0.0
        if (out.accepted) staleFloorM = minOf(staleFloorM, accuracyM ?: 10.0)
        lastGpsT = tSec
        lastGpsAcc = accuracyM ?: 10.0
        lastGpsSpeed = speedMps
        return out
    }

    /** A Wi-Fi or cell fix: null when it is not taken (the GPS is fresh and better). */
    fun network(fix: LatLng, tSec: Double, accuracyM: Double?): PositionFilter.Out? {
        val acc = accuracyM ?: NETWORK_DEFAULT_ACC_M
        val gpsAge = if (lastGpsT.isNaN()) Double.MAX_VALUE else tSec - lastGpsT
        // While the GPS is fresh it is the reference: a Wi-Fi or cell fix is taken only when the GPS
        // is far worse. Indoors the two often disagree by tens of meters, and taking both pulled the
        // dot back and forth between them.
        if (gpsAge < NETWORK_GPS_FRESH_S && !(lastGpsAcc > 2 * acc && lastGpsAcc > NETWORK_GPS_POOR_M)) return null
        val walking = walkingAt(tSec)
        filter.mode = if (walking) TravelMode.WALK else TravelMode.DRIVE
        val lastGood = goodSpeed?.takeIf { tSec - goodSpeedT <= GOOD_SPEED_HOLD_S }
        carryOver(fix, tSec, acc)
        filter.positionNoise = if (walking) WALK_POSITION_NOISE else 0.0
        val stationary = !walking && (lastGood ?: 0.0) < MOVE_MPS
        if (stationary) filter.holdStill()
        // Nor may a network fix's drift move the dot while the GPS speaks.
        if (gpsAge > NETWORK_DRIFT_AFTER_S) settleDrift(netDrift, fix) else netDrift.reset()
        return if (stationary) filter.update(fix, tSec, acc, 0.0, STATIONARY_SPEED_ACC, null, null, still = true, canReanchor = gpsAge > NETWORK_REANCHOR_AFTER_S)
        else filter.update(fix, tSec, acc, null, null, null, null, still = false, canReanchor = gpsAge > NETWORK_REANCHOR_AFTER_S)
    }

    /**
     * The first fix after a gap long enough that the filter would start over from it (the app in
     * the background, the screen off). When it agrees with where the dot was, inside both their
     * uncertainties at [CARRY_GATE], the phone may well not have moved: the dot carries over and the
     * fix is averaged in as usual, instead of the dot jumping to a Wi-Fi fix 10 m off and then
     * being walked back as the GPS returns. Its circle shows at least that fix's accuracy, and
     * shrinks as GPS fixes better than it are taken. A fix that disagrees starts the dot over from it, as before.
     */
    private fun carryOver(fix: LatLng, tSec: Double, accM: Double) {
        val at = filter.position ?: return
        val last = filter.lastFixT
        if (last.isNaN() || tSec - last <= PositionFilter.RESEED_S) return
        val sFix = accM / ACC_TO_SIGMA
        val sDot = filter.accuracyM / ACC_TO_SIGMA
        val ye = (fix.lng - at.lng) * 111_320.0 * cos(Math.toRadians(at.lat))
        val yn = (fix.lat - at.lat) * 110_540.0
        if (ye * ye + yn * yn > CARRY_GATE * (sFix * sFix + sDot * sDot)) return
        filter.bridge(tSec)
        staleFloorM = maxOf(staleFloorM, accM)
    }

    /** Feed one fix's offset from the dot to its source's [Drift]; when the offset holds, widen the
     *  dot's uncertainty to it so the next fixes move the dot there, then start counting again. */
    private fun settleDrift(drift: Drift, fix: LatLng) {
        val at = filter.position ?: return
        val ye = (fix.lng - at.lng) * 111_320.0 * cos(Math.toRadians(at.lat))
        val yn = (fix.lat - at.lat) * 110_540.0
        drift.add(ye, yn)
        drift.offset()?.let { filter.inflate(it); drift.reset() }
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
        gpsDrift.reset(); netDrift.reset()
        lastStepT = Double.NaN; stepTimes.clear()
        staleFloorM = 0.0
    }

    companion object {
        const val DEFAULT_STEP_M = 0.72
        /** A fix wider than this gives no speed or course the dot takes. */
        const val DOPPLER_MAX_ACC_M = 20.0
        /** How long a trusted speed says what the phone is doing. */
        const val GOOD_SPEED_HOLD_S = 10.0
        /** Under this trusted speed, with no step, the phone is stationary. */
        const val MOVE_MPS = 0.8
        /** Position noise while walking, m²/s, for the turns and stops steps do not show. */
        const val WALK_POSITION_NOISE = 0.3
        /** How fast a source's [Drift] follows its fixes (a running mean over about ten). */
        const val DRIFT_RATE = 0.2
        const val DRIFT_MIN_FIXES = 4
        /** An offset counts as held from this many meters... */
        const val DRIFT_MIN_M = 6.0
        /** ...and when it is this large a part of the fixes' whole spread around the dot... */
        const val DRIFT_RATIO = 0.75
        /** ...for this many fixes in a row. */
        const val DRIFT_HOLD_FIXES = 3
        /** The speed accuracy a stationary fix is given, m/s. */
        const val STATIONARY_SPEED_ACC = 0.3
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
        /** While a GPS fix is this recent, a network fix is skipped unless the GPS is far worse... */
        const val NETWORK_GPS_FRESH_S = 5.0
        /** ...twice as wide as the network fix and wider than this. */
        const val NETWORK_GPS_POOR_M = 25.0
        /** A network fix's drift may move the dot only once the GPS has been quiet this long. */
        const val NETWORK_DRIFT_AFTER_S = 10.0
        /** A network fix may re-anchor the model only once the GPS has been quiet this long. */
        const val NETWORK_REANCHOR_AFTER_S = 30.0
        const val NETWORK_DEFAULT_ACC_M = 500.0
        /** The chi-square cut for two degrees of freedom at 95 percent: a fix after a gap this close
         *  to the dot, against both their uncertainties, agrees with it ([carryOver]). */
        const val CARRY_GATE = 5.99
        /** Android's accuracy is a 68 percent radius, about 1.5 standard deviations. */
        private const val ACC_TO_SIGMA = 1.5
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
