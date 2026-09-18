package app.vela.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The free-drive follow must glide, not surge and stall, on 1 Hz fixes at city speed. */
class FollowEstimatorTest {
    private val mPerDegLng = 111_320.0 * kotlin.math.cos(Math.toRadians(38.55))

    /** Truth: due east at [v] m/s from (38.55, -121.74). Fixes at 1 Hz, optionally lagged the way
     *  the view model's low-pass lags them at low speed; frames at 60 Hz. Returns per-frame
     *  eastward displacement in meters. */
    private fun run(v: Double, lagS: Double, seconds: Int, est: FollowEstimator): List<Double> {
        val out = ArrayList<Double>()
        var lastLng = Double.NaN
        var nowMs = 0L
        for (frame in 0 until seconds * 60) {
            val t = frame / 60.0
            if (frame % 60 == 0) {
                val fixT = (t - lagS).coerceAtLeast(0.0)
                est.onFix(38.55, -121.74 + v * fixT / mPerDegLng, v, 90.0, nowMs)
            }
            est.step(1 / 60.0, nowMs)
            if (!lastLng.isNaN()) out += (est.lng - lastLng) * mPerDegLng
            lastLng = est.lng
            nowMs += 16
        }
        return out.drop(120) // settle
    }

    @Test fun neverStepsBackwardOnLaggedFixes() {
        val d = run(v = 5.0, lagS = 0.8, seconds = 8, est = FollowEstimator())
        assertTrue("min frame step ${d.minOrNull()}", d.all { it >= -0.001 })
    }

    @Test fun keepsMovingBetweenFixes() {
        val d = run(v = 5.0, lagS = 0.0, seconds = 8, est = FollowEstimator())
        val perFrameTrue = 5.0 / 60.0
        assertTrue("slowest frame ${d.minOrNull()}", d.min() > perFrameTrue * 0.6)
        assertEquals(perFrameTrue, d.average(), perFrameTrue * 0.15)
    }

    @Test fun staleFixStopsTheIntegration() {
        val e = FollowEstimator()
        e.onFix(38.55, -121.74, 20.0, 90.0, 0L)
        e.step(1.0, 1000L); val a = e.lng
        e.step(1.0, 4000L); val b = e.lng
        assertEquals(a, b, 1e-12)
    }

    @Test fun standingStillFollowsTheFixExactly() {
        val e = FollowEstimator()
        e.onFix(38.55, -121.74, 0.0, null, 0L)
        e.onFix(38.5501, -121.7401, 0.0, null, 1000L)
        assertEquals(38.5501, e.lat, 1e-12)
        assertTrue(abs(e.lng + 121.7401) < 1e-12)
    }
}
