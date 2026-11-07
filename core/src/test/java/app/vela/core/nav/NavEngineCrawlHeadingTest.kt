package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The heading term against GPS drift at a crawl (a car pulling away from a light). */
class NavEngineCrawlHeadingTest {
    private val lat0 = 38.5449
    private val lng = -121.7405
    private fun at(northM: Double) = LatLng(lat0 + northM / 111_320.0, lng)

    private val route = run {
        val poly = (0..200).map { at(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 2000.0, 150.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", at(2000.0), 0.0, 0.0),
        )
        Route(poly, listOf(RouteLeg(2000.0, 150.0, null, ms)), 2000.0, 150.0, null)
    }

    /** Stopped on the line at 500 m, then the fixes in [moves] (meters north, speed, course). */
    private fun drive(moves: List<Triple<Double, Double, Double>>): Boolean {
        var st = NavState(stepIndex = 0, traveledM = 0.0)
        var rerouted = false
        for (m in 0..500 step 10) st = NavEngine.update(route, st, at(m.toDouble()), speedMps = 10.0, bearingDeg = 0.0).first
        repeat(20) { st = NavEngine.update(route, st, at(500.0), speedMps = 0.0, bearingDeg = 0.0).first }
        for ((north, speed, course) in moves) {
            val (s, ev) = NavEngine.update(route, st, at(north), speedMps = speed, bearingDeg = course)
            st = s
            if (ev.any { it is NavEvent.RerouteNeeded }) rerouted = true
        }
        return rerouted
    }

    @Test fun `drift that reads as crawling backward on the line does not reroute`() {
        // The recorded case: 2.7, 3.6, 3.9 m/s at about 230 degrees against a route heading north
        // east, 1 to 2 m from the line, moving back 24 m over four fixes; then on its way.
        val drift = listOf(Triple(498.0, 2.1, 200.0), Triple(494.0, 2.7, 180.0), Triple(488.0, 3.6, 185.0), Triple(481.0, 3.9, 190.0), Triple(476.0, 3.5, 175.0))
        val away = (1..8).map { Triple(476.0 + it * 9.0, 9.0, 2.0) }
        assertFalse(drive(drift + away))
    }

    @Test fun `a real turnaround at driving speed still reroutes`() {
        val back = (1..6).map { Triple(500.0 - it * 9.0, 9.0, 180.0) }
        assertTrue(drive(back))
    }
}
