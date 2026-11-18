package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FreeFused, Vela's own fused position, the one guidance steers by: a Kalman filter over position and
 * velocity fed by each GPS fix's position, weighted by the accuracy the fix reports, and its
 * Doppler velocity (speed and course), which the receiver measures far more precisely than it
 * measures where it is. The accelerometer's at-rest reading lowers how much the model lets the
 * speed change, but only when the Doppler speed agrees the car is stopped: the accelerometer
 * alone reads "still" while a car creeps at walking pace (2026-11-17 drives: 14 m in one stop).
 *
 * What that buys over the raw fix: a fix that leaps away from where the motion says the car is
 * (a reflection off a building) is not taken, and while the Doppler says stopped and the
 * accelerometer says at rest it is never taken, so a car parked between tall buildings stays put. A fix with nothing against it moves the position at once, so turn prompts do not lag the
 * way a low-pass does. After a gap (a tunnel) the model's uncertainty has grown with the gap and
 * the first fix back is taken. While moving, [MAX_REJECTED] fixes in a row that all disagree
 * re-anchor on the last, so a model that is wrong cannot hold out against the GPS for long.
 *
 * The speed is precise along the course and the course is not: a Doppler velocity's noise is
 * the speed's accuracy along the course and speed times the course's accuracy across it, so the
 * state is east, north and both velocities with a full 4x4 covariance.
 * Pure math, no Android, unit-tested in `:core`. Units: seconds, meters, m/s.
 */
class PositionFilter(mode: TravelMode = TravelMode.DRIVE) {
    /** The tuning in use: how hard the speed may change and how far one second may carry. The
     *  dot's filter ([DotFix]) switches it between a walker's and a car's as the motion shows. */
    @Volatile var mode: TravelMode = mode
    /** [accepted] false = this fix was not taken: [position] is where the model puts the car. */
    class Out(val position: LatLng, val accepted: Boolean, val sigmaM: Double)

    private var lat = 0.0
    private var lng = 0.0
    private var ve = 0.0
    private var vn = 0.0

    /** Covariance of (east, north, ve, vn), row-major. */
    private val p = DoubleArray(16)
    private var lastT = 0.0
    private var rejected = 0
    private var velRejected = 0
    private var wasStopped = false
    private var seededAt = 0.0

    var seeded = false
        private set

    /** The model's position, or null before the first fix. */
    val position: LatLng? get() = if (seeded) LatLng(lat, lng) else null

    /** Forget everything: the next fix seeds the model. */
    fun reset() {
        seeded = false
        rejected = 0
        velRejected = 0
    }

