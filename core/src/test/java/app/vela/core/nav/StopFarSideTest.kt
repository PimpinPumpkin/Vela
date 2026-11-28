package app.vela.core.nav

import app.vela.core.data.TrafficControl
import app.vela.core.data.TrafficControl.Kind
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stop sign on the far side of a junction holds the other direction. Davis fixture. */
class StopFarSideTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    // East 300 m, a left at the corner, north 300 m.
    private val poly = (0..30).map { at(it * 10.0, 0.0) } + (1..30).map { at(300.0, it * 10.0) }
    private val cum = RouteProjection.cumulative(poly)
    private val route = Route(
        poly,
        listOf(RouteLeg(600.0, 60.0, null, listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 300.0, 30.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left", at(300.0, 0.0), 300.0, 30.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        ))),
        600.0, 60.0, null,
    )
    private val corners = RouteProjection.cornersAlong(route, poly, cum)

    private fun stop(x: Double, y: Double, road: Int) = TrafficControl(at(x, y), Kind.STOP, road)
    private fun kept(vararg s: TrafficControl) = RouteProjection.stopsNotFarSide(poly, cum, s.toList(), corners)
        .filter { RouteProjection.stopIsOnRoute(poly, cum, it.loc, it.roadBearingDeg) }

    @Test fun `the sign holding the street turned onto is left out`() {
        val ours = stop(292.0, 0.0, 90)
        val theirs = stop(300.0, 10.0, 0) // on the street the route turns onto, for its traffic coming out
        assertEquals(listOf(ours), kept(ours, theirs))
    }

    @Test fun `at a junction driven straight through only the near side's sign is yours`() {
        val ours = stop(142.0, 0.0, 90)
        val oncoming = stop(158.0, 0.0, 90)
        val crossN = stop(150.0, 8.0, 0)
        val crossS = stop(150.0, -8.0, 0)
        assertEquals(listOf(ours), kept(ours, oncoming, crossN, crossS))
        // Both of the route's road's signs alone mark the junction too.
        assertEquals(listOf(ours), kept(ours, oncoming))
    }

    @Test fun `a sign on the junction's own node or further on stays`() {
        val node = stop(302.0, 2.0, 0)
        val next = stop(300.0, 40.0, 0)
        assertEquals(listOf(node, next), kept(node, next))
    }

    @Test fun `a sign just past one corner and before the next stays`() {
        // East 300 m, left, north 25 m, right, east: the jog's second corner has its own sign.
        val jog = (0..30).map { at(it * 10.0, 0.0) } + listOf(at(300.0, 10.0), at(300.0, 20.0), at(300.0, 25.0)) +
            (1..20).map { at(300.0 + it * 10.0, 25.0) }
        val jcum = RouteProjection.cumulative(jog)
        val r = Route(
            jog,
            listOf(RouteLeg(525.0, 52.0, null, listOf(
                Maneuver(ManeuverType.DEPART, "Head east", jog.first(), 300.0, 30.0),
                Maneuver(ManeuverType.TURN_LEFT, "Turn left", at(300.0, 0.0), 25.0, 3.0),
                Maneuver(ManeuverType.TURN_RIGHT, "Turn right", at(300.0, 25.0), 200.0, 20.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", jog.last(), 0.0, 0.0),
            ))),
            525.0, 52.0, null,
        )
        val second = stop(300.0, 15.0, 0)
        assertEquals(listOf(second), RouteProjection.stopsNotFarSide(jog, jcum, listOf(second), RouteProjection.cornersAlong(r, jog, jcum)))
    }
}
