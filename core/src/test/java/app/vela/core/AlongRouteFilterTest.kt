package app.vela.core

import app.vela.core.location.AlongRouteFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The along-route POSITION filter (issue #251). The puck used to take each snapped fix as its
 * position outright, so along-route GPS noise was distance the puck actually travelled, once a
 * second, all drive. These pin that the noise is averaged down without the estimate going deaf to
 * a real correction.
 */
class AlongRouteFilterTest {

    private val fps = 60
    private val dt = 1.0 / fps

    /** Drive [seconds] at [speed] m/s, one fix a second with Gaussian along-route noise. */
    private fun drive(
        seconds: Int,
        speed: Double = 15.0,
        noise: Double = 3.0,
        accuracy: Float? = 4f,
        seed: Int = 7,
    ): Pair<Double, Double> { // (RMS error of the filtered estimate, RMS error of the raw fixes)
        val rnd = Random(seed)
        val f = AlongRouteFilter()
        var truth = 0.0
        var sumF = 0.0
        var sumR = 0.0
        var n = 0
        f.reseed(0.0, accuracy)
        repeat(seconds * fps) { i ->
            truth += speed * dt
            f.predict(speed * dt, dt)
            if (i % fps == fps - 1) {
                val z = truth + rnd.nextGaussian() * noise
                f.update(z, accuracy)
                sumR += (z - truth) * (z - truth)
                sumF += (f.alongM - truth) * (f.alongM - truth)
                n++
            }
        }
        return Math.sqrt(sumF / n) to Math.sqrt(sumR / n)
    }

    private fun Random.nextGaussian(): Double {
        var u = 0.0
        var v = 0.0
        var s = 0.0
        while (s <= 0.0 || s >= 1.0) {
            u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; s = u * u + v * v
        }
        return u * Math.sqrt(-2.0 * Math.log(s) / s)
    }

    @Test fun `fix noise is averaged down, not driven into the puck`() {
        val (filtered, raw) = drive(seconds = 120)
        // The whole point: the estimate must be substantially steadier than the fixes feeding it.
        assertTrue("filtered $filtered should be well under raw $raw", filtered < raw * 0.6)
    }

    @Test fun `a noisier fix stream is filtered harder`() {
        val clean = drive(seconds = 120, noise = 3.0, accuracy = 4f).first
        val canyon = drive(seconds = 120, noise = 12.0, accuracy = 20f).first
        // Absolute error grows with the noise, but nothing like proportionally: 4x the noise
        // must not be 4x the wobble, because the wider accuracy tells the filter to trust it less.
        assertTrue("canyon $canyon vs clean $clean", canyon < clean * 4.0)
    }

    @Test fun `a wide accuracy moves the estimate less than a tight one`() {
        fun pull(acc: Float): Double {
            val f = AlongRouteFilter()
            f.reseed(100.0, 4f)
            f.predict(0.0, 1.0) // let a second of doubt build
            f.update(120.0, acc) // a fix 20 m ahead
            return f.alongM - 100.0
        }
        val tight = pull(3f)
        val wide = pull(40f)
        assertTrue("tight $tight should out-pull wide $wide", tight > wide * 2)
        assertTrue("even a tight fix should not teleport the estimate", tight < 20.0)
    }

    @Test fun `dead reckoning carries the estimate between fixes`() {
        val f = AlongRouteFilter()
        f.reseed(0.0, 4f)
        repeat(fps) { f.predict(15.0 * dt, dt) }
        assertEquals(15.0, f.alongM, 0.001)
    }

    @Test fun `reseed snaps, because a discontinuity is not noise`() {
        val f = AlongRouteFilter()
        f.reseed(0.0, 4f)
        repeat(10) { f.update(0.0, 4f) } // get confident
        f.reseed(500.0, 4f)
        assertEquals(500.0, f.alongM, 0.0)
    }

    @Test fun `the estimate never goes deaf to a real correction`() {
        val f = AlongRouteFilter()
        f.reseed(0.0, 4f)
        // A long confident stretch, then the truth is genuinely 30 m ahead.
        repeat(300) { f.predict(0.0, 1.0); f.update(0.0, 4f) }
        assertTrue("variance floored", f.variance >= AlongRouteFilter.VAR_FLOOR)
        var moved = 0.0
        repeat(5) { f.predict(0.0, 1.0); f.update(30.0, 4f); moved = f.alongM }
        assertTrue("should have closed most of a real 30 m error in 5 fixes, got $moved", moved > 20.0)
    }

    @Test fun `a steady drive tracks the truth closely`() {
        val f = AlongRouteFilter()
        var truth = 0.0
        f.reseed(0.0, 4f)
        repeat(60 * fps) { i ->
            truth += 20.0 * dt
            f.predict(20.0 * dt, dt)
            if (i % fps == fps - 1) f.update(truth, 4f) // noiseless fixes
        }
        assertTrue("no drift on clean fixes: ${abs(f.alongM - truth)}", abs(f.alongM - truth) < 0.5)
    }

    @Test fun `measurement variance handles a missing or absurd accuracy`() {
        val none = AlongRouteFilter.measurementVariance(null)
        val typical = AlongRouteFilter.measurementVariance(AlongRouteFilter.DEFAULT_ACC_M)
        assertEquals("null must fall back to the typical-GPS default", typical, none, 1e-9)
        // A nonsense 0 m accuracy must not make the fix infinitely trusted.
        assertTrue(AlongRouteFilter.measurementVariance(0f) >= 2.5 * 2.5)
        // ...nor a 5 km one make it infinitely distrusted.
        assertTrue(AlongRouteFilter.measurementVariance(5000f) <= 60.0 * 60.0)
    }

    @Test fun `predict is a no-op before the first seed`() {
        val f = AlongRouteFilter()
        f.predict(100.0, 1.0)
        assertEquals(0.0, f.alongM, 0.0)
        assertTrue(!f.seeded)
    }
}
