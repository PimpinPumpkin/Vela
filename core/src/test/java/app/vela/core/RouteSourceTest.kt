package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.replay.TripLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-field provenance: what each source means for the nav session's two questions, and
 *  that a trip file carries the source across a round trip (and reads UNKNOWN when it predates it). */
class RouteSourceTest {
    private fun route(source: RouteSource, provisional: Boolean = false, abbreviated: Boolean = false, offline: Boolean = false) = Route(
        polyline = listOf(LatLng(38.54, -121.74), LatLng(38.55, -121.73)),
        legs = listOf(RouteLeg(1000.0, 120.0, null, emptyList())),
        distanceMeters = 1000.0, durationSeconds = 120.0, durationInTrafficSeconds = null,
        provisional = provisional, abbreviatedSteps = abbreviated, offline = offline, source = source,
    )

    @Test fun provisionalIsNeverDrivable() {
        assertFalse(route(RouteSource.GOOGLE_PROVISIONAL).drivable)
        assertFalse(route(RouteSource.OSRM, provisional = true).drivable) // the boolean still counts during the transition
        assertTrue(route(RouteSource.OSRM).drivable)
        assertTrue(route(RouteSource.OSRM_VIA_SNAP).drivable)
    }

    @Test fun abbreviatedHasNoRealSteps() {
        assertFalse(route(RouteSource.GOOGLE_ABBREVIATED).hasRealSteps)
        assertFalse(route(RouteSource.GOOGLE_NAMED, abbreviated = true).hasRealSteps)
        assertTrue(route(RouteSource.OBF).hasRealSteps)
        assertTrue(route(RouteSource.VALHALLA).hasRealSteps)
    }

    @Test fun onDeviceRoutesAreOffline() {
        assertTrue(route(RouteSource.OBF).isOffline)
        assertTrue(route(RouteSource.GRAPHHOPPER).isOffline)
        assertFalse(route(RouteSource.OSRM).isOffline)
        assertTrue(route(RouteSource.OSRM, offline = true).isOffline)
    }

    @Test fun tripLogRoundTripsTheSource() {
        val r = route(RouteSource.GOOGLE_ABBREVIATED, abbreviated = true)
        val parsed = TripLog.parseRoute(TripLog.encodeRoute(r, "start").lines())
        assertEquals(RouteSource.GOOGLE_ABBREVIATED, parsed?.source)
        assertTrue(parsed?.abbreviatedSteps == true)
        assertFalse(parsed!!.hasRealSteps)
    }

    @Test fun oldTripFilesReadUnknown() {
        val r = route(RouteSource.OSRM)
        val text = TripLog.encodeRoute(r, "start").replace(";source=OSRM", "").replace("source=OSRM", "")
        assertEquals(RouteSource.UNKNOWN, TripLog.parseRoute(text.lines())?.source)
    }
}
