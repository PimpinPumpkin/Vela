package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoadRename
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShieldsTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)

    /** A line due east, one vertex every 100 m, with these steps laid along it. */
    private fun route(vararg steps: Maneuver): Route {
        val total = steps.sumOf { it.distanceMeters }
        val poly = (0..(total / 100).toInt()).map { east(it * 100.0) }
        return Route(poly, listOf(RouteLeg(total, total / 15, null, steps.toList())), total, total / 15, null)
    }
    private fun step(type: ManeuverType, m: Double, ref: String? = null, renames: List<RoadRename> = emptyList()) =
        Maneuver(type, "", east(0.0), m, m / 15, road = null, ref = ref, renames = renames)

    @Test fun `a numbered road gets a shield soon after joining and every so often`() {
        val r = route(step(ManeuverType.DEPART, 500.0), step(ManeuverType.RAMP_RIGHT, 8_000.0, ref = "I 80"), step(ManeuverType.ARRIVE, 0.0))
        val s = RouteShields.points(r)
        // The stretch runs 500 to 8,500 m: 850, 2,450, 4,050, 5,650 and 7,250 m.
        assertEquals(List(5) { "80" }, s.map { it.text })
        assertTrue(s.all { it.type == ShieldType.INTERSTATE })
        assertEquals(850.0, east(0.0).distanceTo(s[0].at), 5.0)
        assertEquals(RouteShields.EVERY_M, s[0].at.distanceTo(s[1].at), 5.0)
        assertTrue("none in the last stretch before the turn", east(8_500.0).distanceTo(s.last().at) >= RouteShields.END_CLEAR_M)
    }

    @Test fun `a road with no number, and a short hop on a numbered one, get none`() {
        val r = route(step(ManeuverType.DEPART, 3_000.0), step(ManeuverType.TURN_LEFT, 600.0, ref = "CA 113"), step(ManeuverType.TURN_RIGHT, 2_000.0), step(ManeuverType.ARRIVE, 0.0))
        assertTrue(RouteShields.points(r).isEmpty())
    }

    @Test fun `the same number through a keep-right is one stretch, and a number that changes mid-leg starts another`() {
        val r = route(
            step(ManeuverType.DEPART, 2_000.0, ref = "US 50"),
            step(ManeuverType.KEEP_RIGHT, 2_000.0, ref = "US-50 E", renames = listOf(RoadRename(1_000.0, null, "CA 99"))),
            step(ManeuverType.CONTINUE, 3_000.0, ref = "CA 99"),
            step(ManeuverType.ARRIVE, 0.0),
        )
        val s = RouteShields.points(r)
        // US 50 runs 0 to 3,000 m: shields at 350 and 1,950 m.
        // CA 99 runs 3,000 to 7,000 m: shields at 3,350, 4,950 and 6,550 m.
        assertEquals(listOf("50", "50", "99", "99", "99"), s.map { it.text })
        assertEquals(ShieldType.US_ROUTE, s[0].type)
        assertEquals(ShieldType.STATE, s[2].type)
        assertEquals(3_350.0, east(0.0).distanceTo(s[2].at), 5.0)
    }

    @Test fun `a county road shows its own number, and a long one is left out`() {
        val r = route(step(ManeuverType.DEPART, 2_000.0, ref = "CR E6"), step(ManeuverType.TURN_LEFT, 2_000.0, ref = "Ruta Nacional 40"), step(ManeuverType.ARRIVE, 0.0))
        val s = RouteShields.points(r)
        assertEquals(listOf("E6"), s.map { it.text })
        assertEquals(ShieldType.GENERIC, s.single().type)
    }
}