    /**
     * Take one GPS fix. [tSec] is the fix's time on a monotonic clock. [speedMps] and [bearingDeg]
     * are the fix's own Doppler speed and course (null when the fix has none), [still] the
     * accelerometer's at-rest flag ([Stillness.still]).
     */
    fun update(
        fix: LatLng, tSec: Double, accuracyM: Double?, speedMps: Double?, speedAccMps: Double?,
        bearingDeg: Double?, bearingAccDeg: Double?, still: Boolean, canReanchor: Boolean = true,
    ): Out {
        val acc = (accuracyM ?: DEFAULT_ACC_M).coerceIn(MIN_ACC_M, MAX_ACC_M)
        val rPos = (acc / ACC_TO_SIGMA).let { it * it }
        val dt = tSec - lastT
        if (!seeded || dt > RESEED_S || dt < -1.0) return seed(fix, tSec, rPos, speedMps, speedAccMps, bearingDeg, bearingAccDeg, acc)
        if (dt <= 0.0) return Out(LatLng(lat, lng), false, sigma())
        lastT = tSec

        val stopped = speedMps != null && speedMps < REST_MPS
        // Stopped at both ends of the interval: the car did not move in it. The fix that first
        // reads 0 closes the second the car braked through, which gets the moving model.
        val stoppedThrough = stopped && wasStopped
        val stopOnset = stopped && !wasStopped
        wasStopped = stopped
        val zupt = stoppedThrough && still
        if (zupt) {
            // Stopped by the Doppler and at rest by the accelerometer: the velocity is zero. Set it
            // so before predicting (a zero-velocity update), without the usual pull on the
            // position, which assumes a velocity error has run for a while.
            ve = 0.0; vn = 0.0
            for (i in 0..3) for (j in 2..3) { p[i * 4 + j] = 0.0; p[j * 4 + i] = 0.0 }
            p[10] = ZUPT_SIGMA * ZUPT_SIGMA; p[15] = ZUPT_SIGMA * ZUPT_SIGMA
            velRejected = 0
        }
        predict(dt, when {
            zupt -> Q_REST
            stoppedThrough -> Q_STOPPED
            else -> qMoving()
        })

        if (!zupt) doppler(speedMps, speedAccMps, bearingDeg, bearingAccDeg, acc)?.let { (z, r) ->
            val y = doubleArrayOf(z[0] - ve, z[1] - vn)
            // A change no car makes in [dt] is a Doppler glitch (0 at freeway speed for one fix):
            // not taken, but two in a row are. A hard stop is well inside the bound.
            val bound = maxAccel() * dt + 3 * sqrt((r[0] + r[3]) / 2)
            if (y[0] * y[0] + y[1] * y[1] > bound * bound && ++velRejected < 2) {
                // skipped
            } else {
                velRejected = 0
                correct(2, y, sub(2, r))
            }
        }

        // The car just came to rest: let the fixes place it, as an average of the stop's fixes,
        // instead of creeping from wherever the braking left the model.
        if (stopOnset) {
            if (p[0] < rPos) p[0] = rPos
            if (p[5] < rPos) p[5] = rPos
        }

        // Position: the fix's offset from the prediction, in meters east and north.
        val y = doubleArrayOf((fix.lng - lng) * mPerDegLng(lat), (fix.lat - lat) * M_PER_DEG_LAT)
        val s = sub(0, doubleArrayOf(rPos, 0.0, 0.0, rPos))
        val accepted: Boolean
        if (mahalanobis(y, s) > GATE) {
            // Moving, or in the first [WARMUP_S] after the model started (a bad first fix in a
            // garage must not pin it), [MAX_REJECTED] in a row give way. Stopped by the Doppler and
            // at rest by the accelerometer, the car is not moving and a fix that leaps is the GPS:
            // nothing gives way. Stopped by the Doppler alone, [MAX_REJECTED_STOPPED] do.
            val limit = when {
                !stopped || tSec - seededAt < WARMUP_S -> MAX_REJECTED
                zupt -> Int.MAX_VALUE
                else -> MAX_REJECTED_STOPPED
            }
            if (++rejected > limit && canReanchor) {
                rejected = 0
                lat = fix.lat; lng = fix.lng
                for (i in 0..1) for (j in 0..3) { p[i * 4 + j] = 0.0; p[j * 4 + i] = 0.0 }
                p[0] = rPos; p[5] = rPos
                accepted = true
            } else {
                accepted = false
            }
        } else {
            rejected = 0
            correct(0, y, s)
            accepted = true
        }
        // GPS errors run on from one fix to the next, so the model never claims better than a meter.
        if (p[0] < PPP_FLOOR) p[0] = PPP_FLOOR
        if (p[5] < PPP_FLOOR) p[5] = PPP_FLOOR
        return Out(LatLng(lat, lng), accepted, sigma())
    }

    private fun sigma() = sqrt((p[0] + p[5]) / 2)

