package app.vela.core

import app.vela.core.model.Lane
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavEngine
import app.vela.core.nav.NavEvent
import app.vela.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-bug regressions from a real drive (2026-07-03):
 *
 * 1. "it keeps saying continue on the road I'm already on … the name of the road does change but
 * it literally is the same road just going straight" - CONTINUE (same physical road, straight
 * on, name change or not) must be voice-SILENT while staying on the banner/step list; a
 * lane-carrying CONTINUE still speaks (the driver must position); a real turn still speaks.
 *
 * 2. "turns showing up 12 miles early" - on an out-and-back route both passes ride the SAME
 * asphalt, and the old GLOBAL maneuver projection matched a return-leg turn onto its outbound
 * twin: the moment it became the target (right after the turnaround), its along-route distance
 * read ≈ 0 and it announced/advanced miles early. Maneuvers now project inside a window around
 * their prefix-summed step position, and off-window re-acquire prefers the pass nearest the
 * current progress.
 */
class NavContinueSilenceTest {

    private fun north(nSteps: Int, fromLat: Double = 37.0000, lng: Double = -122.0000, step: Double = 0.0005) =
        (0..nSteps).map { LatLng(fromLat + it * step, lng) }

    private fun route(polyline: List<LatLng>, maneuvers: List<Maneuver>): Route {
        val dist = maneuvers.sumOf { it.distanceMeters }
        return Route(
            polyline = polyline,
            legs = listOf(RouteLeg(dist, dist / 13.0, null, maneuvers)),
            distanceMeters = dist,
            durationSeconds = dist / 13.0,
            durationInTrafficSeconds = null,
        )
    }

