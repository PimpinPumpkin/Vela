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
 * Bearings in degrees clockwise from north; a positive rate turns clockwise. Pure, no Android.
 */
class GyroHeading {
    /** The heading, NaN until the first [update]. */
    var heading = Double.NaN
        private set

    /** One frame of [dt] seconds: the gyroscope's yaw rate (null when none is arriving), the
     *  reference bearing, the car's speed. Returns the new heading. */
    fun update(dt: Double, yawRateDps: Double?, referenceDeg: Double, speedMps: Double): Double {
        if (heading.isNaN() || dt <= 0.0) {
            if (heading.isNaN()) { heading = norm(referenceDeg); lastReference = referenceDeg }
            return heading
        }
        val moving = speedMps >= MIN_SPEED_MPS
        val rate = if (moving) yawRateDps ?: 0.0 else 0.0
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
        val left = delta(h, referenceDeg)
        if (abs(left) > MAX_OFF_DEG) h = referenceDeg - MAX_OFF_DEG * kotlin.math.sign(left)
        heading = norm(h)
        return heading
    }

    fun reset() { heading = Double.NaN; lastReference = Double.NaN }

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

        private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
        /** Shortest signed angle from [from] to [to], -180..180. */
        fun delta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
    }
}
