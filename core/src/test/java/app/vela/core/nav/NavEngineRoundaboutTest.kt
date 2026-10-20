package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two roundabouts 120 m apart, each an entry step and an exit step with the same words. */
class NavEngineRoundaboutTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double) = LatLng(lat, lng0 + e / mPerDegLng)

    private val first = "At the roundabout, take the 2nd exit onto Elm Street"
    private val second = "At the roundabout, go straight through onto Oak Avenue, the 2nd exit"

    // Drawn straight east: the voice only reads distances along the line.
    private fun route(): Route {
        val poly = (0..200).map { pt(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", pt(0.0), 1000.0, 100.0),
            Maneuver(ManeuverType.ROUNDABOUT, first, pt(1000.0), 30.0, 3.0, road = "Elm Street"),
            Maneuver(ManeuverType.EXIT_ROUNDABOUT, first, pt(1030.0), 120.0, 12.0, road = "Elm Street"),
            Maneuver(ManeuverType.ROUNDABOUT, second, pt(1150.0), 30.0, 3.0, road = "Oak Avenue"),
            Maneuver(ManeuverType.EXIT_ROUNDABOUT, second, pt(1180.0), 820.0, 82.0, road = "Oak Avenue"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", pt(2000.0), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2000.0, 200.0, null, ms)), 2000.0, 200.0, null)
    }

    private fun drive(r: Route): List<Pair<Double, NavEvent.Speak>> {
        var st = NavState()
        val said = ArrayList<Pair<Double, NavEvent.Speak>>()
        var m = 0.0
        while (m <= 1900.0) {
            val (s, ev) = NavEngine.update(r, st, pt(m), speedMps = 8.0, bearingDeg = 90.0)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += m to it }
            m += 4.0
        }
        return said
    }

    @Test fun `each roundabout is said for its entry and never again at its exit`() {
        val said = drive(route())
        val texts = said.map { it.second.text }
        // The line at the first entry does not name the same roundabout as its "then".
        assertTrue("$texts", texts.none { it.contains("$first, then") })
        // Nothing is said between the first ring's entry and the second ring's approach, and
        // nothing inside the second ring after its entry line.
        val atFirst = said.indexOfFirst { it.second.text.startsWith(first) }
        val atSecond = said.indexOfLast { it.second.text.startsWith(second) }
        assertTrue("$texts", atFirst >= 0 && atSecond > atFirst)
        assertEquals("$texts", 0, said.count { it.first in 1031.0..1180.0 && it.second.text.startsWith(first) })
        assertEquals("$texts", 0, said.drop(atSecond + 1).count { "roundabout" in it.second.text })
    }

    @Test fun `a route that starts inside the ring still says its exit`() {
        val r = route()
        assertTrue(NavEngine.ringExit(r.maneuvers, 2))
        val inside = r.maneuvers.drop(2)
        val ms = listOf(r.maneuvers[0]) + inside
        assertTrue(!NavEngine.ringExit(ms, 1))
    }
}
