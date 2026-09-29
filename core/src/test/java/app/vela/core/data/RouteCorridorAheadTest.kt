package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteCorridorAheadTest {
    // North 0.02 deg (~2.2 km), then back south along the same line: a route that doubles back.
    private val route = listOf(LatLng(38.54, -121.74), LatLng(38.56, -121.74), LatLng(38.54, -121.7401))

    @Test
    fun placesAlreadyPassedDropOut() {
        val behind = Place(id = "behind", name = "behind", location = LatLng(38.541, -121.7395))
        val aheadP = Place(id = "ahead", name = "ahead", location = LatLng(38.558, -121.7395))
        val ahead = RouteCorridor.ahead(route, 1000.0)
        val out = RouteCorridor.alongRoute(listOf(behind, aheadP), ahead, maxMeters = 100.0)
        // "behind" is passed on the way out but comes up again on the way back, so it stays, last.
        assertEquals(listOf("ahead", "behind"), out.map { it.id })
        // Distances count from the car: "ahead" is about 1 km on plus 44 m off the line, not 2 km from the trip start.
        assertEquals(1044.0, out.first().distanceMeters!!, 30.0)
    }

    @Test
    fun noProgressKeepsTheWholeRoute() {
        assertEquals(route, RouteCorridor.ahead(route, 0.0))
    }
}
