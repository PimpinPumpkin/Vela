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
 * A drive off a freeway as Google says it: the exit, then a town turn at the end of the ramp
 * with lines of its own. Sacramento fixture geometry in meters, imperial off, metric lines.
 */
class NavEngineFreewayTest {
    private val lat = 38.58
    private val lng0 = -121.50
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @Before fun setUp() { NavStringsRegistry.setLocale(Locale.US) }
    @After fun reset() { SpokenDetail.mode = SpokenDetail.Mode.FULL }

    /** East 3000 m on the freeway, the exit ramp off it at 2000 m (northeast, [rampM] long), a
     *  right turn onto El Camino Avenue at its end, then east 600 m. */
    private fun route(rampM: Double): Pair<Route, Double> {
        val fwy = (0..200).map { pt(it * 10.0, 0.0) }
        val k = Math.sqrt(0.5)
        val ramp = (1..(rampM / 10).toInt()).map { pt(2000.0 + it * 10.0 * k, it * 10.0 * k) }
        val end = ramp.last()
        val eE = (end.lng - lng0) * mPerDegLng; val eN = (end.lat - lat) * 111_320.0
        val street = (1..60).map { pt(eE + it * 10.0 * k, eN - it * 10.0 * k) }
        val poly = fwy + ramp + street
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east on I 80 Business", poly.first(), 2000.0, 87.0),
            Maneuver(ManeuverType.RAMP_RIGHT, "Take exit 10 toward El Camino Avenue", pt(2000.0, 0.0), rampM, rampM / 12),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto El Camino Avenue", end, 600.0, 50.0, road = "El Camino Avenue", instructionNoRoad = "Turn right"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        val d = poly.zipWithNext { a, b -> a.distanceTo(b) }.sum()
        return Route(poly, listOf(RouteLeg(d, d / 15, null, ms)), d, d / 15, null) to 2000.0 + rampM
    }

    private fun drive(r: Route, turnAt: Double): List<Pair<Double, String>> {
        var st = NavState()
        val said = ArrayList<Pair<Double, String>>()
        val poly = r.polyline
        val cum = RouteProjection.cumulative(poly)
        var m = 0.0
        while (m <= cum.last() - 30.0) {
            // 23 m/s on the freeway, down to 12 on the ramp, 5 at the turn.
            val v = when { m < 2000.0 -> 23.0; m < turnAt - 40.0 -> 12.0; m < turnAt + 20.0 -> 5.0; else -> 12.0 }
            val here = RouteProjection.pointAt(poly, cum, m)
            val ahead = RouteProjection.pointAt(poly, cum, m + 5.0)
            val brg = Math.toDegrees(Math.atan2((ahead.lng - here.lng) * mPerDegLng, (ahead.lat - here.lat) * 111_320.0)).let { (it + 360) % 360 }
            val (s, ev) = NavEngine.update(r, st, here, speedMps = v, bearingDeg = brg, cornerQuiet = true)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += m to it.text }
            if (st.arrived) break
            m += v
        }
        return said
    }

    @Test fun `an exit at freeway speed says Google's two lines and nothing at the gore`() {
        val (r, turnAt) = route(460.0)
        val said = drive(r, turnAt)
        val exit = said.filter { "exit 10" in it.second }
        // Far about 35 s out with its distance (800 m at 23 m/s), near about 10 s out without one
        // and without the sign's tail, then nothing within 60 m of the gore.
        assertEquals("$said", 2, exit.size)
        assertTrue("$said", exit[0].second.startsWith("In 800 meters, Take exit 10") && exit[0].first in 1150.0..1250.0)
        assertEquals("$said", "Take exit 10", exit[1].second)
        assertTrue("$said", exit[1].first in 1700.0..1800.0)
        assertTrue("$said", said.none { it.first in 1940.0..2014.0 })
    }

    @Test fun `the exit's near line names the turn after it only when it is close`() {
        val close = drive(route(320.0).first, 2320.0)
        assertTrue("$close", close.any { it.second == "Take exit 10. Then you'll turn right" })
        val far = drive(route(460.0).first, 2460.0)
        assertTrue("$far", far.none { "Then" in it.second })
    }

    @Test fun `a town turn at the end of an exit ramp still names its street before the turn`() {
        for (rampM in listOf(320.0, 460.0)) {
            val (r, turnAt) = route(rampM)
            val said = drive(r, turnAt)
            val own = said.filter { "El Camino Avenue" in it.second && "exit" !in it.second }
            assertTrue("ramp $rampM: $said", own.any { it.first in (turnAt - 250.0)..(turnAt - 40.0) })
        }
    }
}
