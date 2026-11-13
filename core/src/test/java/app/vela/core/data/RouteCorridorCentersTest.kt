package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteCorridorCentersTest {
    // North along one meridian: 0.1 degree of latitude is about 11.1 km.
    private fun north(degrees: Double, via: Int = 1): List<LatLng> =
        (0..via + 1).map { LatLng(38.0 + degrees * it / (via + 1), -121.0) }

    @Test
    fun aShortRouteIsSearchedFromItsMiddleVertex() {
        val route = north(0.2) // about 22 km
        assertEquals(listOf(route[route.size / 2]), RouteCorridor.searchCenters(route))
    }

    @Test
    fun aLongRouteGetsAWindowEveryTwentyKilometers() {
        val route = north(0.5) // about 55.7 km
        val centers = RouteCorridor.searchCenters(route)
        assertEquals(3, centers.size)
        val start = route.first()
        assertEquals(10_000.0, start.distanceTo(centers[0]), 100.0)
        assertEquals(30_000.0, start.distanceTo(centers[1]), 100.0)
        assertEquals(50_000.0, start.distanceTo(centers[2]), 100.0)
    }

    @Test
    fun theLastWindowNeverPassesTheEndOfTheRoute() {
        val route = north(0.25, via = 0) // about 27.8 km: two windows, 10 km and the end
        val centers = RouteCorridor.searchCenters(route)
        assertEquals(2, centers.size)
        assertEquals(route.last().lat, centers.last().lat, 0.001)
    }

    @Test
    fun aVeryLongRouteStopsAtTheWindowLimit() {
        val centers = RouteCorridor.searchCenters(north(4.0)) // about 445 km
        assertEquals(RouteCorridor.MAX_WINDOWS, centers.size)
        assertEquals(110_000.0, LatLng(38.0, -121.0).distanceTo(centers.last()), 200.0)
    }

    @Test
    fun aRouteWithoutALineComesBackAsItIs() {
        assertEquals(emptyList<LatLng>(), RouteCorridor.searchCenters(emptyList()))
        assertEquals(listOf(LatLng(38.0, -121.0)), RouteCorridor.searchCenters(listOf(LatLng(38.0, -121.0))))
    }
}
