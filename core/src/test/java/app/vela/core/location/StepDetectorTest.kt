package app.vela.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StepDetectorTest {
    private val g = 9.81

    /** A deterministic jitter in -1..1, so a test never depends on a random seed. */
    private fun jitter(i: Int) = sin(i * 12.9898 + 78.233) * 0.999

    private fun run(seconds: Double, hz: Double = 50.0, signal: (Double, Int) -> Double): Int {
        val d = StepDetector()
        var steps = 0
        var i = 0
        var t = 0.0
        while (t < seconds) {
            // The walk's bounce lands mostly on the axis gravity is on (a phone held in front).
            if (d.sample(t, 0.2 * jitter(i + 7), 0.3 * jitter(i + 3), g + signal(t, i)) != null) steps++
            i++
            t = i / hz
        }
        return steps
    }

    @Test fun aWalkCountsItsStepsAfterTheFirst() {
        // 1.8 steps a second for 10 s, a hand-held bounce of 2.5 m/s² with noise.
        val steps = run(10.0) { t, i -> 2.5 * sin(2 * PI * 1.8 * t) + 0.3 * jitter(i) }
        assertTrue("steps $steps", steps in 16..18)
    }

    @Test fun aSlowWalkCountsToo() {
        val steps = run(10.0) { t, i -> 1.8 * sin(2 * PI * 1.2 * t) + 0.2 * jitter(i) }
        assertTrue("steps $steps", steps in 10..12)
    }

    @Test fun roadVibrationThroughAMountIsNoWalk() {
        val steps = run(20.0) { t, i -> 0.8 * sin(2 * PI * 14.0 * t) + 0.5 * jitter(i) }
        assertEquals(0, steps)
    }

    @Test fun aSingleBumpIsNoStep() {
        val steps = run(10.0) { t, i -> (if (t in 4.0..4.15) 6.0 else 0.0) + 0.1 * jitter(i) }
        assertEquals(0, steps)
    }

    @Test fun aPhoneOnATableTakesNoSteps() {
        assertEquals(0, run(20.0) { _, i -> 0.05 * jitter(i) })
    }
}
