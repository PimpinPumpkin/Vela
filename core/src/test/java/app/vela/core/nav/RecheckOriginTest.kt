package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a recheck may start, and whether what it brings back begins at the car. */
class RecheckOriginTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / mPerDegLng)

    @Test fun `only a trusted fix with no off-route hits starts a recheck`() {
        assertTrue(NavSession.recheckFrom(trustedFix = true, offRouteHits = 0))
        assertFalse(NavSession.recheckFrom(trustedFix = false, offRouteHits = 0))
        assertFalse(NavSession.recheckFrom(trustedFix = true, offRouteHits = 2))
    }

    @Test fun `a candidate starting 185 m beside the car is not taken`() {
        // The candidate runs south east from a point 185 m west of the car.
        val line = (0..60).map { pt(-185.0 + it * 10.0 * 0.7, -it * 10.0 * 0.7) }
        assertFalse(NavSession.startsAtCar(line, pt(0.0, 0.0)))
        // The car 30 m along it, a few meters off the line, is on it.
        assertTrue(NavSession.startsAtCar(line, pt(-185.0 + 21.0 + 3.0, -21.0)))
    }
}
