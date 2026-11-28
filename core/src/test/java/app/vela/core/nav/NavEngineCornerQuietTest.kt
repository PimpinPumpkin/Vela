package app.vela.core.nav

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * A drive's town turns in Google's rhythm (NavEngine.update's cornerQuiet): a far line with its
 * distance, a near line with none, and nothing at the corner. The near line names a turn close after, and
 * that turn's own line comes once the corner is made. Davis fixture, metric, 10 m/s.
 */
class NavEngineCornerQuietTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @Before fun setUp() { NavStringsRegistry.setLocale(Locale.US) }
    @After fun reset() { SpokenDetail.mode = SpokenDetail.Mode.FULL }

    /** East 1500 m, [type] onto North Road, north [northM], then (with [second]) a right onto Elm
     *  Street and east 400 m. */
    private fun route(type: ManeuverType = ManeuverType.TURN_LEFT, northM: Double = 500.0, second: Boolean = false): Route {
        val east = (0..150).map { pt(it * 10.0, 0.0) }
        val north = (1..(northM / 10).toInt()).map { pt(1500.0, it * 10.0) }
        val east2 = if (second) (1..40).map { pt(1500.0 + it * 10.0, northM) } else emptyList()
        val poly = east + north + east2
        val ms = buildList {
            add(Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1500.0, 150.0))
            val first = when (type) {
                ManeuverType.RAMP_RIGHT -> "Take the ramp on the right toward I 80 East"
                else -> "Turn left onto North Road"
            }
            add(Maneuver(type, first, pt(1500.0, 0.0), northM, northM / 10, road = "North Road", instructionNoRoad = "Turn left"))
            if (second) add(Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm Street", pt(1500.0, northM), 400.0, 40.0, road = "Elm Street", instructionNoRoad = "Turn right"))
            add(Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0))
        }
        val d = poly.zipWithNext { a, b -> a.distanceTo(b) }.sum()
        return Route(poly, listOf(RouteLeg(d, d / 10, null, ms)), d, d / 10, null)
    }

    /** Drives [r] at 10 m/s along its line, a fix every 5 m; each line with the meters driven. */
    private fun drive(r: Route, quiet: Boolean): List<Pair<Double, String>> {
        var st = NavState()
        val said = ArrayList<Pair<Double, String>>()
        val poly = r.polyline
        val cum = RouteProjection.cumulative(poly)
        var m = 0.0
        while (m <= cum.last() - 30.0) {
            val here = RouteProjection.pointAt(poly, cum, m)
            val ahead = RouteProjection.pointAt(poly, cum, m + 5.0)
            val brg = Math.toDegrees(Math.atan2((ahead.lng - here.lng) * mPerDegLng, (ahead.lat - here.lat) * 111_320.0)).let { (it + 360) % 360 }
            val (s, ev) = NavEngine.update(r, st, here, speedMps = 10.0, bearingDeg = brg, cornerQuiet = quiet)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += m to it.text }
            if (st.arrived) break
            m += 5.0
        }
        return said
    }

    @Test fun `a town turn says a far and a near line and nothing at the corner`() {
        val quiet = drive(route(), quiet = true)
        val turnLines = quiet.filter { "North Road" in it.second }
        assertEquals("$quiet", listOf("In 400 meters, Turn left onto North Road", "Turn left onto North Road"), turnLines.map { it.second })
        assertTrue("$quiet", quiet.none { it.first in 1460.0..1530.0 })
        // Without it (walking, cycling) the line at the turn stays.
        val loud = drive(route(), quiet = false)
        assertTrue("$loud", loud.any { it.second == "Turn left onto North Road" && it.first > 1450.0 })
    }

    @Test fun `the near line names a turn close after, and that turn speaks once the corner is made`() {
        val said = drive(route(northM = 100.0, second = true), quiet = true)
        val near = said.first { it.second.startsWith("Turn left onto North Road") }.second
        assertTrue(near, "then" in near && "Elm Street" in near)
        // Nothing inside either corner: from 30 m before the first to 15 m past it, and from 25 m
        // before the second.
        assertTrue("$said", said.none { it.first in 1470.0..1514.0 })
        assertTrue("$said", said.none { it.first in 1575.0..1610.0 })
        // The second turn's own line comes after the first corner, before its own.
        val own = said.filter { it.second.contains("Turn right onto Elm Street") && !it.second.contains("North Road") }
        assertEquals("$said", 1, own.size)
        assertTrue("$said", own[0].first in 1515.0..1575.0)
    }

    @Test fun `a ramp keeps its line at the turn`() {
        val said = drive(route(type = ManeuverType.RAMP_RIGHT), quiet = true)
        assertTrue("$said", said.any { it.first > 1450.0 && it.second.startsWith("Take the ramp") && !it.second.startsWith("In ") })
    }

    @Test fun `the voice prepares the near line with the turn after, not a line at the corner`() {
        val r = route(northM = 100.0, second = true)
        val lines = NavEngine.upcomingPrompts(r, fromStep = 1, spoken = emptySet(), speedMps = 10.0, imperial = false, nearestFirst = false, cornerQuiet = true)
        // The far line, then the near line with no distance and the turn after: nothing else
        // names North Road, so nothing is prepared for the corner.
        assertEquals("$lines", listOf("In 400 meters, Turn left onto North Road, then turn right onto Elm Street", "Turn left onto North Road, then turn right onto Elm Street"),
            lines.filter { "North Road" in it }.distinct())
    }

    @Test fun `a town turn named on the line at a fork just before it still gets its own line`() {
        // East 1000 m, keep slight left (a bend 20 degrees north of east) for 44 m, a left onto
        // Oak Avenue, north 400 m.
        val bend = 20.0 * Math.PI / 180
        val east = (0..100).map { pt(it * 10.0, 0.0) }
        val fork = (1..4).map { pt(1000.0 + it * 11.0 * Math.cos(bend), it * 11.0 * Math.sin(bend)) }
        val corner = fork.last()
        val cE = (corner.lng - lng0) * mPerDegLng
        val cN = (corner.lat - lat) * 111_320.0
        val north = (1..40).map { pt(cE, cN + it * 10.0) }
        val poly = east + fork + north
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1000.0, 100.0),
            Maneuver(ManeuverType.FORK_LEFT, "Keep slight left", pt(1000.0, 0.0), 44.0, 5.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", corner, 400.0, 40.0, road = "Oak Avenue", instructionNoRoad = "Turn left"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        val d = poly.zipWithNext { a, b -> a.distanceTo(b) }.sum()
        val said = drive(Route(poly, listOf(RouteLeg(d, d / 10, null, ms)), d, d / 10, null), quiet = true)
        val own = said.filter { "Oak Avenue" in it.second && "Keep" !in it.second && !it.second.startsWith("In 400") }
        assertTrue("$said", own.any { it.first >= 975.0 })
    }
}
