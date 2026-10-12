package app.vela.core.nav

import app.vela.core.model.Lane
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two prompts of one step said seconds apart, and a first line cut by the line at the turn:
 *  a trace of 2026-10-11 had "In 550 feet, turn left" then "In 500 feet, turn left" three
 *  seconds later, and a ramp's lane line still playing when "Take the ramp" interrupted it. */
class NavPromptGapTest {
    private val a = LatLng(37.0, -122.0)
    private fun north(p: LatLng, m: Double) = LatLng(p.lat + m / 111_320.0, p.lng)
    private val b = north(a, 1000.0)
    private val c = north(b, 300.0)

    private fun route(turn: Maneuver): Route {
        val legs = listOf(
            RouteLeg(
                distanceMeters = 1300.0, durationSeconds = 90.0, durationInTrafficSeconds = null,
                maneuvers = listOf(
                    Maneuver(ManeuverType.DEPART, "Head north", a, 1000.0, 60.0),
                    turn,
                    Maneuver(ManeuverType.ARRIVE, "Arrive", c, 0.0, 0.0),
                ),
            ),
        )
        return Route(polyline = listOf(a, b, c), legs = legs, distanceMeters = 1300.0, durationSeconds = 90.0, durationInTrafficSeconds = null)
    }

    private fun spoken(events: List<NavEvent>) = events.filterIsInstance<NavEvent.Speak>()

    @Test fun `a band reached seconds after the first prompt is not said again`() {
        val route = route(Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", b, 300.0, 20.0, road = "Oak Avenue"))
        var state = NavEngine.update(route, NavState(), a, speedMps = 13.0).first
        // 170 m before the turn at 13 m/s: the step's first line, said at its real distance.
        val (s1, e1) = NavEngine.update(route, state, north(b, -170.0), speedMps = 13.0)
        state = s1
        assertEquals(1, spoken(e1).size)
        assertTrue(spoken(e1)[0].text, spoken(e1)[0].text.startsWith("In 170 meters, Turn left"))
        // 150 m, the near band, 1.5 s later: nothing, the line just said covers it.
        val (s2, e2) = NavEngine.update(route, state, north(b, -150.0), speedMps = 13.0)
        state = s2
        assertEquals(e2.toString(), 0, spoken(e2).size)
        val (s3, e3) = NavEngine.update(route, state, north(b, -100.0), speedMps = 13.0)
        state = s3
        assertEquals(e3.toString(), 0, spoken(e3).size)
        // The line at the turn still comes.
        val (_, e4) = NavEngine.update(route, state, north(b, -30.0), speedMps = 13.0)
        assertEquals(1, spoken(e4).size)
        assertTrue(spoken(e4)[0].interrupt)
    }

    @Test fun `a far first prompt leaves the near band to be said`() {
        val route = route(Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", b, 300.0, 20.0, road = "Oak Avenue"))
        var state = NavEngine.update(route, NavState(), a, speedMps = 13.0).first
        val (s1, e1) = NavEngine.update(route, state, north(b, -420.0), speedMps = 13.0)
        state = s1
        assertEquals(1, spoken(e1).size)
        val (_, e2) = NavEngine.update(route, state, north(b, -150.0), speedMps = 13.0)
        assertEquals(1, spoken(e2).size)
        assertTrue(spoken(e2)[0].text, spoken(e2)[0].text.startsWith("In 150 meters"))
    }

    @Test fun `a first line the turn would cut keeps its lanes and drops the sign tail`() {
        val lanes = listOf(Lane(listOf("straight"), valid = false), Lane(listOf("straight", "slight right"), valid = true))
        val ramp = Maneuver(ManeuverType.RAMP_RIGHT, "Take the ramp on the right toward I 80 West: Sacramento, Davis", b, 300.0, 20.0, lanes = lanes)
        val route = route(ramp)
        // Far out the whole sign is said.
        var state = NavEngine.update(route, NavState(), a, speedMps = 15.0).first
        val (_, far) = NavEngine.update(route, state, north(b, -400.0), speedMps = 15.0)
        assertEquals(1, spoken(far).size)
        assertTrue(spoken(far)[0].text, spoken(far)[0].text.contains("right lane") && spoken(far)[0].text.contains("toward I 80 West"))
        // Entering the step 107 m out at 15 m/s, the line at the turn comes in under 5 s: the
        // lanes stay, the tail goes.
        state = NavEngine.update(route, NavState(), a, speedMps = 15.0).first
        val (_, near) = NavEngine.update(route, state, north(b, -107.0), speedMps = 15.0)
        assertEquals(1, spoken(near).size)
        val line = spoken(near)[0].text
        assertTrue(line, line.contains("right lane"))
        assertFalse(line, line.contains("toward"))
    }
}
