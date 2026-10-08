package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Pass the traffic light, then ..." is said for a light still ahead, and dropped once it is behind. */
class NavEngineLightCueTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)
    private fun northOf(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    /** East 600 m to a left turn, with a light [lightBeforeM] before it, then north 500 m. */
    private fun route(lightBeforeM: Double?): Route {
        val poly = (0..60).map { east(it * 10.0) } + (1..50).map { northOf(600.0, it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 600.0, 0.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto North Road", east(600.0), 500.0, 0.0, lightsBeforeM = listOfNotNull(lightBeforeM)),
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

    @Test fun `a light between the far prompt and the near one is named once`() {
        val said = spokenOnApproach(route(lightBeforeM = 250.0))
        assertEquals("far, near, turn-now: $said", 3, said.size)
        assertTrue("400 m out the light is ahead: ${said[0]}", said[0].contains("Pass the traffic light, then turn left onto North Road"))
        assertTrue("150 m out it is behind: ${said[1]}", !said[1].contains("traffic light") && said[1].contains("Turn left onto North Road"))
        assertEquals("the turn itself is said bare", "Turn left onto North Road", said[2])
    }

    @Test fun `a light close to the turn is named on both approach prompts`() {
        val said = spokenOnApproach(route(lightBeforeM = 80.0))
        assertTrue(said[0].contains("Pass the traffic light, then"))
        assertTrue(said[1].contains("Pass the traffic light, then"))
        assertTrue(!said[2].contains("traffic light"))
    }

    @Test fun `a route with no marked light speaks as before`() {
        val said = spokenOnApproach(route(lightBeforeM = null))
        assertTrue(said.none { it.contains("traffic light") })
        assertEquals(3, said.size)
    }

    @Test fun `the prepared lines are the ones that will be spoken`() {
        val r = route(lightBeforeM = 80.0)
        val ready = NavEngine.upcomingPrompts(r, fromStep = 1, spoken = emptySet(), speedMps = 10.0, imperial = false)
        val said = spokenOnApproach(r)
        assertTrue("prepared $ready, spoken $said", ready.containsAll(said))
    }
}
