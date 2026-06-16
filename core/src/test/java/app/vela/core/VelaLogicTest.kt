package app.vela.core

import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavEngine
import app.vela.core.nav.NavEvent
import app.vela.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineCodecTest {

    /** Reference vector straight from Google's encoded-polyline documentation. */
    @Test
    fun decodesGoogleReferenceVector() {
        val pts = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5)
        assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5)
        assertEquals(-120.95, pts[1].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5)
        assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test
    fun roundTrips() {
        val path = listOf(
            LatLng(37.7749, -122.4194),
            LatLng(37.7849, -122.4094),
            LatLng(37.7949, -122.3994),
        )
        val again = PolylineCodec.decode(PolylineCodec.encode(path))
        assertEquals(path.size, again.size)
        for (i in path.indices) {
            assertEquals(path[i].lat, again[i].lat, 1e-5)
            assertEquals(path[i].lng, again[i].lng, 1e-5)
        }
    }
}

class NavEngineTest {

    private fun straightRoute(): Route {
        val a = LatLng(37.0000, -122.0000)
        val mid = LatLng(37.0050, -122.0000)
        val b = LatLng(37.0100, -122.0000)
        return Route(
            polyline = listOf(a, mid, b),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 1100.0,
                    durationSeconds = 80.0,
                    durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 1100.0, 80.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", b, 0.0, 0.0),
                    ),
                ),
            ),
            distanceMeters = 1100.0,
            durationSeconds = 80.0,
            durationInTrafficSeconds = null,
        )
    }

    @Test
    fun advancesPastDepartThenArrives() {
        val route = straightRoute()
        val start = route.polyline.first()
        val (afterStart, _) = NavEngine.update(route, NavState(), start)
        assertTrue("should advance off the DEPART step", afterStart.stepIndex >= 1)

        val (arrived, events) = NavEngine.update(route, afterStart, route.polyline.last())
        assertTrue("should mark arrived at destination", arrived.arrived)
        assertTrue("should emit an Arrived event", events.any { it is NavEvent.Arrived })
    }

    @Test
    fun detectsOffRoute() {
        val route = straightRoute()
        // ~400m east of the north-south line → well past the off-route threshold.
        val offPoint = LatLng(37.0050, -121.9955)
        var state = NavState()
        var sawReroute = false
        repeat(6) {
            val (next, events) = NavEngine.update(route, state, offPoint)
            state = next
            if (events.any { it is NavEvent.RerouteNeeded }) sawReroute = true
        }
        assertTrue("repeated off-route fixes should request a reroute", sawReroute)
    }
}
