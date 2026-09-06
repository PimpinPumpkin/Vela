package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The via-route "appendix": a spur out to a snapped-away point and back, while the course it was
 *  meant to follow goes straight on. */
class RouteSpurTest {
    // A straight east-west course, 6 km, a vertex every 100 m (1 deg lng ~ 87 km at this latitude).
    private val lat = 38.55
    private fun east(m: Double) = LatLng(lat, -121.75 + m / (111_320.0 * Math.cos(Math.toRadians(lat))))
    private val course = (0..60).map { east(it * 100.0) }

    @Test fun `the same line has no spur`() {
        assertFalse(RouteGeometry.hasSpur(course, course))
    }

    @Test fun `a parallel road that keeps advancing is not a spur`() {
        val parallel = course.map { LatLng(it.lat + 0.0012, it.lng) } // ~130 m north, same progress
        assertFalse(RouteGeometry.hasSpur(parallel, course))
    }

    @Test fun `an out-and-back appendix off the middle is a spur`() {
        // Follow the course to 3 km, go 400 m north and come back, then carry on.
        val out = (1..4).map { LatLng(lat + it * 0.0009, east(3000.0).lng) }
        val route = course.take(31) + out + out.reversed() + course.drop(31)
        assertTrue(RouteGeometry.hasSpur(route, course))
    }

    @Test fun `a ramp-shaped loop that rejoins further along is a spur too`() {
        // Leave at 3 km, run 300 m beside the course 40 m off it, loop back 250 m, rejoin: distance
        // grows, progress along the course does not.
        val off = 40.0 / 111_320.0
        val loop = listOf(
            LatLng(lat + off, east(3100.0).lng), LatLng(lat + off, east(3300.0).lng),
            LatLng(lat + 2 * off, east(3300.0).lng), LatLng(lat + 2 * off, east(3050.0).lng),
            LatLng(lat + off, east(3000.0).lng),
        )
        val route = course.take(31) + loop + course.drop(31)
        assertTrue(RouteGeometry.hasSpur(route, course))
    }

    @Test fun `the real one - a 120 m turn-right, u-turn, turn-right stub off the course`() {
        // From a shared trip: the route left a state route onto a side street at an angle for
        // ~60 m, U-turned and came back, while Google's course (and the car) went straight.
        val j = course[47] // 4.7 km in
        val ls = Math.cos(Math.toRadians(lat))
        fun off(alongM: Double, sideM: Double) = LatLng(j.lat + sideM / 111_320.0, j.lng + alongM / (111_320.0 * ls))
        val out = listOf(off(15.0, 20.0), off(30.0, 40.0), off(42.0, 56.0))
        val route = course.take(48) + out + out.reversed() + course.drop(48)
        assertTrue(RouteGeometry.hasSpur(route, course))
    }

    @Test fun `a different approach at the ends is tolerated`() {
        val detourStart = listOf(LatLng(lat + 0.001, course[0].lng), LatLng(lat + 0.001, course[1].lng))
        val route = listOf(course[0]) + detourStart + course.drop(2)
        assertFalse(RouteGeometry.hasSpur(route, course))
    }
}
