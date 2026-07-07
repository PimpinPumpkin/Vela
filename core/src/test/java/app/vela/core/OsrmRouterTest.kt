package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
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
        // A "new name" step is a plain rename — OSRM stamps a few-degree slight bearing artifact on a
        // straight rename node (132nd St SE → Cathcart Way), so slight left/right on a NEW NAME is still
        // CONTINUE (silent). "continue"+slight stays a real bend (spoken) — the branch is split on purpose.
        assertEquals(ManeuverType.CONTINUE, RouteGeometry.osrmType("new name", "slight right"))
        assertEquals(ManeuverType.CONTINUE, RouteGeometry.osrmType("new name", "slight left"))
        assertEquals(ManeuverType.CONTINUE, RouteGeometry.osrmType("new name", null))
        assertEquals(ManeuverType.STRAIGHT, RouteGeometry.osrmType("turn", "straight"))      // junction where straight is a CHOICE — SPOKEN
        assertEquals(ManeuverType.FORK_RIGHT, RouteGeometry.osrmType("fork", "straight"))    // a fork is never silenced
        assertEquals(ManeuverType.ROUNDABOUT, RouteGeometry.osrmType("roundabout", "right"))
        assertEquals(ManeuverType.EXIT_ROUNDABOUT, RouteGeometry.osrmType("exit roundabout", "straight")) // OSRM's exit step carries the exit number
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

    private fun man(t: ManeuverType, dist: Double) =
        app.vela.core.model.Maneuver(t, "x", LatLng(0.0, 0.0), dist, 0.0)

    // ~150 m eastbound line; DEPART at the start, TURN_LEFT at the end (so a signal on the approach counts).
    private fun straightRoute(): Route {
        val poly = (0..4).map { LatLng(47.0, -122.0 + it * 0.0005) }
        val mans = listOf(
            app.vela.core.model.Maneuver(ManeuverType.DEPART, "Head east", poly[0], 150.0, 0.0),
            app.vela.core.model.Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Main Street", poly[4], 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(150.0, 0.0, null, mans)), 150.0, 0.0, null)
    }

    @Test fun lightGuidanceAddsClauseForOneSignalBeforeATurn() {
        val onApproach = LatLng(47.0, -122.0 + 0.001) // sits on the driven line, before the turn
        val out = RouteGeometry.enrichWithLights(straightRoute(), listOf(onApproach))
        val turn = out.legs[0].maneuvers.last()
        assertTrue("clause prepended", turn.instruction.startsWith("Pass the traffic light, then turn left"))
    }

    @Test fun lightGuidanceStaysSilentWhenItDoesntHelp() {
        val turnOf = { signals: List<LatLng> -> RouteGeometry.enrichWithLights(straightRoute(), signals).legs[0].maneuvers.last().instruction }
        // no signals → unchanged
        assertEquals("Turn left onto Main Street", turnOf(emptyList()))
        // a signal ~1 km off the route → not counted → unchanged
        assertEquals("Turn left onto Main Street", turnOf(listOf(LatLng(47.01, -122.0))))
        // 3+ signals on the approach → "pass 4 lights" is unhelpful, Google-style → unchanged
        assertEquals("Turn left onto Main Street", turnOf((1..3).map { LatLng(47.0, -122.0 + it * 0.0004) }))
    }

    @Test fun lightAtTheTurnVertexItselfIsNotCounted() {
        // A signal exactly at the turn intersection is the one you turn AT, not one to "pass" first
        // (the approach walk starts at poly[toIdx], the turn vertex) — exclude it (audit 2026-07-06).
        val atTurn = LatLng(47.0, -122.0 + 0.002) // poly[4], the TURN_LEFT vertex
        val turn = RouteGeometry.enrichWithLights(straightRoute(), listOf(atTurn)).legs[0].maneuvers.last()
        assertEquals("Turn left onto Main Street", turn.instruction)
    }

    @Test fun lightsAtOneJunctionClusterToASingleIntersection() {
        // OSM maps one traffic_signals node per approach/carriageway at a junction (~20 m apart); they must
        // count as ONE intersection, not "pass 2 lights" (audit 2026-07-06). The 3-signal silence test above
        // uses ~30.4 m spacing, which stays UN-clustered at the strict < 30 m radius, so it still passes.
        val a = LatLng(47.0, -122.0 + 0.001)
        val b = LatLng(47.0, -122.0 + 0.00125) // ~19 m from a, same physical junction
        val turn = RouteGeometry.enrichWithLights(straightRoute(), listOf(a, b)).legs[0].maneuvers.last()
        assertTrue("clustered to one light", turn.instruction.startsWith("Pass the traffic light, then turn left"))
    }

    @Test fun pureRenameContinueIsFoldedIntoThePreviousStep() {
        // A "132nd St SE becomes Cathcart Way" rename (CONTINUE, no genuine fork) must NOT be its own
        // banner card — Google shows nothing there, just the next real turn (user 2026-07-06). It folds
        // into the preceding step, summing distance so the polyline still tiles.
        val out = RouteGeometry.foldRenames(listOf(
            man(ManeuverType.DEPART, 200.0),
            man(ManeuverType.CONTINUE, 500.0), // pure rename — folds away
            man(ManeuverType.TURN_LEFT, 80.0),
            man(ManeuverType.ARRIVE, 0.0),
        ))
        assertEquals(3, out.size) // CONTINUE gone
        assertEquals(ManeuverType.DEPART, out[0].type)
        assertEquals(700.0, out[0].distanceMeters, 1e-9) // 200 + 500 — still tiles to the turn
        assertEquals(ManeuverType.TURN_LEFT, out[1].type)
        assertEquals(ManeuverType.ARRIVE, out[2].type)
    }

    @Test fun exitRampForkMergeFoldsToOne() {
        val out = RouteGeometry.consolidateExits(listOf(
            man(ManeuverType.RAMP_RIGHT, 120.0), // exit ramp, 120 m to the fork
            man(ManeuverType.FORK_RIGHT, 30.0),  // keep right, 30 m to the merge
            man(ManeuverType.MERGE, 200.0),      // merge, 200 m to the next turn
            man(ManeuverType.TURN_LEFT, 50.0),   // a genuine later turn
        ))
        assertEquals(2, out.size) // [folded exit, turn]
        assertEquals(ManeuverType.RAMP_RIGHT, out[0].type)
        assertEquals(350.0, out[0].distanceMeters, 1e-9) // 120+30+200 — still tiles the polyline
        assertEquals(ManeuverType.TURN_LEFT, out[1].type)
    }

    @Test fun isolatedRampAndFarForkAreNotFolded() {
        // ramp then a normal turn → untouched
        assertEquals(2, RouteGeometry.consolidateExits(listOf(
            man(ManeuverType.RAMP_RIGHT, 100.0), man(ManeuverType.TURN_LEFT, 50.0),
        )).size)
        // ramp then a fork 900 m later (> EXIT_COMPLEX_GAP_M) → a real separate decision, NOT folded
        assertEquals(3, RouteGeometry.consolidateExits(listOf(
            man(ManeuverType.RAMP_RIGHT, 900.0), man(ManeuverType.FORK_RIGHT, 40.0), man(ManeuverType.ARRIVE, 0.0),
        )).size)
        // a bare fork with no preceding ramp (a real highway split) → NOT folded
        assertEquals(2, RouteGeometry.consolidateExits(listOf(
            man(ManeuverType.FORK_LEFT, 100.0), man(ManeuverType.TURN_RIGHT, 50.0),
        )).size)
    }
}
