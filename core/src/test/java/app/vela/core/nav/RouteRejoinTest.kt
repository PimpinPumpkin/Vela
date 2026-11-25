package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRejoinTest {
    private val lat = 38.5449
    private val lng0 = -121.7405 // Davis fixture
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double = 0.0) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    /** Google's route: east 10 km, a named step every km, then the arrival. */
    private val old: Route = run {
        val poly = (0..1000).map { at(it * 10.0) }
        val steps = (0 until 10).map { k ->
            Maneuver(if (k == 0) ManeuverType.DEPART else ManeuverType.STRAIGHT, "Continue onto Road $k", at(k * 1000.0), 1000.0, 60.0, road = "Road $k")
        } + Maneuver(ManeuverType.ARRIVE, "Arrive", at(10_000.0), 0.0, 0.0)
        Route(poly, listOf(RouteLeg(10_000.0, 600.0, 640.0, steps)), 10_000.0, 600.0, 640.0, source = RouteSource.GOOGLE_HYBRID)
    }

    /** The phone's way back: from [from] along [path] to its end, one step and the arrival. */
    private fun head(path: List<LatLng>): Route {
        var len = 0.0
        for (i in 1 until path.size) len += path[i - 1].distanceTo(path[i])
        val steps = listOf(
            Maneuver(ManeuverType.DEPART, "Head east on Side Street", path.first(), len, len / 12.0, road = "Side Street"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", path.last(), 0.0, 0.0),
        )
        return Route(path, listOf(RouteLeg(len, len / 12.0, null, steps)), len, len / 12.0, null, source = RouteSource.OBF, offline = true)
    }

    @Test fun `the way back aims at the first step three km past where the car left`() {
        val t = RouteRejoin.target(old, alongM = 2_300.0)!!
        assertEquals(6, t.step)
        assertEquals(6_000.0, t.alongM, 0.01)
    }

    @Test fun `no target when only the arrival is that far on`() {
        assertNull(RouteRejoin.target(old, alongM = 7_500.0))
    }

    @Test fun `a way back along the old road keeps Google's steps from the join`() {
        val t = RouteRejoin.target(old, alongM = 2_300.0)!!
        // Off to the north, east along a side street, then down onto the old road 500 m before the join.
        val path = listOf(at(2_300.0, 300.0), at(5_000.0, 300.0), at(5_500.0, 0.0), at(6_000.0))
        val joined = RouteRejoin.splice(head(path), old, t)
        assertNotNull(joined)
        joined!!
        assertTrue(joined.isOffline)
        assertTrue(RouteRejoin.joined(joined))
        assertEquals(listOf("Side Street", "Road 6", "Road 7", "Road 8", "Road 9", null), joined.maneuvers.map { it.road })
        assertEquals(joined.maneuvers.sumOf { it.distanceMeters }, joined.distanceMeters, 0.01)
        assertNull(joined.durationInTrafficSeconds)
        assertEquals(old.polyline.last(), joined.polyline.last())
    }

    @Test fun `a way back that comes in on another road is refused`() {
        val t = RouteRejoin.target(old, alongM = 2_300.0)!!
        // Straight down from the north onto the join point: the old step there assumes coming from the west.
        val path = listOf(at(2_300.0, 300.0), at(6_000.0, 300.0), at(6_000.0, 0.0))
        assertNull(RouteRejoin.splice(head(path), old, t))
    }

    @Test fun `a way back that ends off the join point is refused`() {
        val t = RouteRejoin.target(old, alongM = 2_300.0)!!
        val path = listOf(at(2_300.0, 300.0), at(5_500.0, 0.0), at(5_900.0))
        assertNull(RouteRejoin.splice(head(path), old, t))
    }
}