    /**
     * One step of a walker at [tSec]: [lengthM] meters taken over [intervalS] seconds toward
     * [headingDeg], whose accuracy is [headingAccDeg]. The model moves to the step's time and takes
     * the step's velocity as a measurement, so the position moves with each step between fixes.
     * With no heading, the step's speed is taken along the model's own course, and while the model
     * has none the step only moves the clock. Null before the first fix.
     */
    fun step(tSec: Double, lengthM: Double, intervalS: Double, headingDeg: Double?, headingAccDeg: Double): Out? {
        if (!seeded) return null
        val dt = tSec - lastT
        if (dt <= 0.0 || dt > RESEED_S) return Out(LatLng(lat, lng), true, sigma())
        lastT = tSec
        wasStopped = false
        predict(dt, qMoving())
        val sp = (lengthM / intervalS.coerceAtLeast(0.25)).coerceIn(0.2, STEP_MAX_MPS)
        val course = headingDeg ?: if (ve * ve + vn * vn > 0.09) Math.toDegrees(kotlin.math.atan2(ve, vn)) else null
        if (course != null) {
            val r = Math.toRadians(course)
            val ue = sin(r); val un = cos(r)
            val along = STEP_SPEED_SIGMA
            val cross = (sp * Math.toRadians(if (headingDeg == null) STEP_COURSE_ACC_DEG else headingAccDeg.coerceAtLeast(5.0))).coerceIn(0.1, 2.0)
            val a2 = along * along; val c2 = cross * cross
            val ree = a2 * ue * ue + c2 * un * un
            val rnn = a2 * un * un + c2 * ue * ue
            val ren = (a2 - c2) * ue * un
            correct(2, doubleArrayOf(sp * ue - ve, sp * un - vn), sub(2, doubleArrayOf(ree, ren, ren, rnn)))
        }
        if (p[0] < PPP_FLOOR) p[0] = PPP_FLOOR
        if (p[5] < PPP_FLOOR) p[5] = PPP_FLOOR
        return Out(LatLng(lat, lng), true, sigma())
    }

    /** The walker stopped (no step for a while and no speed from the GPS): the velocity is zero from
     *  here, so the next fix does not carry the position on past where they stood. */
    fun holdStill() {
        if (!seeded) return
        ve = 0.0; vn = 0.0
        for (i in 0..3) for (j in 2..3) { p[i * 4 + j] = 0.0; p[j * 4 + i] = 0.0 }
        p[10] = HOLD_SIGMA * HOLD_SIGMA; p[15] = HOLD_SIGMA * HOLD_SIGMA
    }

    /** Widen the position's uncertainty to at least [sigmaM] (a walk on steps alone drifts with
     *  the distance walked, which the per-step noise does not capture). */
    fun inflate(sigmaM: Double) {
        val v = sigmaM * sigmaM
        if (p[0] < v) p[0] = v
        if (p[5] < v) p[5] = v
    }

    /** The model's speed, meters per second. */
    val speedMps: Double get() = sqrt(ve * ve + vn * vn)

    /** The model's position accuracy as Android reports one: the radius holding the true position
     *  about 68 percent of the time. */
    val accuracyM: Double get() = sigma() * ACC_TO_SIGMA

    /** The Doppler velocity as a measurement (east, north) with its 2x2 noise, or null. */
    private fun doppler(speedMps: Double?, speedAccMps: Double?, bearingDeg: Double?, bearingAccDeg: Double?, acc: Double): Pair<DoubleArray, DoubleArray>? {
        val sp = speedMps ?: return null
        val along = (speedAccMps ?: DEFAULT_SPEED_ACC).coerceIn(MIN_SPEED_ACC, MAX_SPEED_ACC)
        if (sp < REST_MPS) return doubleArrayOf(0.0, 0.0) to doubleArrayOf(along * along, 0.0, 0.0, along * along)
        // A wide fix under walking-to-crawl speed says nothing about its course (issue #818).
        val b = bearingDeg?.takeIf { acc <= Stillness.ACC_TRUST_M || sp >= Stillness.SLOW_MPS } ?: return null
        val r = Math.toRadians(b)
        val ue = sin(r); val un = cos(r) // along the course
        val cross = (sp * Math.toRadians((bearingAccDeg ?: DEFAULT_BEARING_ACC_DEG).coerceAtLeast(1.0))).coerceIn(MIN_SPEED_ACC, MAX_CROSS_ACC)
        val a2 = along * along; val c2 = cross * cross
        // R = a2 u u' + c2 w w', with w = (un, -ue) across the course.
        val ree = a2 * ue * ue + c2 * un * un
        val rnn = a2 * un * un + c2 * ue * ue
        val ren = (a2 - c2) * ue * un
        return doubleArrayOf(sp * ue, sp * un) to doubleArrayOf(ree, ren, ren, rnn)
    }

