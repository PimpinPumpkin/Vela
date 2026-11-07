package app.vela.core.nav

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The arrival's side when the router left it out. */
class ArrivalSideTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / mPerDegLng)

    @Before fun english() = NavStringsRegistry.setLocale(Locale.US)

    // Due east for 400 m, ending in a bare arrival, as the on-phone router and Google's named line build it.
    private fun route(side: String? = null): Route {
        val poly = (0..40).map { pt(it * 10.0, 0.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 400.0, 40.0),
            Maneuver(ManeuverType.ARRIVE, "You have arrived", poly.last(), 0.0, 0.0, side = side),
        )
        return Route(poly, listOf(RouteLeg(400.0, 40.0, null, ms)), 400.0, 40.0, null)
    }

    /** Every spoken line driving the route east at 10 m/s to the end. */
    private fun said(r: Route): List<String> {
        var st = NavState()
        val out = ArrayList<String>()
        for (m in generateSequence(0.0) { it + 10.0 }.takeWhile { it <= 400.0 }) {
            val (s, ev) = NavEngine.update(r, st, pt(m, 0.0), speedMps = 10.0, bearingDeg = 90.0)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { out += it.text }
            if (st.arrived) break
        }
        return out
    }

    @Test fun `the approach says the side the arrival says`() {
        val right = said(NavEngine.withArrivalSide(route(), pt(395.0, -25.0)))
        assertTrue(right.toString(), right.any { it.endsWith("Your destination will be on the right") })
        assertTrue(right.toString(), right.none { "ahead" in it })
        assertEquals("Your destination is on the right", right.last())
        // No side known: the approach stays "ahead" and the arrival is the plain line.
        val none = said(route())
        assertTrue(none.toString(), none.any { it.endsWith("Your destination will be ahead") })
    }

    @Test fun `a pin north of an eastbound end is on the left`() {
        val r = NavEngine.withArrivalSide(route(), pt(400.0, 30.0))
        assertEquals("left", r.maneuvers.last().side)
        assertEquals("Your destination is on the left", r.maneuvers.last().instruction)
        assertEquals("right", NavEngine.withArrivalSide(route(), pt(395.0, -25.0)).maneuvers.last().side)
    }

    @Test fun `a router's own side and a pin on the line are left alone`() {
        val sided = route(side = "right")
        assertSame(sided, NavEngine.withArrivalSide(sided, pt(400.0, 30.0)))
        val bare = route()
        assertSame(bare, NavEngine.withArrivalSide(bare, pt(400.0, 2.0)))
        assertSame(bare, NavEngine.withArrivalSide(bare, null))
    }
}
