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

/** How many lines the full voice says for an exit, the fork on its ramp and the merge after it,
 *  driven at a steady pace with a fix a second. At highway pace each exit or fork gets one line a
 *  quarter mile out and one at the turn, and a merge is said once. */
class HighwayVoiceTest {
    private val o = LatLng(38.40, -121.70)
    private fun at(m: Double) = LatLng(o.lat + m / 111_320.0, o.lng)

    private val right2 = listOf(
        Lane(listOf("straight"), valid = false), Lane(listOf("straight"), valid = false),
        Lane(listOf("slight right"), valid = true), Lane(listOf("slight right"), valid = true),
    )
    private val leftLane = listOf(Lane(listOf("slight left"), valid = true), Lane(listOf("slight right"), valid = false))

    private fun route(total: Double, steps: List<Maneuver>): Route {
        val line = (0..(total / 50.0).toInt()).map { at(it * 50.0) }
        val firstM = (steps.first().location.lat - o.lat) * 111_320.0
        val ms = listOf(Maneuver(ManeuverType.DEPART, "Head north", at(0.0), firstM, 60.0)) + steps +
            Maneuver(ManeuverType.ARRIVE, "Arrive", at(total), 0.0, 0.0)
        return Route(polyline = line, legs = listOf(RouteLeg(total, total / 25.0, null, ms)), distanceMeters = total, durationSeconds = total / 25.0, durationInTrafficSeconds = null)
    }

    /** Drives [r] from [fromM] to [toM] at [mps], one fix a second; each spoken line with the step it belonged to. */
    private fun drive(r: Route, fromM: Double, toM: Double, mps: Double, startStep: Int = 1): List<Pair<Int, NavEvent.Speak>> {
        var state = NavState(stepIndex = startStep, traveledM = fromM)
        val out = ArrayList<Pair<Int, NavEvent.Speak>>()
        var m = fromM
        while (m <= toM) {
            val step = state.stepIndex
            val (next, events) = NavEngine.update(r, state, at(m), imperial = true, speedMps = mps)
            events.filterIsInstance<NavEvent.Speak>().forEach { out += step to it }
            state = next
            m += mps
        }
        return out
    }

    @Test fun `an exit, the fork on its ramp and the merge after it take four lines at highway pace`() {
        val r = route(
            8000.0,
            listOf(
                Maneuver(ManeuverType.RAMP_RIGHT, "Take exit 12 toward I 80 West: Sacramento", at(3000.0), 1700.0, 60.0, lanes = right2),
                Maneuver(ManeuverType.FORK_RIGHT, "Keep slight right toward I 80 West: Sacramento", at(4700.0), 450.0, 16.0, lanes = right2),
                Maneuver(ManeuverType.MERGE, "Merge onto I 80 West", at(5150.0), 2850.0, 100.0, road = "I 80 West"),
            ),
        )
        val lines = drive(r, 500.0, 6000.0, 29.0)
        assertEquals(lines.joinToString("\n") { "${it.first}: ${it.second.text}" }, 4, lines.size)
        val (exitFar, exitNow, forkFar, forkNow) = lines.map { it.second }
        assertTrue(exitFar.text, exitFar.text.startsWith("In a quarter mile, Use the right 2 lanes to take exit 12 toward I 80 West"))
        assertTrue(exitNow.interrupt)
        assertTrue(exitNow.text, exitNow.text.startsWith("Take exit 12"))
        assertTrue(forkFar.text, forkFar.text.startsWith("In a quarter mile, Use the right 2 lanes to keep slight right"))
        assertEquals("Keep slight right toward I 80 West, then merge onto I 80 West", forkNow.text)
        assertTrue(lines.none { it.first == 3 })
    }

    @Test fun `the lines prepared for an exit at highway pace are the lines said`() {
        val r = route(
            6000.0,
            listOf(Maneuver(ManeuverType.RAMP_RIGHT, "Take exit 12 toward I 80 West: Sacramento", at(3000.0), 3000.0, 100.0, lanes = right2)),
        )
        val prepared = NavEngine.upcomingPrompts(r, 1, emptySet(), 29.0, imperial = true, count = 1, nearestFirst = false)
        val said = drive(r, 500.0, 3100.0, 29.0).map { it.second.text }
        assertEquals(said, prepared)
    }

    @Test fun `a fork just past an exit is said with the exit, lanes and all`() {
        val r = route(
            6000.0,
            listOf(
                Maneuver(ManeuverType.RAMP_RIGHT, "Take the exit toward US 50 East", at(3000.0), 260.0, 12.0),
                Maneuver(ManeuverType.FORK_LEFT, "Keep slight left toward US 50 East: Sacramento", at(3260.0), 2740.0, 100.0, lanes = leftLane),
            ),
        )
        val lines = drive(r, 1500.0, 3500.0, 22.0).map { it.second.text }
        assertEquals(lines.joinToString("\n"), 3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("In a quarter mile, Take the exit"))
        assertEquals("Take the exit toward US 50 East, then use the left lane to keep slight left", lines[1])
        assertTrue(lines[2], lines[2].startsWith("Keep slight left toward US 50 East"))
    }

    @Test fun `an on-ramp says its merge as its then and nothing at the merge`() {
        val r = route(
            4000.0,
            listOf(
                Maneuver(ManeuverType.RAMP_RIGHT, "Take the ramp on the right toward CA 113 North", at(1500.0), 400.0, 30.0),
                Maneuver(ManeuverType.MERGE, "Merge onto CA 113 North", at(1900.0), 2100.0, 80.0, road = "CA 113 North"),
            ),
        )
        // At 12 m/s, under EXIT_ONE_LINE_MPS: the ramp keeps its far and near lines.
        val lines = drive(r, 900.0, 2100.0, 12.0)
        assertEquals(lines.joinToString("\n") { "${it.first}: ${it.second.text}" }, 3, lines.size)
        assertEquals("Take the ramp on the right toward CA 113 North, then merge onto CA 113 North", lines.last().second.text)
        assertTrue(lines.none { it.first == 2 })
    }

    @Test fun `the turn onto an on-ramp at 40 mph is said like an exit`() {
        val r = route(
            4000.0,
            listOf(
                Maneuver(ManeuverType.TURN_LEFT, "Take the ramp on the left toward I 80", at(1500.0), 350.0, 25.0, lanes = leftLane),
                Maneuver(ManeuverType.MERGE, "Merge onto I 80", at(1850.0), 2150.0, 80.0, ref = "I 80"),
            ),
        )
        val lines = drive(r, 500.0, 2000.0, 18.0).map { it.second.text }
        assertEquals(lines.joinToString("\n"), 2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("In a quarter mile, Use the left lane to take the ramp toward I 80"))
        assertEquals("Take the ramp on the left, then merge onto I 80", lines[1])
    }

    @Test fun `a turn in town keeps its far, near and turn lines`() {
        val r = route(
            3000.0,
            listOf(Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Covell Boulevard", at(1500.0), 1500.0, 100.0, road = "Covell Boulevard")),
        )
        val lines = drive(r, 600.0, 1600.0, 13.0).map { it.second.text }
        assertEquals(lines.joinToString("\n"), 3, lines.size)
        assertFalse(lines[0], lines[0].startsWith("Turn"))
        assertTrue(lines[2], lines[2].startsWith("Turn left"))
    }
}