    private fun predict(dt: Double, q: Double) {
        lat += vn * dt / M_PER_DEG_LAT
        lng += ve * dt / mPerDegLng(lat)
        // P = F P F' + Q, F = [[I, dt I], [0, I]].
        val f = DoubleArray(16)
        for (i in 0..3) for (j in 0..3) {
            var v = p[i * 4 + j]
            if (i < 2) v += dt * p[(i + 2) * 4 + j]
            f[i * 4 + j] = v
        }
        for (i in 0..3) for (j in 0..3) {
            var v = f[i * 4 + j]
            if (j < 2) v += dt * f[i * 4 + j + 2]
            p[i * 4 + j] = v
        }
        val qpp = q * dt * dt * dt / 3; val qpv = q * dt * dt / 2; val qvv = q * dt
        for (k in 0..1) {
            p[k * 4 + k] += qpp
            p[k * 4 + k + 2] += qpv
            p[(k + 2) * 4 + k] += qpv
            p[(k + 2) * 4 + k + 2] += qvv
        }
    }

    /** S = H P H' + R for the 2x2 block starting at state index [o] (0 position, 2 velocity). */
    private fun sub(o: Int, r: DoubleArray) = doubleArrayOf(
        p[o * 4 + o] + r[0], p[o * 4 + o + 1] + r[1],
        p[(o + 1) * 4 + o] + r[2], p[(o + 1) * 4 + o + 1] + r[3],
    )

    private fun mahalanobis(y: DoubleArray, s: DoubleArray): Double {
        val det = s[0] * s[3] - s[1] * s[2]
        if (det <= 0.0) return Double.MAX_VALUE
        return (y[0] * (s[3] * y[0] - s[1] * y[1]) + y[1] * (s[0] * y[1] - s[2] * y[0])) / det
    }

    /** The Kalman correction for a measurement of the 2 states at [o], innovation [y], [s] = H P H' + R. */
    private fun correct(o: Int, y: DoubleArray, s: DoubleArray) {
        val det = s[0] * s[3] - s[1] * s[2]
        if (det <= 0.0) return
        val i00 = s[3] / det; val i01 = -s[1] / det; val i10 = -s[2] / det; val i11 = s[0] / det
        // K = P H' S^-1, 4x2; P H' is columns o and o+1 of P.
        val k = DoubleArray(8)
        for (i in 0..3) {
            val a = p[i * 4 + o]; val b = p[i * 4 + o + 1]
            k[i * 2] = a * i00 + b * i10
            k[i * 2 + 1] = a * i01 + b * i11
        }
        val dx = DoubleArray(4) { k[it * 2] * y[0] + k[it * 2 + 1] * y[1] }
        shift(dx[0], dx[1])
        ve += dx[2]; vn += dx[3]
        // P = P - K (H P), H P = rows o and o+1 of P; then keep it symmetric.
        val hp = DoubleArray(8) { val r = it / 4; val c = it % 4; p[(o + r) * 4 + c] }
        for (i in 0..3) for (j in 0..3) p[i * 4 + j] -= k[i * 2] * hp[j] + k[i * 2 + 1] * hp[4 + j]
        for (i in 0..3) for (j in i + 1..3) {
            val m = (p[i * 4 + j] + p[j * 4 + i]) / 2
            p[i * 4 + j] = m; p[j * 4 + i] = m
        }
    }

