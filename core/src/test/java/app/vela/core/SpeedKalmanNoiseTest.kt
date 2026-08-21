package app.vela.core

import app.vela.core.location.SpeedKalman
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accelerometer noise must not reach the puck (issue #251, the jitter that survived two other
 * fixes).
 *
 * predict() runs once per FRAME, so ~60 accelerometer samples are integrated between two 1 Hz GPS
 * fixes. Whatever vibration survives into the speed estimate becomes visible puck movement, because
 * the puck advances at that speed. These drive a second of frames the way a real drive does.
 */
class SpeedKalmanNoiseTest {

    private val dt = 1.0 / 60.0

    private fun seeded(at: Double = 20.0) = SpeedKalman().apply { update(at) }

    @Test fun `a second of road vibration does not move the speed`() {
        val k = seeded()
        // Alternating +/-0.3 m/s2: vibration, not driving. It averages to zero, but the point is
        // that it must not wander even transiently - every frame of it moves the drawn puck.
        repeat(60) { i -> k.predict(if (i % 2 == 0) 0.3 else -0.3, dt) }
        assertEquals("vibration must not accumulate", 20.0, k.speed, 0.001)
    }

    @Test fun `a steady vibration BIAS does not accumulate either`() {
        // The nastier case: a mount that rings slightly forward reads as a constant small accel,
        // and integrating that for a second is a phantom 0.3 m/s - which is the shimmer.
        val k = seeded()
        repeat(60) { k.predict(0.3, dt) }
        // Attenuated, not erased - erasing it entirely meant taxing real braking by the same
        // amount (see denoise). A tenth of a m/s over a whole second is well under what shows.
        assertTrue("bias must be strongly attenuated, got ${k.speed - 20.0}", k.speed - 20.0 < 0.12)
    }

    @Test fun `a real brake still slows the puck`() {
        // The whole reason this filter exists: without it the puck sails on at the last fix's speed
        // while the car stops. That must survive the noise rejection.
        val k = seeded(20.0)
        repeat(60) { k.predict(-3.0, dt) }
        assertTrue("a 3 m/s2 brake for 1 s should shed most of 3 m/s, got ${20.0 - k.speed}", 20.0 - k.speed > 2.5)
    }

    @Test fun `hard braking to a stop still reaches zero and never goes negative`() {
        val k = seeded(8.0)
        repeat(120) { k.predict(-6.0, dt) }
        assertEquals(0.0, k.speed, 0.0001)
    }

    @Test fun `the threshold is continuous, so noise cannot chatter across it`() {
        // A hard gate would jump between 0 and the full value as noise crossed the threshold,
        // swapping a shimmer for a chatter. Just above the floor must give just above zero.
        val a = seeded()
        val b = seeded()
        repeat(60) { a.predict(0.36, dt) }
        repeat(60) { b.predict(0.34, dt) }
        assertTrue("just-above-floor must be small, not a step", a.speed - 20.0 < 0.2)
        assertTrue("just-below-floor must be smaller still", b.speed - 20.0 < a.speed - 20.0 + 0.01)
    }

    @Test fun `genuine acceleration still registers`() {
        val k = seeded(5.0)
        repeat(60) { k.predict(2.0, dt) }
        assertTrue("2 m/s2 for 1 s should add most of 2 m/s, got ${k.speed - 5.0}", k.speed - 5.0 > 1.5)
    }
}
