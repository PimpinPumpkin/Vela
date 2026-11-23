package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepFitTest {
    private val lat = 38.5449
    private val lng0 = -121.7405 // Davis fixture
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    /** East [eastM], then right (south) [southM], a vertex every 10 m. */
    private fun rightTurnLine(eastM: Double = 400.0, southM: Double = 300.0) =
        (0..(eastM / 10).toInt()).map { at(it * 10.0, 0.0) } + (1..(southM / 10).toInt()).map { at(eastM, -it * 10.0) }

    private fun m(type: ManeuverType, p: LatLng, dist: Double, text: String, road: String? = null) =
        Maneuver(type, text, p, dist, dist / 10.0, road = road, instructionNoRoad = text.substringBefore(" onto"))

    private fun route(line: List<LatLng>, ms: List<Maneuver>): Route {
        val d = ms.sumOf { it.distanceMeters }
        return Route(line, listOf(RouteLeg(d, d / 10.0, null, ms)), d, d / 10.0, null)
    }

    @Test fun `a turn on its corner is left alone`() {
        val r = route(rightTurnLine(), listOf(
            m(ManeuverType.DEPART, at(0.0, 0.0), 400.0, "Head east on Elm Street", "Elm Street"),
            m(ManeuverType.TURN_RIGHT, at(400.0, 0.0), 300.0, "Turn right onto Oak Avenue", "Oak Avenue"),
            m(ManeuverType.ARRIVE, at(400.0, -300.0), 0.0, "Arrive"),
        ))
        val f = StepFit.fit(r)
        assertFalse(f.changed)
        assertTrue(f.route === r)
    }

    @Test fun `a bare turn placed short of its corner moves onto it`() {
        val r = route(rightTurnLine(), listOf(
            m(ManeuverType.DEPART, at(0.0, 0.0), 364.0, "Head east on Elm Street", "Elm Street"),
            m(ManeuverType.TURN_RIGHT, at(364.0, 0.0), 336.0, "Turn right"),
            m(ManeuverType.ARRIVE, at(400.0, -300.0), 0.0, "Arrive"),
        ))
        val f = StepFit.fit(r)
        assertEquals(1, f.moved)
        val ms = f.route.maneuvers
        assertEquals(3, ms.size)
        val cum = RouteProjection.cumulative(r.polyline)
        assertEquals(400.0, NavEngine.placeSteps(f.route, cum)[1], 4.0)
        assertEquals(400.0, ms[0].distanceMeters, 4.0)
        assertEquals(700.0, ms.sumOf { it.distanceMeters }, 0.01)
    }

    @Test fun `bare turns where the line goes straight are dropped, a named one is kept`() {
        val line = (0..60).map { at(it * 10.0, 0.0) }
        val r = route(line, listOf(
            m(ManeuverType.DEPART, at(0.0, 0.0), 200.0, "Head east"),
            m(ManeuverType.TURN_RIGHT, at(200.0, 0.0), 1.0, "Turn right"),
            m(ManeuverType.TURN_LEFT, at(201.0, 0.0), 199.0, "Turn left"),
            m(ManeuverType.TURN_LEFT, at(400.0, 0.0), 200.0, "Turn left onto Oak Avenue", "Oak Avenue"),
            m(ManeuverType.ARRIVE, at(600.0, 0.0), 0.0, "Arrive"),
        ))
        val f = StepFit.fit(r)
        assertEquals(2, f.dropped)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), f.route.maneuvers.map { it.type })
        assertEquals("Oak Avenue", f.route.maneuvers[1].road)
        assertEquals(400.0, f.route.maneuvers[0].distanceMeters, 0.01)
        assertEquals(600.0, f.route.maneuvers.sumOf { it.distanceMeters }, 0.01)
    }

    @Test fun `a lot entrance with steps off its bends keeps one turn on each bend`() {
        // East 400 m, right into the lot, 130 m down the aisle, right again, 50 m to the spot. The
        // steps were a right and a left 36 m short of the entrance, a right and a left 62 m past
        // it, and the last right on its bend, as on a recorded arrival.
        val line = (0..40).map { at(it * 10.0, 0.0) } + (1..13).map { at(400.0, -it * 10.0) } + (1..5).map { at(400.0 - it * 10.0, -130.0) }
        val cum = RouteProjection.cumulative(line)
        fun p(a: Double) = RouteProjection.pointAt(line, cum, a)
        val r = route(line, listOf(
            m(ManeuverType.DEPART, p(0.0), 364.0, "Head east on Elm Street", "Elm Street"),
            m(ManeuverType.TURN_RIGHT, p(364.0), 1.0, "Turn right"),
            m(ManeuverType.TURN_LEFT, p(365.0), 97.0, "Turn left"),
            m(ManeuverType.TURN_RIGHT, p(462.0), 0.0, "Turn right"),
            m(ManeuverType.TURN_LEFT, p(462.0), 68.0, "Turn left"),
            m(ManeuverType.TURN_RIGHT, p(530.0), 50.0, "Turn right"),
            m(ManeuverType.ARRIVE, p(580.0), 0.0, "Arrive"),
        ))
        val f = StepFit.fit(r)
        val ms = f.route.maneuvers
        val placed = NavEngine.placeSteps(f.route, cum)
        assertEquals("${ms.map { it.type }} at ${placed.map { it.toInt() }} ${f.note}", listOf(ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), ms.map { it.type })
        assertEquals(400.0, placed[1], 5.0)
        assertEquals(530.0, placed[2], 5.0)
        assertEquals(580.0, ms.sumOf { it.distanceMeters }, 0.01)
        assertEquals(1, f.moved)
        assertEquals(3, f.dropped)
    }

    @Test fun `a bare turn and the road it lands on are said as one`() {
        val r = route(rightTurnLine(), listOf(
            m(ManeuverType.DEPART, at(0.0, 0.0), 400.0, "Take the exit"),
            m(ManeuverType.TURN_RIGHT, at(400.0, 0.0), 42.0, "Turn right"),
            m(ManeuverType.STRAIGHT, at(400.0, -42.0), 258.0, "Continue onto Oak Avenue", "Oak Avenue"),
            m(ManeuverType.ARRIVE, at(400.0, -300.0), 0.0, "Arrive"),
        ))
        val f = StepFit.fit(r)
        assertEquals(1, f.onto)
        val ms = f.route.maneuvers
        assertEquals(3, ms.size)
        assertEquals(ManeuverType.TURN_RIGHT, ms[1].type)
        assertEquals("Oak Avenue", ms[1].road)
        assertTrue(ms[1].instruction, "Oak Avenue" in ms[1].instruction)
        assertEquals(300.0, ms[1].distanceMeters, 0.01)
        assertTrue(f.route.made.contains("fit="))
    }
}
