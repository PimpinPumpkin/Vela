package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open router (OSRM) is now the PRIMARY turn-by-turn source — Google's keyless endpoint returns
 * abbreviated steps for longer routes, OSRM gives them all with street names. These cover the bits
 * with logic: mapping OSRM's `maneuver.type`+`modifier` to Vela's [ManeuverType] (arrow + haptic),
 * and synthesizing the human instruction (OSRM ships none).
 */
class OsrmRouterTest {
    @Test fun typesMapToVela() {
        assertEquals(ManeuverType.DEPART, RouteGeometry.osrmType("depart", "left"))
        assertEquals(ManeuverType.ARRIVE, RouteGeometry.osrmType("arrive", null))
        assertEquals(ManeuverType.TURN_RIGHT, RouteGeometry.osrmType("turn", "right"))
        assertEquals(ManeuverType.SLIGHT_LEFT, RouteGeometry.osrmType("turn", "slight left"))
        assertEquals(ManeuverType.STRAIGHT, RouteGeometry.osrmType("new name", "straight"))
        assertEquals(ManeuverType.ROUNDABOUT, RouteGeometry.osrmType("roundabout", "right"))
        assertEquals(ManeuverType.RAMP_RIGHT, RouteGeometry.osrmType("on ramp", "slight right"))
        assertEquals(ManeuverType.MERGE, RouteGeometry.osrmType("merge", "left"))
        assertEquals(ManeuverType.UTURN, RouteGeometry.osrmType("turn", "uturn"))
    }

    @Test fun phrasesReadNaturally() {
        assertEquals("Turn right onto 164th St SE", RouteGeometry.osrmPhrase("turn", "right", "164th St SE", null))
        assertEquals("Continue onto 132nd St SE", RouteGeometry.osrmPhrase("new name", "straight", "132nd St SE", null))
        assertEquals("Arrive at your destination", RouteGeometry.osrmPhrase("arrive", null, null, null))
        assertEquals("At the roundabout, take exit 2 onto Main St", RouteGeometry.osrmPhrase("roundabout", null, "Main St", 2))
        assertEquals("Head out on Elm St", RouteGeometry.osrmPhrase("depart", "left", "Elm St", null))
    }

    // Traffic-aware routing (option 3): only re-route through Google's path when it genuinely diverges.
    private fun route(poly: List<LatLng>) = Route(poly, emptyList(), 0.0, 0.0, null)

    @Test fun divergenceDetectsRerouting() {
        val north = (0..10).map { LatLng(47.86 + it * 0.002, -122.20) }      // straight north
        val nearNorth = (0..10).map { LatLng(47.86 + it * 0.002, -122.199) } // ~75 m east, parallel
        val eastSwing = (0..10).map { LatLng(47.86, -122.20 + it * 0.006) }  // peels off east instead
        assertFalse("parallel ~75m route is NOT a reroute", RouteGeometry.divergent(route(north), route(nearNorth)))
        assertTrue("a route that peels far off IS a reroute", RouteGeometry.divergent(route(north), route(eastSwing)))
    }

    @Test fun sampleViasSpacedAlongTheLine() {
        val poly = (0..40).map { LatLng(47.86 + it * 0.001, -122.20) }
        val vias = RouteGeometry.sampleVias(poly)
        assertEquals(12, vias.size) // dense enough to follow a diverged path, sparse enough to keep turns
        // interior only (never the origin/destination endpoints) and strictly in order along the route
        assertTrue("first via is past the origin", vias.first().lat > poly.first().lat)
        assertTrue("last via is before the destination", vias.last().lat < poly.last().lat)
        assertTrue("vias run monotonically along the route", (1 until vias.size).all { vias[it].lat > vias[it - 1].lat })
    }

    @Test fun sampleViasDegradesOnTinyPolylines() {
        assertTrue(RouteGeometry.sampleVias((0..1).map { LatLng(47.0 + it, -122.0) }).isEmpty())
        // a 3-point line has exactly one interior point → one via, no crash
        assertEquals(1, RouteGeometry.sampleVias((0..2).map { LatLng(47.0 + it * 0.01, -122.0) }).size)
    }
}
