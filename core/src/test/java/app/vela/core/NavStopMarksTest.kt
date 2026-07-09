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
        LatLng(38.55, -121.90), LatLng(38.55, -121.80), LatLng(38.55, -121.70),
    )

    @Test fun waypointOnTheLineMarksAtItsAlongRouteDistance() {
        val mid = LatLng(38.55, -121.80) // exactly the middle vertex
        val marks = NavEngine.stopMarks(route(poly), listOf(mid))
        assertNotNull(marks[0])
        val expected = poly[0].distanceTo(poly[1]) // start → middle vertex
        assertEquals(expected, marks[0]!!, 30.0)
    }

    @Test fun waypointFarFromTheLineIsNull() {
        val farNorth = LatLng(38.85, -121.80) // ~33 km off the line
        assertNull(NavEngine.stopMarks(route(poly), listOf(farNorth))[0])
    }

    @Test fun marksAreOrderedAlongTheRoute() {
        val a = LatLng(38.55, -121.85) // ~1/4 along
        val b = LatLng(38.55, -121.75) // ~3/4 along
        val marks = NavEngine.stopMarks(route(poly), listOf(a, b))
        assertNotNull(marks[0]); assertNotNull(marks[1])
        assertEquals(true, marks[0]!! < marks[1]!!)
    }

    @Test fun outAndBackRouteKeepsMarksInStopOrder() {
        // Route goes west→east, then doubles back west: origin → B's location (outbound) → A → B (return).
        // Stops in travel order are [A (far end), B (hit on the RETURN pass)]. A global nearest-projection
        // would give B its FIRST (outbound) pass — before A — firing its cue early and out of order; the
        // windowed projection must place mark(B) after mark(A).
        val outAndBack = listOf(
            LatLng(38.55, -121.90), LatLng(38.55, -121.70), LatLng(38.55, -121.80),
        )
        val a = LatLng(38.55, -121.70)
        val b = LatLng(38.55, -121.80)
        val marks = NavEngine.stopMarks(route(outAndBack), listOf(a, b))
        assertNotNull(marks[0]); assertNotNull(marks[1])
        assertEquals(true, marks[1]!! > marks[0]!!)
    }

    @Test fun emptyAndDegenerateInputs() {
        assertEquals(emptyList<Double?>(), NavEngine.stopMarks(route(poly), emptyList()))
        // A route with no drawable line → every stop is unlocatable (null), never crashes.
        assertEquals(listOf(null), NavEngine.stopMarks(route(listOf(LatLng(38.55, -121.9))), listOf(LatLng(38.55, -121.9))))
    }
}
