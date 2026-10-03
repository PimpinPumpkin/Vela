package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Placing corridor-fetched points on the route ([RouteProjection]) - the guard that stops
 *  something on a parallel street being announced as coming up on yours. */
class RouteProjectionTest {

    // A straight run east from the Davis fixture, ~1 vertex per km.
    private val poly = (0..10).map { LatLng(38.5449, -121.7405 + it * 0.0115) }
    private val cum = RouteProjection.cumulative(poly)

    @Test fun `a point on the route is placed at its distance along it`() {
        assertEquals(cum[3], RouteProjection.alongMeters(poly, cum, poly[3])!!, 5.0)
    }

    @Test fun `a point just off the kerb still counts`() {
        val kerb = LatLng(poly[3].lat + 0.0001, poly[3].lng) // ~11 m
        assertEquals(cum[3], RouteProjection.alongMeters(poly, cum, kerb)!!, 20.0)
    }

    @Test fun `a point on a parallel street is rejected`() {
        // ~330 m north: a different road, not this one.
        assertNull(RouteProjection.alongMeters(poly, cum, LatLng(poly[3].lat + 0.003, poly[3].lng)))
    }

    // The route runs east (bearing 90). A sign's road orientation is 0-179.
    @Test fun `a stop sign on the route, on a road running your way, is yours`() {
        assertEquals(true, RouteProjection.stopIsOnRoute(poly, cum, LatLng(poly[3].lat + 0.00003, poly[3].lng + 0.004), 90))
    }

    @Test fun `the sign holding the cross street is not yours`() {
        // 15 m up the side street, whose road runs north-south.
        assertEquals(false, RouteProjection.stopIsOnRoute(poly, cum, LatLng(poly[3].lat + 0.000135, poly[3].lng), 0))
    }

    @Test fun `a sign on the parallel street a block over is not yours`() {
        // Same direction, 90 m north: inside the corridor, not on the route.
        assertEquals(false, RouteProjection.stopIsOnRoute(poly, cum, LatLng(poly[3].lat + 0.0008, poly[3].lng + 0.004), 90))
    }

    @Test fun `a sign with no known road direction is judged on distance alone`() {
        assertEquals(true, RouteProjection.stopIsOnRoute(poly, cum, LatLng(poly[3].lat, poly[3].lng + 0.004), null))
        assertEquals(false, RouteProjection.stopIsOnRoute(poly, cum, LatLng(poly[3].lat + 0.0008, poly[3].lng + 0.004), null))
    }

    @Test fun `a light at a junction the route crosses is yours, the next street's is not`() {
        // On the route, and 15 m up the cross street at the same junction.
        assertEquals(true, RouteProjection.signalIsOnRoute(poly, cum, LatLng(poly[3].lat, poly[3].lng + 0.004)))
        assertEquals(true, RouteProjection.signalIsOnRoute(poly, cum, LatLng(poly[3].lat + 0.000135, poly[3].lng)))
        // The parallel street a block over, 90 m north.
        assertEquals(false, RouteProjection.signalIsOnRoute(poly, cum, LatLng(poly[3].lat + 0.0008, poly[3].lng + 0.004)))
    }

    @Test fun `midway along a segment projects proportionally`() {
        val mid = LatLng(poly[2].lat, (poly[2].lng + poly[3].lng) / 2)
        val along = RouteProjection.alongMeters(poly, cum, mid)!!
        assertEquals((cum[2] + cum[3]) / 2, along, 30.0)
    }

    @Test fun `a degenerate route places nothing`() {
        assertNull(RouteProjection.alongMeters(listOf(poly[0]), doubleArrayOf(0.0), poly[0]))
    }
}
