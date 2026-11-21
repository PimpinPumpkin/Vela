package app.vela.core.location

import kotlin.math.abs
import kotlin.math.exp

/**
 * The drive arrow's heading turned by the phone's gyroscope (Settings > Diagnostics, "Turn the
 * arrow with the gyroscope"). The road's direction at the arrow, taken with no look-ahead, is the
 * reference; the car's measured rate of turn moves the heading between frames, so the arrow turns
 * when the car turns: never before it, as a heading read off the route ahead did at corners the
 * car took wider than the line, and without the second of lag a heading eased onto the road has.
 * The reference is weak whenever it or the car is turning ([TAU_TURNING_S]).
 *
 * A complementary filter: the gyroscope carries the heading over short spans and the reference
 * pulls it back over longer ones, which also cancels the gyroscope's slow drift. While the car is
 * turning ([TURNING_DPS]) the reference's pull is weak ([TAU_TURNING_S]), since the road ahead
 * bends where the line's corner is, not where the car turns; on a straight it is quick
 * ([TAU_STRAIGHT_S]). Stopped, a phone picked up at a light turns too, so the gyroscope is ignored
 * and the heading follows the reference. It never strays more than [MAX_OFF_DEG] from it.
 *
 * It also stays within the route's envelope: the range of directions the route takes from a little
 * behind the arrow to a little ahead, as signed offsets from the reference, plus [ENV_SLACK_DEG].
 * On a straight the envelope is a few degrees wide, so a phone mount that wobbles cannot turn the
 * arrow; at a corner it opens toward the new road, and the gyroscope times the turn. The yaw rate
 * is low-passed over [RATE_TAU_S] first, which takes the fast part of a wobble out of the camera's
 * lead too ([rate]).
 *
 * A car turning at rate w and speed v pushes sideways at v * w; a phone twisting on its mount
 * turns without that push. So when the horizontal acceleration is known the rate is capped at
 * what it allows ([capDps], from [accelCapDps]): a real turn passes, a wobble is clipped to
 * [ACCEL_MARGIN_DPS]. The cap uses the acceleration's size, not its direction, which would hang on
 * a magnetometer a car's steel bends; braking raises the cap, never lowers it.
 *
 * Bearings in degrees clockwise from north; a positive rate turns clockwise. Pure, no Android.
 */
class GyroHeading {
    /** The heading, NaN until the first [update]. */
    var heading = Double.NaN
        private set

    /** The low-passed yaw rate the last [update] used, deg/s; 0 when stopped or without a reading. */
    var rate = 0.0
        private set

    /** One frame of [dt] seconds: the gyroscope's yaw rate (null when none is arriving), the
     *  reference bearing, the car's speed, and the route's envelope as signed degrees from the
     *  reference ([envLoDeg] at most 0, [envHiDeg] at least 0). Returns the new heading. */
    fun update(
        dt: Double,
        yawRateDps: Double?,
        referenceDeg: Double,
        speedMps: Double,
        envLoDeg: Double = -MAX_OFF_DEG,
        envHiDeg: Double = MAX_OFF_DEG,
        capDps: Double? = null,
    ): Double {
        if (heading.isNaN() || dt <= 0.0) {
            if (heading.isNaN()) { heading = norm(referenceDeg); lastReference = referenceDeg }
            return heading
        }
        val moving = speedMps >= MIN_SPEED_MPS
        val raw = if (!moving) 0.0 else (yawRateDps ?: 0.0).let { r -> if (capDps == null) r else r.coerceIn(-capDps, capDps) }
        rate += (raw - rate) * (1.0 - exp(-dt / RATE_TAU_S))
        // The reference turning while the car does not is the road's corner coming up, not the
        // car: its pull stays weak then too, or the arrow would follow it in early.
        val refRate = if (lastReference.isNaN()) 0.0 else delta(lastReference, referenceDeg) / dt
        lastReference = referenceDeg
        var h = heading + rate * dt
        val tau = when {
            !moving || yawRateDps == null -> TAU_STOPPED_S
            abs(rate) >= TURNING_DPS || abs(refRate) >= TURNING_DPS -> TAU_TURNING_S
            else -> TAU_STRAIGHT_S
        }
        val off = delta(h, referenceDeg)
        h += off * (1.0 - exp(-dt / tau))
        val rel = delta(referenceDeg, h)
        val clamped = rel.coerceIn(lowEdge(envLoDeg), highEdge(envHiDeg))
        if (clamped != rel) h = referenceDeg + clamped
        heading = norm(h)
        return heading
    }

    fun reset() { heading = Double.NaN; lastReference = Double.NaN; rate = 0.0 }

    /** Start from [deg] (the heading already drawn), so switching to this mode does not jump. */
    fun seed(deg: Double) { heading = norm(deg); lastReference = Double.NaN }

    private var lastReference = Double.NaN

    companion object {
        /** Under this the car is stopped or creeping and the gyroscope is not trusted. */
        const val MIN_SPEED_MPS = 1.5
        /** A yaw rate past this is a turn. */
        const val TURNING_DPS = 6.0
        const val TAU_TURNING_S = 3.0
        const val TAU_STRAIGHT_S = 1.0
        const val TAU_STOPPED_S = 0.4
        const val MAX_OFF_DEG = 75.0
        /** Room past the route's envelope, for a road's own wiggle between samples. */
        const val ENV_SLACK_DEG = 2.5
        /** The yaw rate's low-pass: a car's turn builds over a second or more, a mount's wobble
         *  is a few cycles a second. */
        const val RATE_TAU_S = 0.12

        /** Room over the rate the acceleration allows: the acceleration is low-passed and lags the
         *  start of a turn a little, and a road's bumps add to it. */
        const val ACCEL_MARGIN_DPS = 6.0

        /** The most the car can be turning, deg/s, given [horizAccelMps2], the size of the phone's
         *  horizontal acceleration less gravity, at [speedMps]: v * w = a, plus the margin. */
        fun accelCapDps(horizAccelMps2: Double, speedMps: Double): Double =
            Math.toDegrees(horizAccelMps2 / speedMps.coerceAtLeast(3.0)) + ACCEL_MARGIN_DPS

        /** The envelope's edges with the slack, never past [MAX_OFF_DEG]. */
        fun lowEdge(envLoDeg: Double): Double = (minOf(envLoDeg, 0.0) - ENV_SLACK_DEG).coerceAtLeast(-MAX_OFF_DEG)
        fun highEdge(envHiDeg: Double): Double = (maxOf(envHiDeg, 0.0) + ENV_SLACK_DEG).coerceAtMost(MAX_OFF_DEG)

        private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
        /** Shortest signed angle from [from] to [to], -180..180. */
        fun delta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
    }
}