    private fun seed(
        fix: LatLng, tSec: Double, rPos: Double, speedMps: Double?, speedAccMps: Double?,
        bearingDeg: Double?, bearingAccDeg: Double?, acc: Double,
    ): Out {
        lat = fix.lat; lng = fix.lng; lastT = tSec; seededAt = tSec
        p.fill(0.0)
        p[0] = rPos; p[5] = rPos
        val d = doppler(speedMps, speedAccMps, bearingDeg, bearingAccDeg, acc)
        if (d != null) {
            ve = d.first[0]; vn = d.first[1]
            p[10] = d.second[0] + 1.0; p[11] = d.second[1]; p[14] = d.second[2]; p[15] = d.second[3] + 1.0
        } else {
            ve = 0.0; vn = 0.0
            p[10] = SEED_VEL_VAR; p[15] = SEED_VEL_VAR
        }
        rejected = 0; velRejected = 0
        wasStopped = speedMps != null && speedMps < REST_MPS
        seeded = true
        return Out(fix, true, sigma())
    }

    private fun shift(east: Double, north: Double) {
        lat += north / M_PER_DEG_LAT
        lng += east / mPerDegLng(lat)
    }

    private fun maxAccel() = when (mode) {
        TravelMode.WALK -> 3.0
        TravelMode.BICYCLE -> 5.0
        else -> MAX_ACCEL_MPS2
    }

    private fun qMoving() = when (mode) {
        TravelMode.WALK -> Q_WALK
        TravelMode.BICYCLE -> Q_BIKE
        else -> Q_DRIVE
    }

    companion object {
        /** Under this Doppler speed the receiver says the car is stopped. */
        const val REST_MPS = 0.5

        /** The chi-square cut for two degrees of freedom at 99.9 percent: a fix this unlikely is not taken. */
        const val GATE = 13.8

        /** Fixes in a row that may disagree, while moving, before the model gives way to the GPS. */
        const val MAX_REJECTED = 2

        /** The same while the Doppler alone says stopped: about 20 seconds of fixes that all disagree. */
        const val MAX_REJECTED_STOPPED = 20

        /** Seconds after the model starts in which it gives way as if moving. */
        const val WARMUP_S = 30.0

        /** The most a car's speed changes in a second (m/s^2), braking or turning hard. */
        const val MAX_ACCEL_MPS2 = 8.0

        /** A gap longer than this (seconds) starts the model over from the next fix. */
        const val RESEED_S = 120.0

        /** Acceleration noise strength (m^2/s^3) by mode, and for a car the accelerometer and the
         *  Doppler both call stopped ([Q_REST]) or the Doppler alone does ([Q_STOPPED]). */
        const val Q_DRIVE = 4.0
        const val Q_BIKE = 1.0
        const val Q_WALK = 0.5
        const val Q_REST = 0.02
        const val Q_STOPPED = 0.2

        /** Android's accuracy is the radius that holds the true position 68 percent of the time,
         *  which for an even spread is about 1.5 standard deviations. */
        private const val ACC_TO_SIGMA = 1.5
        private const val DEFAULT_ACC_M = 10.0
        private const val MIN_ACC_M = 3.0
        private const val MAX_ACC_M = 200.0
        private const val DEFAULT_SPEED_ACC = 0.5
        private const val MIN_SPEED_ACC = 0.2
        private const val MAX_SPEED_ACC = 3.0
        private const val MAX_CROSS_ACC = 6.0
        private const val DEFAULT_BEARING_ACC_DEG = 8.0
        private const val SEED_VEL_VAR = 25.0
        private const val ZUPT_SIGMA = 0.05
        private const val HOLD_SIGMA = 0.1

        /** A step's speed is believed to this many m/s; a walker's step length and timing vary. */
        private const val STEP_SPEED_SIGMA = 0.3

        /** A step taken along the model's own course, with no heading of its own, is believed to
         *  this many degrees across it. */
        private const val STEP_COURSE_ACC_DEG = 25.0

        /** The fastest step taken as walking or jogging, m/s. */
        const val STEP_MAX_MPS = 3.5
        private const val PPP_FLOOR = 1.0
        private const val M_PER_DEG_LAT = 110_540.0
        private fun mPerDegLng(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))
    }
}
