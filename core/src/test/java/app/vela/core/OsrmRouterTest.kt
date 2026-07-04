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
        // Same-physical-road straight-ons (name change or not) are CONTINUE — voice-silent in
        // NavEngine ("it keeps saying continue on the road I'm already on"). Pins for the whole
        // safety boundary of that silence:
        assertEquals(ManeuverType.CONTINUE, RouteGeometry.osrmType("new name", "straight"))
        assertEquals(ManeuverType.CONTINUE, RouteGeometry.osrmType("continue", "straight"))
        assertEquals(ManeuverType.CONTINUE, RouteGeometry.osrmType("continue", null))
        assertEquals(ManeuverType.TURN_LEFT, RouteGeometry.osrmType("continue", "left"))     // 90° bend keeping the name — SPOKEN
        assertEquals(ManeuverType.SLIGHT_RIGHT, RouteGeometry.osrmType("new name", "slight right")) // bear right at a rename — SPOKEN
        assertEquals(ManeuverType.STRAIGHT, RouteGeometry.osrmType("turn", "straight"))      // junction where straight is a CHOICE — SPOKEN
        assertEquals(ManeuverType.FORK_RIGHT, RouteGeometry.osrmType("fork", "straight"))    // a fork is never silenced
        assertEquals(ManeuverType.ROUNDABOUT, RouteGeometry.osrmType("roundabout", "right"))
        assertEquals(ManeuverType.RAMP_RIGHT, RouteGeometry.osrmType("on ramp", "slight right"))
        assertEquals(ManeuverType.MERGE, RouteGeometry.osrmType("merge", "left"))
        assertEquals(ManeuverType.UTURN, RouteGeometry.osrmType("turn", "uturn"))
    }

    @Test fun phrasesReadNaturally() {
        assertEquals("Turn right onto 164th St SE", RouteGeometry.osrmPhrase("turn", "right", "164th St SE", null, null, null))
        assertEquals("Continue onto 132nd St SE", RouteGeometry.osrmPhrase("new name", "straight", "132nd St SE", null, null, null))
        assertEquals("Arrive at your destination", RouteGeometry.osrmPhrase("arrive", null, null, null, null, null))
        assertEquals("At the roundabout, take exit 2 onto Main St", RouteGeometry.osrmPhrase("roundabout", null, "Main St", null, null, 2))
        assertEquals("Head out on Elm St", RouteGeometry.osrmPhrase("depart", "left", "Elm St", null, null, null))
    }

    // Highways identify by ref, not name — the ref must reach the text (so it reads right AND the banner
    // can pull a shield out of it). Regression for the "take the exit / no street / no shield" field bug.
    @Test fun highwayRefsAndExits() {
        // ref carried in as `road` (parseOsrmRoute does name ?: ref) → named continue + a shield-able "I 80"
        assertEquals("Continue onto I 80", RouteGeometry.osrmPhrase("new name", "straight", "I 80", null, null, null))
        assertEquals("Merge toward I 90 E", RouteGeometry.osrmPhrase("merge", "slight right", "I 90 E", "I 90 E", null, null))
        // off ramp: exit number + sign destination (both carry through to the banner as tab + shield)
        assertEquals("Take exit 15 toward I-80 E: Sacramento", RouteGeometry.osrmPhrase("off ramp", "right", null, "I-80 E: Sacramento", "15", null))
        assertEquals("Take the exit toward Reno", RouteGeometry.osrmPhrase("off ramp", "right", null, "Reno", null, null))
        assertEquals("Take the exit", RouteGeometry.osrmPhrase("off ramp", "right", null, null, null, null))
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
