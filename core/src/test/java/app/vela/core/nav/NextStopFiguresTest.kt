package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.LegTime
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom bar's figures to the next stop on a drive with stops ([NavSession.nextStop]). */
class NextStopFiguresTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)

    /** East 2000 m: a first kilometer of 100 s, a turn, and a slower second kilometer of 200 s,
     *  so a stop's time depends on which leg it sits in. [traffic] is the live time. */
    private fun route(stepSeconds: Pair<Double, Double> = 100.0 to 200.0, traffic: Double? = 360.0): Route {
        val poly = (0..200).map { east(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1000.0, stepSeconds.first),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Covell Boulevard", east(1000.0), 1000.0, stepSeconds.second),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        val base = 300.0
        return Route(poly, listOf(RouteLeg(2000.0, base, traffic, ms)), 2000.0, base, traffic)
    }

    private fun stop(m: Double, label: String = "Davis Food Co-op", silent: Boolean = false) =
        NavSession.NavStop(east(m), label, silent)

    /** The engine's state at [m] meters along, the way a drive reaches it. */
    private fun at(r: Route, m: Double): NavState {
        var st = NavState()
        var x = 0.0
        while (x <= m) {
            st = NavEngine.update(r, st, east(x), speedMps = 10.0, bearingDeg = 90.0).first
            x += 10.0
        }
        return st
    }

    @Test fun `the time to a stop is pro-rated over the legs like the whole trip`() {
        val r = route()
        val stops = listOf(stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        val nav = at(r, 500.0)
        // Whole trip: half of the first leg (50 s) and the second (200 s), times the 1.2 traffic ratio.
        assertEquals(300.0, nav.remainingDuration, 2.0)
        val next = NavSession.nextStop(r, stops, marks, 0, nav)
        assertNotNull(next)
        assertEquals("Davis Food Co-op", next!!.label)
        assertEquals(1000.0, next.distanceM, 15.0)
        // To the stop: the same half of the first leg and HALF of the second, times 1.2.
        assertEquals((50.0 + 100.0) * 1.2, next.seconds, 2.0)
    }

    @Test fun `the stop's time and the time beyond it add up to the trip`() {
        val r = route()
        val stops = listOf(stop(1300.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        for (m in listOf(0.0, 250.0, 900.0, 1100.0)) {
            val nav = at(r, m)
            val next = NavSession.nextStop(r, stops, marks, 0, nav)!!
            assertEquals("at $m", nav.remainingDuration, next.seconds + NavEngine.secondsBeyond(r, marks[0]!!), 0.5)
        }
    }

    @Test fun `a route with its own leg times starts the stop on its leg's figure and still counts down to zero`() {
        // The steps' pro-rating gives the first kilometer 120 of the 360 s; the legs say it takes
        // 180, the traffic sitting before the stop. So the stop's time is the pro-rated one times
        // 1.5: 180 at the start, 90 halfway, and nothing at the mark, where the trip's own
        // remaining time (240) is exactly what lies beyond.
        val r = route().copy(legTimes = listOf(LegTime(1000.0, 100.0, 180.0), LegTime(1000.0, 200.0, 180.0)))
        val stops = listOf(stop(1000.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        assertEquals(1.5, NavEngine.legScale(r, marks[0]!!, 0.0), 0.01)
        assertEquals(180.0, NavEngine.legSecondsBeyond(r, marks[0]!!)!!, 0.5)
        assertEquals(180.0, NavEngine.legSecondsTo(r, marks[0]!!)!!, 0.5)
        assertEquals(180.0, NavSession.nextStop(r, stops, marks, 0, at(r, 0.0))!!.seconds, 3.0)
        val nav = at(r, 500.0)
        assertEquals(300.0, nav.remainingDuration, 2.0)
        assertEquals(90.0, NavSession.nextStop(r, stops, marks, 0, nav)!!.seconds, 3.0)
        // Just short of the mark (inside the arrival tolerance it counts as passed): seconds left.
        assertTrue(NavSession.nextStop(r, stops, marks, 0, at(r, 960.0))!!.seconds < 15.0)
    }

    @Test fun `a stop that is no leg boundary falls back to the steps' pro-rating`() {
        val r = route().copy(legTimes = listOf(LegTime(1000.0, 100.0, 180.0), LegTime(1000.0, 200.0, 180.0)))
        val stops = listOf(stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        assertNull(NavEngine.legSecondsBeyond(r, marks[0]!!))
        assertEquals(1.0, NavEngine.legScale(r, marks[0]!!, 0.0), 0.0)
        assertEquals((50.0 + 100.0) * 1.2, NavSession.nextStop(r, stops, marks, 0, at(r, 500.0))!!.seconds, 2.0)
    }

    @Test fun `without traffic the legs' typical times are the figure`() {
        // Steps 100 + 200 s, no traffic; the legs say 150 + 150. The first stop starts on 150.
        val r = route(traffic = null).copy(legTimes = listOf(LegTime(1000.0, 150.0, null), LegTime(1000.0, 150.0, null)))
        val stops = listOf(stop(1000.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        assertEquals(1.5, NavEngine.legScale(r, marks[0]!!, 0.0), 0.01)
        assertEquals(150.0, NavSession.nextStop(r, stops, marks, 0, at(r, 0.0))!!.seconds, 3.0)
    }

    @Test fun `the live calibration scales the stop's time like the trip's`() {
        val r = route()
        val stops = listOf(stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        val nav = at(r, 500.0)
        val plain = NavSession.nextStop(r, stops, marks, 0, nav)!!
        val scaled = NavSession.nextStop(r, stops, marks, 0, nav, etaScale = 1.5)!!
        assertEquals(plain.seconds * 1.5, scaled.seconds, 0.5)
        assertEquals("distance is not a time", plain.distanceM, scaled.distanceM, 0.01)
    }

    @Test fun `a route with no step times falls back to its average speed`() {
        val r = route(stepSeconds = 0.0 to 0.0, traffic = null)
        val stops = listOf(stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        val nav = at(r, 500.0)
        // 2000 m in 300 s: 6.67 m/s, so the kilometer to the stop is 150 s.
        assertEquals(150.0, NavSession.nextStop(r, stops, marks, 0, nav)!!.seconds, 3.0)
    }

    @Test fun `past the stop the bar goes back to the whole trip`() {
        val r = route()
        val stops = listOf(stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        assertNull(NavSession.nextStop(r, stops, marks, 0, at(r, 1490.0)))
    }

    @Test fun `the next stop moves on to the second one once the first is passed`() {
        val r = route()
        val stops = listOf(stop(600.0, "Davis Food Co-op"), stop(1700.0, "Sacramento Valley Station"))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        assertEquals("Davis Food Co-op", NavSession.nextStop(r, stops, marks, 0, at(r, 300.0))!!.label)
        val later = NavSession.nextStop(r, stops, marks, 0, at(r, 800.0))!!
        assertEquals("Sacramento Valley Station", later.label)
        assertEquals(900.0, later.distanceM, 15.0)
    }

    @Test fun `a silent via is not a stop to the driver`() {
        val r = route()
        val stops = listOf(stop(700.0, "", silent = true), stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        val next = NavSession.nextStop(r, stops, marks, 0, at(r, 300.0))!!
        assertEquals("Davis Food Co-op", next.label)
        assertEquals(1200.0, next.distanceM, 15.0)
    }

    @Test fun `a next stop that cannot be measured leaves the bar on the whole trip`() {
        val r = route()
        // The first stop is two kilometers off the line: no mark. Its figures cannot be given, and
        // the second stop's must not be shown under the first one's name.
        val off = NavSession.NavStop(LatLng(lat + 0.02, lng0), "Off the route")
        val stops = listOf(off, stop(1500.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        assertNull(marks[0])
        assertNull(NavSession.nextStop(r, stops, marks, 0, at(r, 300.0)))
    }

    @Test fun `neither figure ever exceeds the whole trip's`() {
        val r = route()
        val stops = listOf(stop(1995.0))
        val marks = NavEngine.stopMarks(r, stops.map { it.location })
        val nav = at(r, 100.0)
        val next = NavSession.nextStop(r, stops, marks, 0, nav)!!
        assertTrue(next.seconds <= nav.remainingDuration)
        assertTrue(next.distanceM <= nav.remainingDistance)
    }
}
