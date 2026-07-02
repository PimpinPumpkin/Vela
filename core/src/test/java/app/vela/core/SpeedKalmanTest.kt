package app.vela.core

import app.vela.core.location.SpeedKalman
import app.vela.core.location.forwardAccel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nav puck's speed fusion: GPS fixes measure, the accelerometer predicts between them.
 *  The scenarios mirror the bug it fixes — braking/stopping between ~1 Hz fixes, where
 *  last-fix-speed dead reckoning overshoots and monotonic progress can't walk it back. */
class SpeedKalmanTest {

    private fun SpeedKalman.run(accel: Double, seconds: Double, dt: Double = 1.0 / 60): Double {
        var dist = 0.0
        var t = 0.0
        while (t < seconds) {
            predict(accel, dt)
            dist += speed * dt
            t += dt
        }
        return dist
    }

    @Test fun firstFixSeedsTheSpeed() {
        val k = SpeedKalman()
        k.update(15.0)
        assertEquals(15.0, k.speed, 1e-9)
    }

    @Test fun predictIsInertBeforeTheFirstFix() {
        val k = SpeedKalman()
        k.predict(3.0, 1.0)
        assertEquals(0.0, k.speed, 1e-9)
    }

    @Test fun brakingCollapsesThePredictionBetweenFixes() {
        val k = SpeedKalman()
        k.update(15.0)
        // Brake at 4 m/s² for 2 s (a missed-fix window). Modelled speed follows the physics…
        val dist = k.run(accel = -4.0, seconds = 2.0)
        assertEquals(7.0, k.speed, 0.5)
        // …so the reckoned distance is the braking integral (~22 m), NOT the old
        // constant-speed glide (15 m/s × 2 s = 30 m) that overshot a stopping car.
        assertTrue("expected braking integral ~22 m, got $dist", dist in 20.0..24.0)
    }

    @Test fun hardStopClampsAtZeroAndStaysStopped() {
        val k = SpeedKalman()
        k.update(6.0)
        k.run(accel = -4.0, seconds = 3.0) // stopped after 1.5 s
        assertEquals(0.0, k.speed, 1e-9)
        val creep = k.run(accel = -1.0, seconds = 2.0) // still braking-noise → no drift
        assertEquals(0.0, creep, 1e-9)
    }

    @Test fun noAccelerometerHoldsTheOldConstantSpeedBehaviour() {
        val k = SpeedKalman()
        k.update(12.0)
        k.run(accel = 0.0, seconds = 2.0)
        assertEquals(12.0, k.speed, 1e-9)
    }

    @Test fun gpsUpdatesConvergeOntoTheMeasurement() {
        val k = SpeedKalman()
        k.update(15.0)
        // Realistic ~1 Hz fixes with a second of (accel-less) prediction between them — the
        // accel-missing fallback must converge onto a big speed change within a few fixes,
        // since the old code snapped to each fix outright.
        repeat(4) {
            k.run(accel = 0.0, seconds = 1.0)
            k.update(5.0)
        }
        assertEquals(5.0, k.speed, 1.0)
    }

    @Test fun uncertaintyGrownByPredictionMakesTheNextFixPullHarder() {
        val a = SpeedKalman().apply { update(10.0); repeat(4) { update(10.0) } } // confident at 10
        val b = SpeedKalman().apply { update(10.0); repeat(4) { update(10.0) } }
        b.run(accel = -3.0, seconds = 2.0) // an eventful window grows b's variance
        a.update(4.0)
        b.update(4.0)
        assertTrue("post-braking fix should pull harder (${b.speed} vs ${a.speed})", b.speed < a.speed)
    }

    @Test fun accelSpikesAreClamped() {
        val k = SpeedKalman()
        k.update(10.0)
        k.predict(500.0, 0.1) // sensor junk: clamped to 8 m/s² → +0.8, not +50
        assertEquals(10.8, k.speed, 1e-6)
    }

    @Test fun negativeGpsSpeedIsTreatedAsZero() {
        val k = SpeedKalman()
        k.update(-3.0)
        assertEquals(0.0, k.speed, 1e-9)
    }

    @Test fun resetForgetsEverything() {
        val k = SpeedKalman()
        k.update(15.0)
        k.reset()
        k.predict(-4.0, 1.0) // inert again until re-seeded
        assertEquals(0.0, k.speed, 1e-9)
        k.update(3.0)
        assertEquals(3.0, k.speed, 1e-9)
    }

    @Test fun forwardProjectionFollowsTheCompassBearing() {
        assertEquals(2.0, forwardAccel(east = 2.0, north = 0.0, bearingDeg = 90.0), 1e-9)
        assertEquals(3.0, forwardAccel(east = 0.0, north = 3.0, bearingDeg = 0.0), 1e-9)
        assertEquals(-3.0, forwardAccel(east = 0.0, north = 3.0, bearingDeg = 180.0), 1e-9)
        // Heading NE while braking due south-west: full projection is negative.
        assertEquals(-2.0, forwardAccel(east = -1.414213562, north = -1.414213562, bearingDeg = 45.0), 1e-6)
    }
}