    /** DEPART → CONTINUE ("Continue onto Oak Ave", the NAME CHANGES) → TURN_RIGHT → ARRIVE,
     * 1.1 km due north. The continue sits at ~500 m, the turn at ~1000 m. */
    private fun continueRoute(lanes: List<Lane> = emptyList()): Route {
        val poly = north(20) // 21 points to 37.0100, ~55 m apart
        return route(
            poly,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", poly.first(), 500.0, 38.0),
                Maneuver(
                    ManeuverType.CONTINUE, "Continue onto Oak Ave", LatLng(37.0045, -122.0000),
                    500.0, 38.0, road = "Oak Ave", lanes = lanes,
                ),
                Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Pine St", LatLng(37.0090, -122.0000), 110.0, 9.0, road = "Pine St"),
                Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
            ),
        )
    }

    @Test fun `continue is voice-silent even when the road name changes`() {
        val route = continueRoute()
        var state = NavEngine.update(route, NavState(), route.polyline.first()).first // past DEPART
        assertEquals(1, state.stepIndex)

        // Approaching the CONTINUE inside prompt range (~222 m out): NO voice, NO haptic.
        val (approach, approachEvents) = NavEngine.update(route, state, LatLng(37.0025, -122.0000))
        assertTrue("no speech approaching a plain continue", approachEvents.none { it is NavEvent.Speak })
        assertTrue("no haptics approaching a plain continue", approachEvents.none { it is NavEvent.Haptic })
        state = approach

        // Passing it: advances silently (no "turn now" interrupt) - it stays a banner/list step only.
        val (passed, passEvents) = NavEngine.update(route, state, LatLng(37.0045, -122.0000))
        assertEquals("advances past the continue", 2, passed.stepIndex)
        assertTrue("no speech at the continue itself", passEvents.none { it is NavEvent.Speak })
        state = passed

        // The REAL turn after it still announces.
        val (_, turnEvents) = NavEngine.update(route, state, LatLng(37.0070, -122.0000))
        assertTrue(
            "the following turn still speaks",
            turnEvents.filterIsInstance<NavEvent.Speak>().any { it.text.contains("Turn right onto Pine St") },
        )
    }

    @Test fun `a lane-carrying continue still speaks, lanes first`() {
        // 1 of 2 lanes valid, at the LEFT edge → "use the left lane" - the driver must position,
        // so this continue is worth a sentence even though the maneuver itself is "do nothing".
        val route = continueRoute(lanes = listOf(Lane(listOf("straight"), true), Lane(listOf("straight", "right"), false)))
        val state = NavEngine.update(route, NavState(), route.polyline.first()).first
        val (_, events) = NavEngine.update(route, state, LatLng(37.0025, -122.0000))
        val spoken = events.filterIsInstance<NavEvent.Speak>()
        assertTrue("a laned continue speaks", spoken.isNotEmpty())
        assertTrue(
            "lanes preface the instruction",
            spoken.any { it.text.contains("Use the left lane to continue onto Oak Ave") },
        )
    }

    @Test fun `a continue past a plain turn bay stays silent`() {
        // 3 lanes; the LEFT lane is a left-turn bay (invalid, marked only "left"), the two right lanes
        // sail straight through. laneGuidance sees a valid subset → non-null, but the off lane offers no
        // straight/slight onward path, so it's a turn bay at an intersection, not a fork. You do nothing
        // to continue onto Oak Ave - Google says nothing, and neither should we (the reported "it tells me
        // to use the lanes to continue when the only thing that changes is the road's name").
        val route = continueRoute(lanes = listOf(
            Lane(listOf("left"), false),
            Lane(listOf("straight"), true),
            Lane(listOf("straight"), true),
        ))
        val state = NavEngine.update(route, NavState(), route.polyline.first()).first
        val spoken = (0..3).fold(state to emptyList<NavEvent>()) { (st, _), i ->
            NavEngine.update(route, st, LatLng(37.0040 - i * 0.0006, -122.0000))
        }.second.filterIsInstance<NavEvent.Speak>()
        assertTrue("a turn-bay continue is silent", spoken.isEmpty())
    }

    @Test fun `a continue past an UNMARKED off lane stays silent`() {
        // The OSRM "none" trap: an off lane with NO painted arrow is emitted as indication "none", which
        // means "no dedicated indication" - NOT "continues straight". This is the equally-real form of the
        // turn-bay above (an unmarked outer lane), and must stay silent too. (Regression for the bug where
        // continueHasGenuineFork treated "none" as straight-ish and re-spoke exactly this case.)
        val route = continueRoute(lanes = listOf(
            Lane(listOf("none"), false),
            Lane(listOf("straight"), true),
            Lane(listOf("straight"), true),
        ))
        val state = NavEngine.update(route, NavState(), route.polyline.first()).first
        val spoken = (0..3).fold(state to emptyList<NavEvent>()) { (st, _), i ->
            NavEngine.update(route, st, LatLng(37.0040 - i * 0.0006, -122.0000))
        }.second.filterIsInstance<NavEvent.Speak>()
        assertTrue("an unmarked-off-lane continue is silent", spoken.isEmpty())
    }

    /** Out-and-back: 1.1 km north, U-turn, 1.1 km back south on the SAME line. The return-leg
     * turn (at 2 km along, ~200 m from home) is geometrically identical to the outbound point
     * at ~200 m. Just past the turnaround it becomes the target - the old global projection
     * matched it onto the outbound pass and read its distance as 0 ("turn now", ~1.6 km early). */
    @Test fun `a return-leg turn is measured on the return leg, not its outbound twin`() {
        val up = north(20)
        val down = north(20).reversed().drop(1) // back down the same line (shared apex vertex)
        val poly = up + down
        val turnLoc = LatLng(37.0020, -122.0000) // on the RETURN leg, true along ≈ 2000 m
        val route = route(
            poly,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", poly.first(), 1111.0, 85.0),
                Maneuver(ManeuverType.UTURN, "Make a U-turn", LatLng(37.0100, -122.0000), 889.0, 68.0),
                Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm St", turnLoc, 222.0, 17.0, road = "Elm St"),
                Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
            ),
        )
        // Just past the turnaround, heading south: target is the return-leg turn, ~780 m ahead.
        val state = NavState(stepIndex = 2, traveledM = 1200.0)
        val (next, events) = NavEngine.update(route, state, LatLng(37.0090, -122.0000))
        assertEquals("must not advance a turn ~780 m away", 2, next.stepIndex)
        assertTrue("must not announce it yet (old global projection read ~0 m)", events.none { it is NavEvent.Speak })
        assertTrue(
            "distance reads the RETURN pass (~780 m), got ${next.distanceToNextManeuver}",
            next.distanceToNextManeuver in 600.0..900.0,
        )
    }

    /** Re-acquire (fix outside the forward window) on a route that passes near itself must pick
     * the pass nearest our progress - nearest-perpendicular alone teleported progress onto the
     * return leg whenever GPS drifted a couple of metres toward it. */
    @Test fun `re-acquire prefers the pass nearest current progress`() {
        val up = north(20, lng = -122.0000)
        val down = north(20, lng = -122.00008).reversed() // return leg ~7 m west (divided road)
        val poly = up + down
        val route = route(
            poly,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", poly.first(), 2222.0, 170.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
            ),
        )
        // Progress mid-outbound (445 m), fix well BEHIND the forward window at ~222 m along -
        // and slightly WEST, i.e. perpendicular-closer to the RETURN leg (~2 m) than to the
        // outbound one (~9 m). Nearest-perpendicular would re-acquire at ~2000 m (the return
        // pass); anchored scoring must keep us on the outbound pass.
        val state = NavState(stepIndex = 0, traveledM = 445.0)
        val (next, _) = NavEngine.update(route, state, LatLng(37.0020, -122.00010))
        assertTrue(
            "progress re-acquires the NEAR pass (~222 m), got ${next.traveledM}",
            next.traveledM in 150.0..300.0,
        )
        assertFalse("must not teleport onto the return leg", next.traveledM > 1500.0)
    }
}
