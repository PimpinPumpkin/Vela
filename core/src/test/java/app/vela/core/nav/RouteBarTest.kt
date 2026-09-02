package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TrafficSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route bar's positioning ([RouteBar]).
 *
 * The bar's whole job is to say WHERE something sits on the road ahead, so the failures worth
 * pinning are the ones a screenshot would not reveal: a jam you already drove through still being
 * drawn, something on a parallel street counted as being on your route, or a mark landing at the
 * wrong height on the strip.
 */
class RouteBarTest {

    // A straight 10 km run east from the Davis fixture, roughly one vertex per kilometre.
    private val poly = (0..10).map { LatLng(38.5449, -121.7405 + it * 0.0115) }

    private fun route(distanceM: Double, spans: List<TrafficSpan> = emptyList()) = Route(
        polyline = poly,
        legs = listOf(RouteLeg(distanceM, 600.0, null, emptyList())),
        distanceMeters = distanceM,
        durationSeconds = 600.0,
        durationInTrafficSeconds = null,
        trafficSpans = spans,
    )

    @Test fun `a jam ahead is placed by how far ahead it is`() {
        // 10 km trip, 2 km driven, jam from 6 km to 8 km: half to three quarters of the 8 km left.
        val m = RouteBar.build(route(10_000.0, listOf(TrafficSpan(2, 6_000.0, 2_000.0))), traveledM = 2_000.0, windowM = 20_000.0)
        assertEquals(1, m.bands.size)
        assertEquals(0.5, m.bands[0].from, 0.001)
        assertEquals(0.75, m.bands[0].to, 0.001)
        assertEquals(8_000.0, m.spanM, 0.1)
    }

    @Test fun `a jam already driven through is dropped`() {
        val m = RouteBar.build(route(10_000.0, listOf(TrafficSpan(2, 1_000.0, 500.0))), traveledM = 4_000.0, windowM = 20_000.0)
        assertTrue("nothing behind you belongs on a bar of the road ahead", m.bands.isEmpty())
    }

    @Test fun `a jam you are sitting in starts at your own position, not before it`() {
        val m = RouteBar.build(route(10_000.0, listOf(TrafficSpan(3, 1_000.0, 4_000.0))), traveledM = 2_000.0, windowM = 20_000.0)
        assertEquals("trimmed to the road ahead", 0.0, m.bands[0].from, 0.001)
        assertEquals(0.375, m.bands[0].to, 0.001) // ends at 5 km = 3 km into the remaining 8
    }

    @Test fun `marks behind you and past the destination are dropped`() {
        val marks = listOf(
            RouteBar.Mark.SIGNAL to 500.0,      // behind
            RouteBar.Mark.STOP to 5_000.0,      // ahead
            RouteBar.Mark.CAMERA to 12_000.0,   // past the end
        )
        val m = RouteBar.build(route(10_000.0), traveledM = 2_000.0, markMeters = marks, windowM = 20_000.0)
        assertEquals(1, m.pins.size)
        assertEquals(RouteBar.Mark.STOP, m.pins[0].kind)
        assertEquals(0.375, m.pins[0].at, 0.001)
    }

    @Test fun `marks at the same junction collapse to one`() {
        val marks = listOf(
            RouteBar.Mark.SIGNAL to 5_000.0,
            RouteBar.Mark.STOP to 5_020.0,
            RouteBar.Mark.SIGNAL to 5_040.0,
            RouteBar.Mark.STOP to 7_000.0, // a genuinely separate junction
        )
        val m = RouteBar.build(route(10_000.0), traveledM = 0.0, markMeters = marks, windowM = 20_000.0)
        assertEquals("one glyph per junction", 2, m.pins.size)
    }

    @Test fun `the bar stands down near the destination`() {
        val m = RouteBar.build(route(10_000.0, listOf(TrafficSpan(2, 9_950.0, 50.0))), traveledM = 9_800.0, windowM = 20_000.0)
        assertTrue("a 200 m sliver is noise, the banner covers that stretch", m.isEmpty)
    }

    @Test fun `a point on the route is placed at its distance along it`() {
        val cum = RouteBar.cumulative(poly)
        assertEquals(cum[3], RouteBar.alongMeters(poly, cum, poly[3])!!, 5.0)
    }

    @Test fun `a point on a parallel street is not counted as being on the route`() {
        val cum = RouteBar.cumulative(poly)
        // ~330 m north of the line: a different road, not this one.
        assertNull(RouteBar.alongMeters(poly, cum, LatLng(poly[3].lat + 0.003, poly[3].lng)))
    }

    @Test fun `a point just off the kerb still counts`() {
        val cum = RouteBar.cumulative(poly)
        val kerb = LatLng(poly[3].lat + 0.0001, poly[3].lng) // ~11 m
        assertEquals(cum[3], RouteBar.alongMeters(poly, cum, kerb)!!, 20.0)
    }

    // The whole point of the window: on a long trip the near road must stay readable.
    @Test fun `on a long trip the bar shows the near road, not the whole route`() {
        val far = TrafficSpan(2, 400_000.0, 10_000.0)   // hundreds of km away
        val near = TrafficSpan(3, 3_000.0, 1_000.0)     // 3 km ahead
        val m = RouteBar.build(route(1_200_000.0, listOf(far, near)), traveledM = 0.0)
        assertEquals("only what is inside the window is drawn", 1, m.bands.size)
        assertEquals(0.6, m.bands[0].from, 0.001) // 3 km into a 5 km window
        assertEquals(0.8, m.bands[0].to, 0.001)
        assertEquals(RouteBar.WINDOW_M, m.spanM, 0.1)
        assertTrue("a 1200 km trip does not end inside the window", !m.reachesDestination)
    }

    @Test fun `near the end the window shrinks to the destination`() {
        val m = RouteBar.build(route(10_000.0, listOf(TrafficSpan(2, 9_000.0, 500.0))), traveledM = 8_000.0)
        assertEquals("2 km left, so the bar spans 2 km", 2_000.0, m.spanM, 0.1)
        assertTrue("the top of the bar is the destination", m.reachesDestination)
        assertEquals(0.5, m.bands[0].from, 0.001)
    }
}
