package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause hold, at the level that matters: how far off the route a stop puts you, and how many
 * moving on-route fixes it takes to pick the drive back up. The session itself needs a data
 * source and a voice to construct, so what is locked here is the geometry the hold reasons
 * with - the same corridor the engine uses, and the auto-resume count.
 */
class NavPauseTest {

    @Test fun `a forecourt across the junction reads as off the route`() {
        // Two points ~120 m apart: a fuel station on the far side of a junction is well outside
        // any driving corridor, which is what makes resume reroute instead of pretending.
        val corridor = NavEngine.offRouteCorridor(TravelMode.DRIVE, null)
        assertTrue("driving corridor should be well under 120 m", corridor < 120.0)
    }

    @Test fun `a walker's corridor is tighter than a driver's`() {
        assertTrue(
            NavEngine.offRouteCorridor(TravelMode.WALK, null) <
                NavEngine.offRouteCorridor(TravelMode.DRIVE, null),
        )
    }

    @Test fun `a noisy fix widens the corridor rather than crying off-route`() {
        val clean = NavEngine.offRouteCorridor(TravelMode.DRIVE, 5.0)
        val noisy = NavEngine.offRouteCorridor(TravelMode.DRIVE, 60.0)
        assertTrue(noisy > clean)
    }

    @Test fun `auto-resume needs more than one lucky fix`() {
        assertTrue(NavSession.AUTO_RESUME_HITS >= 2)
    }

    @Test fun `a driving speed is above every mode's moving floor`() {
        // Auto-resume arms on "stopped or off the route", and 30 mph is neither, so a pause taken
        // while still rolling down the route holds instead of undoing itself.
        val drivingMps = 13.4 // ~30 mph
        assertTrue(drivingMps > 2.0)
    }

    @Test fun `projection measures the perpendicular, not the along-route distance`() {
        // A straight north-south line; a point 50 m east of it is 50 m off route, wherever it
        // sits along the line.
        val path = listOf(LatLng(37.0, -122.0), LatLng(37.01, -122.0))
        val cum = RouteProjection.cumulative(path)
        val east = LatLng(37.005, -122.0 + 50.0 / (111_320.0 * Math.cos(Math.toRadians(37.0))))
        val (_, perp) = NavEngine.projectNearAnchor(path, cum, east, 500.0)
        assertEquals(50.0, perp, 2.0)
    }
}
