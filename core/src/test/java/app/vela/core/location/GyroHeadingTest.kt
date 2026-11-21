package app.vela.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GyroHeadingTest {
    private val dt = 1.0 / 60

    /** A 90 degree right turn at 30 deg/s starting at [carT] s; the route's own bearing at the
     *  arrow makes the same turn starting at [refT]. Returns (t, heading) samples over 10 s. */
    private fun turn(carT: Double, refT: Double, gyro: Boolean = true): List<Pair<Double, Double>> {
        val g = GyroHeading()
        val out = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        while (t < 10.0) {
            val car = ((t - carT) * 30.0).coerceIn(0.0, 90.0)
            val rate = if (t >= carT && car < 90.0) 30.0 else 0.0
            val ref = ((t - refT) * 30.0).coerceIn(0.0, 90.0)
            out += t to g.update(dt, if (gyro) rate else null, ref, 8.0)
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
        assertTrue("arrow 1 s into the turn: $oneSecondIn", oneSecondIn > 25.0)
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

    @Test fun `it never strays far from the road`() {
        val g = GyroHeading()
        repeat(600) { g.update(dt, 120.0, 0.0, 10.0) }
        assertTrue(abs(GyroHeading.delta(g.heading, 0.0)) <= GyroHeading.MAX_OFF_DEG + 0.01)
    }
}
