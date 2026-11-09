package app.vela.core.nav

import app.vela.core.data.RouteGeometry
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Forks and ramps name the road they head for, and a fork that keeps the car on its road says so. */
class ForkRouteNamesTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double) = LatLng(lat, lng0 + e / mPerDegLng)
    private val en get() = NavStringsRegistry.current()

    @After fun reset() = NavStringsRegistry.setLocale(Locale.US)

    @Test fun `a route on a sign is read whatever way it is written`() {
        assertEquals("I" to "80", routeKey("I 80 West"))
        assertEquals("I" to "80", routeKey("I-80"))
        assertEquals("I" to "80", routeKey("Interstate 80"))
        assertEquals("US" to "50", routeKey("US 50 East"))
        assertEquals("S" to "113", routeKey("CA 113 North"))
        assertEquals("S" to "99", routeKey("SR 99"))
        assertEquals("S" to "1", routeKey("Highway 1"))
        assertNull(routeKey("Sacramento"))
        assertNull(routeKey("NE 8th Street"))
        assertNull(routeKey("Richards Boulevard"))
    }

    @Test fun `the short repeat of a fork or ramp keeps its first route`() {
        assertEquals("Keep slight right toward I 80 West", en.repeatWithRoute("Keep slight right toward Davis, I 80 West: San Francisco"))
        assertEquals("Take exit 72B toward CA 113 North", en.repeatWithRoute("Take exit 72B toward CA 113 North: Woodland"))
        // A sign with places only says no more than before.
        assertEquals("Take the ramp", en.repeatWithRoute("Take the ramp toward Richards Boulevard"))
        assertEquals("Keep left", en.repeatWithRoute("Keep left"))
    }

    private fun steps(forkSign: String, onRef: String?) = listOf(
        Maneuver(ManeuverType.DEPART, "Head west", pt(0.0), 500.0, 30.0),
        Maneuver(ManeuverType.MERGE, "Merge onto I 80 West", pt(500.0), 2000.0, 80.0, road = "I 80", ref = onRef),
        Maneuver(ManeuverType.FORK_LEFT, "Keep left toward $forkSign", pt(2500.0), 1000.0, 40.0, instructionNoRoad = "Keep left"),
        Maneuver(ManeuverType.ARRIVE, "Arrive", pt(3500.0), 0.0, 0.0),
    )

    @Test fun `a fork toward the road the car is on says to stay on it`() {
        val out = RouteGeometry.stayOnForks(steps("I 80 West, San Francisco: Vallejo", "I 80"))
        assertEquals("Keep left to stay on I 80 West", out[2].instruction)
        assertEquals("the no-street form is untouched", "Keep left", out[2].instructionNoRoad)
    }

    @Test fun `a fork toward another route keeps its sign`() {
        val out = RouteGeometry.stayOnForks(steps("US 50 East, Sacramento", "I 80"))
        assertEquals("Keep left toward US 50 East, Sacramento", out[2].instruction)
        // A route named only by the road name counts as well.
        val byName = RouteGeometry.stayOnForks(steps("I 80 West", null).let { s ->
            s.toMutableList().also { it[1] = it[1].copy(road = "Interstate 80") }
        })
        assertEquals("Keep left to stay on I 80 West", byName[2].instruction)
    }

    @Test fun `a language with no stay-on line keeps its text`() {
        NavStringsRegistry.setLocale(Locale.forLanguageTag("sv"))
        val s = steps("I 80 West", "I 80")
        assertEquals(s[2].instruction, RouteGeometry.stayOnForks(s)[2].instruction)
    }

    @Test fun `the line at a fork names the route the banner shows`() {
        val poly = (0..300).map { pt(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", pt(0.0), 2000.0, 80.0),
            Maneuver(ManeuverType.FORK_RIGHT, "Keep right toward US 50 East, Sacramento: Placerville", pt(2000.0), 1000.0, 40.0, instructionNoRoad = "Keep right"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", pt(3000.0), 0.0, 0.0),
        )
        val r = Route(poly, listOf(RouteLeg(3000.0, 120.0, null, ms)), 3000.0, 120.0, null)
        var st = NavState()
        val said = ArrayList<String>()
        var m = 0.0
        while (m <= 2900.0) {
            val (s, ev) = NavEngine.update(r, st, pt(m), speedMps = 25.0, bearingDeg = 90.0)
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
            st = s
            m += 25.0
        }
        val fork = said.filter { "Keep right" in it }
        assertTrue("$said", fork.size >= 2)
        // The first line says the whole sign, the approach repeat says none of it, and the line at
        // the fork names the route.
        assertTrue("$said", "US 50 East" in fork.first() && "Sacramento" in fork.first())
        assertEquals("$said", "Keep right toward US 50 East", fork.last())
        assertTrue("$said", fork.drop(1).dropLast(1).all { "US 50" !in it })
    }

    @Test fun `two forks toward the same route name it once`() {
        val poly = (0..300).map { pt(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", pt(0.0), 2000.0, 80.0),
            Maneuver(ManeuverType.FORK_LEFT, "Keep slight left toward I 80 West", pt(2000.0), 100.0, 5.0, instructionNoRoad = "Keep slight left"),
            Maneuver(ManeuverType.FORK_LEFT, "Keep slight left toward I 80 West: Vallejo", pt(2100.0), 900.0, 40.0, instructionNoRoad = "Keep slight left"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", pt(3000.0), 0.0, 0.0),
        )
        val r = Route(poly, listOf(RouteLeg(3000.0, 130.0, null, ms)), 3000.0, 130.0, null)
        var st = NavState()
        val said = ArrayList<String>()
        var m = 0.0
        while (m <= 2900.0) {
            val (s, ev) = NavEngine.update(r, st, pt(m), speedMps = 15.0, bearingDeg = 90.0)
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
            st = s
            m += 15.0
        }
        assertTrue("$said", said.none { it.split("I 80 West").size > 2 })
        assertTrue("$said", said.any { it == "Keep slight left toward I 80 West, then keep slight left" })
    }
}
