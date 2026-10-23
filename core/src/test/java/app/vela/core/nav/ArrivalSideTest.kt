package app.vela.core.nav

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
