package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Go past this light, then ..." for one light just before the turn, on the near line only, never for one far before it. */
class NavEngineLightCueTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)
    private fun northOf(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    /** East 600 m to a left turn, with a light [lightBeforeM] before it, then north 500 m. */
    private fun route(lightBeforeM: Double?, turn: Maneuver.() -> Maneuver = { this }): Route {
        val poly = (0..60).map { east(it * 10.0) } + (1..50).map { northOf(600.0, it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 600.0, 0.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto North Road", east(600.0), 500.0, 0.0, lightsBeforeM = listOfNotNull(lightBeforeM)).turn(),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(1100.0, 120.0, null, ms)), 1100.0, 120.0, null)
    }

    /** Drives east at 10 m/s up to the corner and returns every line spoken about the turn. */
    private fun spokenOnApproach(r: Route): List<String> {
        var st = NavState()
        val said = ArrayList<String>()
        var m = 0.0
        while (m <= 580.0) {
            val (s, ev) = NavEngine.update(r, st, east(m), speedMps = 10.0, bearingDeg = 90.0)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
            m += 10.0
        }
        return said
    }

    @Test fun `a light more than 200 m before the turn is not named`() {
        // Side streets lie between it and the turn: "go past this light, then turn left" would
        // send a driver into the first of them.
        val said = spokenOnApproach(route(lightBeforeM = 250.0))
        assertEquals("far, near, turn-now: $said", 3, said.size)
        assertTrue("$said", said.none { it.contains("light") })
    }

    @Test fun `a light just before the turn is named on the near line only`() {
        val said = spokenOnApproach(route(lightBeforeM = 80.0))
        assertEquals("far, near, turn-now: $said", 3, said.size)
        assertTrue(said[0], !said[0].contains("light"))
        assertTrue(said[1], said[1].contains("Go past this light, then turn left onto North Road"))
        assertTrue(said[2], !said[2].contains("light"))
    }

    @Test fun `a turn at its own light says so near the turn and at it, even with a light before it`() {
        val said = spokenOnApproach(route(lightBeforeM = 80.0) { copy(lightAtTurn = true) })
        assertEquals("far, near, turn-now: $said", 3, said.size)
        assertTrue("no light on the far line: ${said[0]}", !said[0].contains("light"))
        assertTrue(said[1], said[1].contains("At the light, turn left onto North Road"))
        assertTrue(said[2], said[2].startsWith("At the light, turn left"))
    }

    @Test fun `a turn at a stop sign is the next stop sign from far out, then the stop sign`() {
        val said = spokenOnApproach(route(lightBeforeM = null) { copy(stopAtTurn = true) })
        assertEquals("far, near, turn-now: $said", 3, said.size)
        assertEquals("the sign stands in for the distance", "At the next stop sign, turn left onto North Road", said[0])
        assertTrue(said[1], said[1].contains("At the stop sign, turn left onto North Road"))
        assertTrue(said[2], said[2].startsWith("At the stop sign, turn left"))
    }

    @Test fun `another stop sign first keeps the far line's distance`() {
        val said = spokenOnApproach(route(lightBeforeM = null) { copy(stopAtTurn = true, stopsBeforeM = listOf(300.0)) })
        assertTrue(said[0], said[0].startsWith("In ") && !said[0].contains("stop sign"))
        assertTrue(said[1], said[1].contains("At the stop sign, turn left onto North Road"))
    }

    @Test fun `a route with no marked light speaks as before`() {
        val said = spokenOnApproach(route(lightBeforeM = null))
        assertTrue(said.none { it.contains("light") })
        assertEquals(3, said.size)
    }

    @Test fun `the prepared lines are the ones that will be spoken`() {
        for (r in listOf(route(lightBeforeM = 80.0), route(80.0) { copy(lightAtTurn = true) }, route(null) { copy(stopAtTurn = true) })) {
            val ready = NavEngine.upcomingPrompts(r, fromStep = 1, spoken = emptySet(), speedMps = 10.0, imperial = false)
            val said = spokenOnApproach(r)
            assertTrue("prepared $ready, spoken $said", ready.containsAll(said))
        }
    }
}
