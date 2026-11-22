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

    @Test fun `each roundabout is said before its entry and never again after it`() {
        val said = drive(route())
        val texts = said.map { it.second.text }
        // The line at the first entry does not name the same roundabout as its "then".
        assertTrue("$texts", texts.none { it.contains("$first, then") })
        val firstSaid = said.filter { first in it.second.text }
        val secondSaid = said.filter { second in it.second.text }
        assertTrue("$texts", firstSaid.isNotEmpty() && secondSaid.isNotEmpty())
        assertTrue("$texts", firstSaid.all { it.first < 1000.0 })
        assertTrue("$texts", secondSaid.all { it.first < 1150.0 })
        // Nothing about either ring once the car is in the second one.
        assertEquals("$texts", 0, said.count { it.first >= 1150.0 && "roundabout" in it.second.text })
    }

    @Test fun `a route that starts inside the ring still says its exit`() {
        val r = route()
        assertTrue(NavEngine.ringExit(r.maneuvers, 2))
        val inside = r.maneuvers.drop(2)
        val ms = listOf(r.maneuvers[0]) + inside
        assertTrue(!NavEngine.ringExit(ms, 1))
    }

    /** One roundabout 1000 m out, then 970 m to the arrival. */
    private fun single(): Route {
        val poly = (0..200).map { pt(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", pt(0.0), 1000.0, 100.0),
            Maneuver(ManeuverType.ROUNDABOUT, first, pt(1000.0), 30.0, 3.0, road = "Elm Street"),
            Maneuver(ManeuverType.EXIT_ROUNDABOUT, first, pt(1030.0), 970.0, 97.0, road = "Elm Street"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", pt(2000.0), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2000.0, 200.0, null, ms)), 2000.0, 200.0, null)
    }

    @Test fun `a roundabout said on its approach is not said again at its entry`() {
        val r = single()
        var st = NavState()
        val said = ArrayList<Pair<Double, String>>()
        var buzzAtEntry = false
        var m = 0.0
        while (m <= 1900.0) {
            val (s, ev) = NavEngine.update(r, st, pt(m), speedMps = 13.0, bearingDeg = 90.0)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += m to it.text }
            if (ev.any { it is NavEvent.Haptic && !it.approaching && it.type == ManeuverType.ROUNDABOUT }) buzzAtEntry = true
            m += 4.0
        }
        val ring = said.filter { "roundabout" in it.second }
        assertEquals("$said", 2, ring.size)
        assertTrue("both are approach lines: $said", ring.all { it.second.startsWith("In ") })
        assertTrue("the entry still buzzes", buzzAtEntry)
    }
}
