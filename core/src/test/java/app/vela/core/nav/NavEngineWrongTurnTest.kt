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
 * A deliberate wrong turn at speed must ask for a reroute on the SECOND fix after the turn, not
 * the third (real drive 2026-09-07: the guidance kept the driver on the old line for too long).
 * A wide but legitimate turn, which stays within a few meters of the corner while the fix's
 * course swings, must not.
 */
class NavEngineWrongTurnTest {

    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private val mPerDegLat = 111_320.0

    /** A 2 km route due east with a DEPART and an ARRIVE. */
    private val route: Route by lazy {
        val poly = (0..20).map { LatLng(lat, lng0 + (it * 100.0) / mPerDegLng) }
        Route(
            poly,
            listOf(
                RouteLeg(
                    2000.0, 120.0, null,
                    listOf(
                        Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 2000.0, 0.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
                    ),
                ),
            ),
            2000.0, 120.0, null,
        )
    }

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)
    private fun southOf(m: Double, s: Double) = LatLng(lat - s / mPerDegLat, lng0 + m / mPerDegLng)

    private fun drive(state: NavState, at: LatLng, bearing: Double): Pair<NavState, List<NavEvent>> =
        NavEngine.update(route, state, at, speedMps = 8.0, bearingDeg = bearing)

    @Test fun `a left turn at speed reroutes on the second fix after the turn`() {
        var st = NavState()
        for (m in listOf(0.0, 8.0, 16.0, 24.0)) st = drive(st, east(m), 90.0).first
        assertFalse(st.offRoute)
        // Turned south at the 24 m mark: heading 180, 8 m/s, so 8 m then 16 m off the line.
        val (s1, e1) = drive(st, southOf(24.0, 8.0), 180.0)
        assertTrue("one heading-off fix is a turn transient, not yet a reroute", e1.none { it is NavEvent.RerouteNeeded })
        val (s2, e2) = drive(s1, southOf(24.0, 16.0), 180.0)
        assertTrue("second fix, 16 m off and heading away, must reroute", e2.any { it is NavEvent.RerouteNeeded })
        assertTrue(s2.offRoute)
    }

    @Test fun `a wide turn that stays beside the corner does not reroute`() {
        var st = NavState()
        for (m in listOf(0.0, 8.0, 16.0, 24.0)) st = drive(st, east(m), 90.0).first
        // Course swings 70° for two fixes but the car is 3 m and 6 m from the line: a wide
        // legitimate turn, still counted single, so no reroute on the second fix.
        val (s1, _) = drive(st, southOf(30.0, 3.0), 160.0)
        val (_, e2) = drive(s1, southOf(36.0, 6.0), 160.0)
        assertTrue(e2.none { it is NavEvent.RerouteNeeded })
    }

    /** East 300 m, then north 300 m: a left turn at 300 m. */
    private val turning: Route by lazy {
        val poly = (0..30).map { east(it * 10.0) } + (1..30).map { LatLng(lat + it * 10.0 / mPerDegLat, lng0 + 300.0 / mPerDegLng) }
        Route(
            poly,
            listOf(RouteLeg(600.0, 60.0, null, listOf(
                Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 300.0, 30.0),
                Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", east(300.0), 300.0, 30.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
            ))),
            600.0, 60.0, null,
        )
    }

    @Test fun `straight on where the route turns reroutes about two fixes past the corner`() {
        var st = NavState()
        var m = 0.0
        while (m <= 290.0) { st = NavEngine.update(turning, st, east(m), speedMps = 11.0, bearingDeg = 90.0).first; m += 11.0 }
        assertFalse(st.offRoute)
        // On east past the corner at 11 m/s.
        var firedAt = -1.0
        while (m <= 360.0 && firedAt < 0) {
            val (s, ev) = NavEngine.update(turning, st, east(m), speedMps = 11.0, bearingDeg = 90.0)
            st = s; if (ev.any { it is NavEvent.RerouteNeeded }) firedAt = m; m += 11.0
        }
        assertTrue("rerouted at $firedAt m, 300 is the corner", firedAt in 300.0..330.0)
    }

    @Test fun `taking the turn is not straight on`() {
        var st = NavState()
        var m = 0.0
        while (m <= 290.0) { st = NavEngine.update(turning, st, east(m), speedMps = 11.0, bearingDeg = 90.0).first; m += 11.0 }
        // Rounding the corner: the course swings through north-east, then runs north.
        st = NavEngine.update(turning, st, east(298.0), speedMps = 8.0, bearingDeg = 60.0).first
        st = NavEngine.update(turning, st, LatLng(lat + 5.0 / mPerDegLat, lng0 + 301.0 / mPerDegLng), speedMps = 8.0, bearingDeg = 20.0).first
        for (n in 1..6) {
            val (s, ev) = NavEngine.update(turning, st, LatLng(lat + (5.0 + n * 9.0) / mPerDegLat, lng0 + 300.0 / mPerDegLng), speedMps = 9.0, bearingDeg = 0.0)
            assertTrue(ev.none { it is NavEvent.RerouteNeeded }); st = s
        }
        assertFalse(st.offRoute)
    }
}
