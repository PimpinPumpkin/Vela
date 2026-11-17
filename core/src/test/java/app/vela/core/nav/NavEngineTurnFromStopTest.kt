package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn the route makes, taken from a stop, must not read as driving against the route. Mid-turn
 * the course has swung while the fix still projects before the corner, or has not swung enough
 * when it already projects past it, and the car ends up in the far lane of the road it turned
 * onto. That asked for a reroute 3 s after a right turn taken as told (real drive 2026-11-17).
 * A wrong turn at the same corner still reroutes on the second fix after it.
 */
class NavEngineTurnFromStopTest {

    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private val mPerDegLat = 111_320.0

    /** East [x] and north [y] meters from the start. */
    private fun at(x: Double, y: Double) = LatLng(lat0 + y / mPerDegLat, lng0 + x / mPerDegLng)

    /** North 200 m, then a right turn and east 300 m. */
    private val route: Route by lazy {
        val poly = (0..20).map { at(0.0, it * 10.0) } + (1..30).map { at(it * 10.0, 200.0) }
        Route(
            poly,
            listOf(
                RouteLeg(
                    500.0, 60.0, null,
                    listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", poly.first(), 200.0, 25.0),
                        Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto 2nd Street", at(0.0, 200.0), 300.0, 35.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
                    ),
                ),
            ),
            500.0, 60.0, null,
        )
    }

    private data class Fix(val x: Double, val y: Double, val course: Double, val speed: Double)

    /** Up to a stop 35 m short of the corner, then [turn], each fix 4 m accurate. */
    private fun drive(turn: List<Fix>): List<NavEvent> {
        var st = NavState()
        val events = mutableListOf<NavEvent>()
        val approach = (0..16).map { Fix(1.0, it * 10.0, 0.0, 10.0) } + List(5) { Fix(1.5, 165.0, 0.0, 0.0) }
        for (f in approach + turn) {
            val (next, ev) = NavEngine.update(
                route, st, at(f.x, f.y), speedMps = f.speed,
                offRouteM = NavEngine.offRouteCorridor(app.vela.core.model.TravelMode.DRIVE, 4.0),
                bearingDeg = f.course,
            )
            st = next
            events += ev
        }
        return events
    }

    @Test fun `a right turn from a stop into the far lane does not reroute`() {
        val events = drive(
            listOf(
                Fix(2.0, 170.0, 10.0, 3.0),
                Fix(3.0, 175.0, 30.0, 5.0),
                Fix(6.0, 182.0, 61.0, 6.0),   // course swung, still before the corner
                Fix(12.0, 188.0, 96.0, 8.7),  // past it, 12 m from the line it turned onto
                Fix(24.0, 191.0, 105.0, 9.5),
                Fix(38.0, 193.0, 95.0, 10.5),
                Fix(52.0, 195.0, 90.0, 11.0),
            ),
        )
        assertTrue(events.none { it is NavEvent.RerouteNeeded })
    }

    @Test fun `a fix placed past the corner before the course swings does not reroute`() {
        val events = drive(
            listOf(
                Fix(1.5, 172.0, 0.0, 4.5),
                Fix(1.5, 182.0, -10.0, 6.0),
                Fix(14.0, 189.0, 17.0, 6.8),  // projects onto the new road 11 m off, course 73° short of it
                Fix(20.0, 193.0, 25.0, 7.5),
                Fix(30.0, 197.0, 70.0, 8.5),
                Fix(44.0, 199.0, 90.0, 9.5),
            ),
        )
        assertTrue(events.none { it is NavEvent.RerouteNeeded })
    }

    @Test fun `a left turn at the same corner still reroutes on the second fix after it`() {
        val events = drive(
            listOf(
                Fix(1.0, 180.0, 0.0, 6.0),
                Fix(1.0, 192.0, 0.0, 8.0),
                Fix(-8.0, 200.0, 270.0, 8.0),
            ),
        )
        assertFalse("one fix into the wrong turn is a transient", events.any { it is NavEvent.RerouteNeeded })
        val more = drive(
            listOf(
                Fix(1.0, 180.0, 0.0, 6.0),
                Fix(1.0, 192.0, 0.0, 8.0),
                Fix(-8.0, 200.0, 270.0, 8.0),
                Fix(-16.0, 200.0, 270.0, 8.0),
            ),
        )
        assertTrue(more.any { it is NavEvent.RerouteNeeded })
    }
}
