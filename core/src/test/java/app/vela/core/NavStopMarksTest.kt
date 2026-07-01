package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [NavEngine.stopMarks] projects each multi-stop waypoint onto the route line and returns the
 * metres-along-route of its nearest point (its "you're passing this stop" cue mark), or null when
 * the stop sits too far from the line to be on this route. Drives the per-stop arrival cue.
 */
class NavStopMarksTest {

    private fun route(poly: List<LatLng>) = Route(
        polyline = poly, legs = emptyList(),
        distanceMeters = 0.0, durationSeconds = 0.0, durationInTrafficSeconds = null,
    )

    // A straight west→east route at constant latitude.
    private val poly = listOf(
        LatLng(47.60, -122.40), LatLng(47.60, -122.30), LatLng(47.60, -122.20),
    )

    @Test fun waypointOnTheLineMarksAtItsAlongRouteDistance() {
        val mid = LatLng(47.60, -122.30) // exactly the middle vertex
        val marks = NavEngine.stopMarks(route(poly), listOf(mid))
        assertNotNull(marks[0])
        val expected = poly[0].distanceTo(poly[1]) // start → middle vertex
        assertEquals(expected, marks[0]!!, 30.0)
    }

    @Test fun waypointFarFromTheLineIsNull() {
        val farNorth = LatLng(47.90, -122.30) // ~33 km off the line
        assertNull(NavEngine.stopMarks(route(poly), listOf(farNorth))[0])
    }

    @Test fun marksAreOrderedAlongTheRoute() {
        val a = LatLng(47.60, -122.35) // ~1/4 along
        val b = LatLng(47.60, -122.25) // ~3/4 along
        val marks = NavEngine.stopMarks(route(poly), listOf(a, b))
        assertNotNull(marks[0]); assertNotNull(marks[1])
        assertEquals(true, marks[0]!! < marks[1]!!)
    }

    @Test fun emptyAndDegenerateInputs() {
        assertEquals(emptyList<Double?>(), NavEngine.stopMarks(route(poly), emptyList()))
        // A route with no drawable line → every stop is unlocatable (null), never crashes.
        assertEquals(listOf(null), NavEngine.stopMarks(route(listOf(LatLng(47.6, -122.4))), listOf(LatLng(47.6, -122.4))))
    }
}
