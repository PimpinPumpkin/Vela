package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reroute goes the way the car is going, while that costs little ([RouteGeometry.forwardChoice]). */
class ForwardChoiceTest {
    private val here = LatLng(38.5449, -121.7405) // Davis fixture
    private val mLat = 111_320.0

    /** A route that sets off north or south for 2 km, with these times in minutes. */
    private fun route(north: Boolean, freeMin: Double, trafficMin: Double? = null): Route {
        val sign = if (north) 1 else -1
        val poly = (0..20).map { LatLng(here.lat + sign * it * 100.0 / mLat, here.lng) }
        val ms = listOf(Maneuver(ManeuverType.DEPART, "Head out", poly.first(), 2000.0, 0.0), Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0))
        return Route(poly, listOf(RouteLeg(2000.0, freeMin * 60, null, ms)), 2000.0, freeMin * 60, trafficMin?.let { it * 60 })
    }

    private val north = 0.0

    @Test fun `the best route is kept when it goes the car's way`() {
        val best = route(north = true, 40.0, 55.0); val back = route(north = false, 42.0, 58.0)
        assertEquals(listOf(best), RouteGeometry.forwardChoice(listOf(best, back), north, 41.0 * 60))
    }

    @Test fun `a turn-around is set aside for a forward route a little slower`() {
        val back = route(north = false, 40.0, 55.0); val on = route(north = true, 41.0, 57.0)
        assertEquals(listOf(on), RouteGeometry.forwardChoice(listOf(back, on), north, 41.0 * 60))
    }

    @Test fun `a forward alternate twenty minutes longer is not taken, the open router's way round is`() {
        // The drive of 2026-10-07: best 55 min starting back, the only forward alternate 75 min,
        // the open router's heading-pinned route a minute longer than the best with no traffic.
        val back = route(north = false, 40.0, 55.0); val far = route(north = true, 47.0, 75.0)
        assertTrue(RouteGeometry.forwardChoice(listOf(back, far), north, 41.0 * 60).isEmpty())
    }

    @Test fun `when every way on costs minutes, the route that turns around stands`() {
        val back = route(north = false, 40.0, 55.0); val far = route(north = true, 47.0, 75.0)
        val chosen = RouteGeometry.forwardChoice(listOf(back, far), north, 52.0 * 60)
        assertSame(back, chosen.first())
        assertEquals(2, chosen.size)
        // And with no forward Google route at all.
        assertSame(back, RouteGeometry.forwardChoice(listOf(back), north, 52.0 * 60).single())
    }

    @Test fun `with only turn-around routes and a cheap way on, the open router's route is used`() {
        val back = route(north = false, 40.0, 55.0)
        assertTrue(RouteGeometry.forwardChoice(listOf(back), north, 42.0 * 60).isEmpty())
    }

    @Test fun `the allowance grows with the trip`() {
        // Three hours out, a way on five minutes slower than turning around is still taken.
        val back = route(north = false, 170.0, 180.0); val on = route(north = true, 174.0, 185.0)
        assertEquals(listOf(on), RouteGeometry.forwardChoice(listOf(back, on), north, null))
    }
}
