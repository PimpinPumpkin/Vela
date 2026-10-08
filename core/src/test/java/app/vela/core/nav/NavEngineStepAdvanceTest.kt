package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step shown stays the turn in hand until the car is at it. A car waiting at a stop line
 * 20 m short of a left turn used to be shown the turn after it (a real drive, 2026-10-07): the
 * step advanced with the spoken "turn left", 25 m out.
 */
class NavEngineStepAdvanceTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private val mPerDegLat = 111_320.0

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)
    private fun northOf(e: Double, n: Double) = LatLng(lat + n / mPerDegLat, lng0 + e / mPerDegLng)

    /** East 300 m, left turn, north 1,500 m, right turn, east 500 m. */
    private val route: Route by lazy {
        val poly = (0..30).map { east(it * 10.0) } + (1..150).map { northOf(300.0, it * 10.0) } + (1..50).map { northOf(300.0 + it * 10.0, 1500.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 300.0, 0.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto North Road", east(300.0), 1500.0, 0.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto East Road", northOf(300.0, 1500.0), 500.0, 0.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        Route(poly, listOf(RouteLeg(2300.0, 300.0, null, ms)), 2300.0, 300.0, null)
    }

    private fun step(st: NavState, at: LatLng, speed: Double, bearing: Double) = NavEngine.update(route, st, at, speedMps = speed, bearingDeg = bearing)

    @Test fun `a car stopped short of its turn still shows that turn`() {
        var st = NavState()
        val said = ArrayList<String>()
        fun go(at: LatLng, speed: Double, bearing: Double = 90.0) {
            val (s, ev) = step(st, at, speed, bearing)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
        }
        // Up to the queue at 8 m/s, then a crawl to a stop 20 m short of the corner.
        var m = 0.0
        while (m < 250.0) { go(east(m), 8.0); m += 8.0 }
        for (x in listOf(262.0, 270.0, 276.0, 280.0)) go(east(x), 2.0)
        repeat(8) { go(east(280.0), 0.0) }
        assertEquals("waiting 20 m short, the card is the left turn", 1, st.stepIndex)
        assertTrue("and its distance is the 20 m to it", st.distanceToNextManeuver in 15.0..25.0)
        assertEquals("the turn was called once, not on every fix of the wait", 1, said.count { it.contains("left", ignoreCase = true) && !it.startsWith("In ") })
        // Through the corner.
        go(east(292.0), 3.0)
        go(northOf(300.0, 4.0), 5.0, 0.0)
        go(northOf(300.0, 12.0), 7.0, 0.0)
        assertEquals("past the corner, the card is the next turn", 2, st.stepIndex)
    }

    @Test fun `at speed the step still moves on two and a half seconds ahead`() {
        var st = NavState()
        var m = 0.0
        while (m < 240.0) { st = step(st, east(m), 20.0, 90.0).first; m += 20.0 }
        assertEquals(1, st.stepIndex)
        // 20 m/s: 50 m of warning. At 45 m short the step has moved on.
        st = step(st, east(255.0), 20.0, 90.0).first
        assertEquals(2, st.stepIndex)
    }

    /** East 400 m, then a left into a lot whose end is 20 m in. */
    private val lotRoute: Route by lazy {
        val poly = (0..40).map { east(it * 10.0) } + (1..2).map { northOf(400.0, it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 400.0, 0.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left", east(400.0), 20.0, 0.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        Route(poly, listOf(RouteLeg(420.0, 60.0, null, ms)), 420.0, 60.0, null)
    }

    @Test fun `parking just short of a last turn into the destination still arrives`() {
        var st = NavState()
        var arrived = false
        fun go(at: LatLng, speed: Double) {
            val (s, ev) = NavEngine.update(lotRoute, st, at, speedMps = speed, bearingDeg = 90.0)
            st = s
            if (ev.any { it is NavEvent.Arrived }) arrived = true
        }
        var m = 0.0
        while (m < 360.0) { go(east(m), 8.0); m += 8.0 }
        assertTrue("still driving, 40 m short of the turn: not there yet", !arrived)
        for (x in listOf(372.0, 380.0, 385.0)) go(east(x), 2.5)
        assertTrue("rolling up to the entrance is not arriving", !arrived)
        repeat(3) { go(east(385.0), 0.0) }
        assertTrue("parked 15 m before the turn, 35 m of route left", arrived)
    }

    @Test fun `stopping short of a turn in mid route is not an arrival`() {
        var st = NavState()
        var arrived = false
        var m = 0.0
        while (m < 250.0) { val (s, ev) = step(st, east(m), 8.0, 90.0); st = s; if (ev.any { it is NavEvent.Arrived }) arrived = true; m += 8.0 }
        repeat(5) { val (s, ev) = step(st, east(285.0), 0.0, 90.0); st = s; if (ev.any { it is NavEvent.Arrived }) arrived = true }
        assertTrue(!arrived)
        assertEquals(1, st.stepIndex)
    }
}
