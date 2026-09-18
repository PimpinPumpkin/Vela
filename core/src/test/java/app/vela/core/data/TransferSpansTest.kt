package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TrafficSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RouteGeometry.transferSpans]: Google's congestion spans carried onto a route with different
 *  geometry, colored only where the two share the road (issue #403). */
class TransferSpansTest {
    /** A straight east-west line at [lat] from [lng0], [n] points [stepM] apart. */
    private fun line(lat: Double, lng0: Double, n: Int, stepM: Double = 50.0): List<LatLng> {
        val dLng = stepM / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return List(n) { LatLng(lat, lng0 + it * dLng) }
    }
    private fun route(poly: List<LatLng>, spans: List<TrafficSpan> = emptyList()): Route {
        val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
        return Route(poly, listOf(RouteLeg(cum.last(), 600.0, null, emptyList())), cum.last(), 600.0, null, trafficSpans = spans)
    }

    @Test
    fun `same road gets the span at the same distances`() {
        val g = route(line(38.5, -121.7, 100), listOf(TrafficSpan(2, 1000.0, 500.0)))
        val open = route(line(38.5, -121.7, 100))
        val out = RouteGeometry.transferSpans(g, open)
        assertEquals(1, out.size)
        assertEquals(2, out[0].level)
        assertEquals(1000.0, out[0].startMeters, 30.0)
        assertEquals(500.0, out[0].lengthMeters, 60.0)
    }

    @Test
    fun `a route on a parallel road 200 m away gets nothing`() {
        val g = route(line(38.5, -121.7, 100), listOf(TrafficSpan(3, 0.0, 4000.0)))
        val open = route(line(38.5018, -121.7, 100)) // ~200 m north
        assertTrue(RouteGeometry.transferSpans(g, open).isEmpty())
    }

    @Test
    fun `only the shared stretch is colored when the open route joins late`() {
        // Google: 0..5000 m along the road, jammed the whole way. Open route: same road but it
        // only joins at 2500 m (its first half runs 300 m north), so the color starts there.
        val g = route(line(38.5, -121.7, 101), listOf(TrafficSpan(3, 0.0, 5000.0)))
        val north = line(38.5027, -121.7, 50)
        val shared = line(38.5, -121.7, 101).drop(50)
        val open = route(north + shared)
        val out = RouteGeometry.transferSpans(g, open)
        assertEquals(out.toString(), 1, out.size)
        val cum = app.vela.core.nav.RouteProjection.cumulative(open.polyline)
        // The open route's along-distance where the shared road begins.
        val joinAt = cum[50]
        assertEquals(joinAt, out[0].startMeters, 80.0)
        assertTrue(out[0].startMeters + out[0].lengthMeters <= cum.last() + 30.0)
    }

    @Test
    fun `no spans in means no spans out`() {
        val g = route(line(38.5, -121.7, 10))
        assertTrue(RouteGeometry.transferSpans(g, g).isEmpty())
    }
}
