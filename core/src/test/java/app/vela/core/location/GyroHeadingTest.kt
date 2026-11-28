package app.vela.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GyroHeadingTest {
    private val dt = 1.0 / 60

    /** A 90 degree right turn at 30 deg/s starting at [carT] s; the route's own bearing at the
     *  arrow makes the same turn starting at [refT]. Returns (t, heading) samples over 10 s. */
    private fun turn(carT: Double, refT: Double, gyro: Boolean = true, envelope: Boolean = false): List<Pair<Double, Double>> {
        val g = GyroHeading()
        val out = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        while (t < 10.0) {
            val car = ((t - carT) * 30.0).coerceIn(0.0, 90.0)
            val rate = if (t >= carT && car < 90.0) 30.0 else 0.0
            val ref = ((t - refT) * 30.0).coerceIn(0.0, 90.0)
            // The route's envelope: the new road's direction once the corner is within 1.5 s ahead,
            // the old one until it is 1 s behind, as offsets from the reference.
            val hi = if (envelope && t < refT - 1.5) 0.0 else 90.0 - ref
            val lo = if (envelope && t > refT + 4.0) 0.0 else -ref
            out += t to if (envelope) g.update(dt, if (gyro) rate else null, ref, 8.0, lo, hi)
                else g.update(dt, if (gyro) rate else null, ref, 8.0)
            t += dt
        }
        return out
    }

    @Test fun `a route corner a second early does not turn the arrow before the car`() {
        val s = turn(carT = 3.0, refT = 2.0)
        val atCarStart = s.first { it.first >= 3.0 }.second
        assertTrue("arrow at the car's start of turn: $atCarStart", atCarStart < 6.0)
    }

    @Test fun `the arrow turns with the car, not after it`() {
        val s = turn(carT = 3.0, refT = 4.0) // the line's corner a second late
        val oneSecondIn = s.first { it.first >= 4.0 }.second
        // The car is at 30; the rate's low-pass (RATE_TAU_S) costs about 4 of it.
        assertTrue("arrow 1 s into the turn: $oneSecondIn", oneSecondIn > 22.0)
    }

    @Test fun `it ends on the road's new direction`() {
        val s = turn(carT = 3.0, refT = 2.0)
        assertEquals(90.0, s.last().second, 2.0)
    }

    @Test fun `gyroscope drift is pulled back on a straight`() {
        val g = GyroHeading()
        repeat(600) { g.update(dt, 0.8, 45.0, 20.0) } // 0.8 deg/s bias for 10 s
        assertTrue(abs(GyroHeading.delta(g.heading, 45.0)) < 2.0)
    }

    @Test fun `stopped, a turning phone does not turn the arrow`() {
        val g = GyroHeading()
        repeat(120) { g.update(dt, 90.0, 10.0, 0.0) } // picked up at a light
        assertEquals(10.0, g.heading, 0.5)
    }

    @Test fun `without a gyroscope reading it follows the road`() {
        val s = turn(carT = 3.0, refT = 3.0, gyro = false)
        assertEquals(90.0, s.last().second, 2.0)
    }

    @Test fun `switched on mid-drive it starts from the drawn arrow`() {
        val g = GyroHeading()
        g.seed(60.0)
        g.update(dt, 0.0, 90.0, 15.0)
        assertTrue(g.heading < 61.0) // one frame on, not a jump to the road
        repeat(300) { g.update(dt, 0.0, 90.0, 15.0) }
        assertEquals(90.0, g.heading, 1.0)
    }

    @Test fun `a wobbling mount on a straight does not turn the arrow`() {
        val g = GyroHeading()
        var t = 0.0
        var worst = 0.0
        while (t < 10.0) {
            // A mount swinging +-9.5 degrees twice a second about its rest, and a faster buzz on top.
            val wobble = 120.0 * kotlin.math.cos(2 * Math.PI * 2.0 * t) + 40.0 * kotlin.math.cos(2 * Math.PI * 7.0 * t)
            worst = maxOf(worst, abs(GyroHeading.delta(0.0, g.update(dt, wobble, 0.0, 15.0, 0.0, 0.0))))
            t += dt
        }
        assertTrue("worst swing on a straight: $worst", worst <= GyroHeading.ENV_SLACK_DEG + 0.01)
    }

    /** The most a mount swinging +-[swingDeg] at [hz] turns the arrow toward a corner whose new
     *  direction is already inside the envelope, over 3 s. */
    private fun cornerWobble(swingDeg: Double, hz: Double): Double {
        val g = GyroHeading()
        var t = 0.0
        var most = 0.0
        while (t < 3.0) {
            val wobble = swingDeg * 2 * Math.PI * hz * kotlin.math.cos(2 * Math.PI * hz * t)
            most = maxOf(most, GyroHeading.delta(0.0, g.update(dt, wobble, 0.0, 10.0, 0.0, 90.0)))
            t += dt
        }
        return most
    }

    @Test fun `a wobbling mount before a corner does not start the turn`() {
        val usual = cornerWobble(4.0, 2.5)
        assertTrue("a +-4 degree wobble turned the arrow $usual toward the corner", usual < 4.0)
        val floppy = cornerWobble(9.5, 2.0) // SPEC 4.7 quotes this one
        assertTrue("a +-9.5 degree wobble turned the arrow $floppy toward the corner", floppy < 8.0)
    }

    @Test fun `inside the route's envelope the real turn still comes through`() {
        val s = turn(carT = 3.0, refT = 4.0, envelope = true)
        val oneSecondIn = s.first { it.first >= 4.0 }.second
        assertTrue("arrow 1 s into the turn: $oneSecondIn", oneSecondIn > 20.0)
        val early = turn(carT = 3.0, refT = 2.0, envelope = true).first { it.first >= 3.0 }.second
        assertTrue("arrow at the car's start of turn: $early", early < 6.0)
        assertEquals(90.0, s.last().second, 2.0)
    }

    @Test fun `with the acceleration known a wobble near a corner is clipped`() {
        val g = GyroHeading()
        var t = 0.0
        var most = 0.0
        while (t < 3.0) {
            val wobble = 9.5 * 2 * Math.PI * 2.0 * kotlin.math.cos(2 * Math.PI * 2.0 * t) // +-9.5 degrees
            // No turn, so only road noise sideways: 0.5 m/s2 at 10 m/s.
            val cap = GyroHeading.accelCapDps(0.5, 10.0)
            most = maxOf(most, GyroHeading.delta(0.0, g.update(dt, wobble, 0.0, 10.0, 0.0, 90.0, cap)))
            t += dt
        }
        assertTrue("a +-9.5 degree wobble turned the arrow $most toward the corner", most < 2.0)
    }

    @Test fun `with the acceleration known a real turn still comes through`() {
        val g = GyroHeading()
        var t = 0.0
        var oneSecondIn = 0.0
        while (t < 6.0) {
            val rate = if (t >= 1.0 && t < 4.0) 30.0 else 0.0
            // The push a 30 deg/s turn at 10 m/s makes, arriving through a 0.2 s low-pass.
            val accel = 10.0 * Math.toRadians(30.0) * (if (t < 1.0) 0.0 else if (t < 4.0) 1 - kotlin.math.exp(-(t - 1.0) / 0.2) else kotlin.math.exp(-(t - 4.0) / 0.2))
            val ref = ((t - 1.5) * 30.0).coerceIn(0.0, 90.0)
            val h = g.update(dt, rate, ref, 10.0, -ref, 90.0 - ref, GyroHeading.accelCapDps(accel, 10.0))
            if (oneSecondIn == 0.0 && t >= 2.0) oneSecondIn = h
            t += dt
        }
        assertTrue("arrow 1 s into the turn: $oneSecondIn", oneSecondIn > 22.0)
        assertEquals(90.0, g.heading, 2.0)
    }

    /** The most the arrow trails a 90 degree right turn at 22.5 deg/s and 6 m/s whose line
     *  bends [refLagS] after the car, with the push the turn makes known through a 0.2 s low-pass. */
    private fun cornerLag(refLagS: Double): Pair<Double, Double> {
        val g = GyroHeading()
        val v = 6.0
        var t = 0.0
        var worst = 0.0
        var push = 0.0
        while (t < 9.0) {
            val car = ((t - 2.0) * 22.5).coerceIn(0.0, 90.0)
            val rate = if (t >= 2.0 && car < 90.0) 22.5 else 0.0
            push += (v * Math.toRadians(rate) - push) * (1 - kotlin.math.exp(-dt / 0.2))
            val ref = ((t - 2.0 - refLagS) * 22.5).coerceIn(0.0, 90.0)
            // The corner enters the envelope 1.5 s before the line bends and leaves it 1 s after.
            val hi = if (t < 2.0 + refLagS - 1.5) 0.0 else 90.0 - ref
            val lo = if (t > 2.0 + refLagS + 5.0) 0.0 else -ref
            val h = g.update(dt, rate, ref, v, lo, hi, GyroHeading.accelCapDps(push, v))
            worst = maxOf(worst, car - h)
            t += dt
        }
        return worst to g.heading
    }

    @Test fun `a line that bends a second after the car does not leave the arrow behind`() {
        val (worst, end) = cornerLag(1.0)
        // The rate's low-pass (RATE_TAU_S) costs about 3 of it at this rate.
        assertTrue("the arrow trailed the car by $worst", worst < 6.0)
        assertEquals(90.0, end, 2.0)
    }

    @Test fun `it never strays far from the road`() {
        val g = GyroHeading()
        repeat(600) { g.update(dt, 120.0, 0.0, 10.0) }
        assertTrue(abs(GyroHeading.delta(g.heading, 0.0)) <= GyroHeading.MAX_OFF_DEG + 0.01)
    }
    /** Out of a right turn the gyroscope under-read: the arrow at 65 while the car heads 90 and the
     *  reference at the arrow still lags at 60, with the road ahead at 90 inside the envelope. */
    private fun outOfTurn(course: Double?, seconds: Double): Double {
        val g = GyroHeading()
        g.seed(65.0)
        var t = 0.0
        while (t < seconds) {
            g.update(dt, 0.0, 60.0, 8.0, envLoDeg = 0.0, envHiDeg = 30.0, capDps = 20.0, courseDeg = course)
            t += dt
        }
        return g.heading
    }

    @Test fun `out of a turn the fix's course finishes it`() {
        assertTrue("with the course: ${outOfTurn(90.0, 1.5)}", outOfTurn(90.0, 1.5) > 85.0)
        assertTrue("without it the arrow waits: ${outOfTurn(null, 1.5)}", outOfTurn(null, 1.5) < 66.0)
    }

    @Test fun `a course the road does not take is ignored`() {
        // Pointing back, or across: outside the envelope.
        assertTrue(outOfTurn(240.0, 1.5) < 66.0)
        assertTrue(outOfTurn(10.0, 1.5) < 66.0)
    }

    @Test fun `the course does not turn the arrow before the car`() {
        // A right turn coming up: the envelope opens, the car still goes straight on (no yaw), its
        // course straight; and mid-turn (yaw above the turning rate) a lagging course is not used.
        val g = GyroHeading(); g.seed(0.0)
        repeat(90) { g.update(dt, 0.0, 0.0, 8.0, envLoDeg = 0.0, envHiDeg = 90.0, capDps = 20.0, courseDeg = 0.0) }
        assertEquals(0.0, g.heading, 1.0)
        val h = GyroHeading(); h.seed(0.0)
        repeat(60) { h.update(dt, 30.0, 0.0, 8.0, envLoDeg = 0.0, envHiDeg = 90.0, capDps = 40.0, courseDeg = 0.0) }
        assertTrue("turned with the gyroscope: ${h.heading}", h.heading > 20.0)
    }
}
