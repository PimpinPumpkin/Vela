package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavEngine
import app.vela.core.nav.NavEvent
import app.vela.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulated drive ([DemoTrace.fromRoute] with a route). Fixture: a staircase of town blocks
 *  from the Davis fixture point, east one block and north one block, eight turns. */
class DemoTraceTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mLng)

    /** [block] meters a side; each step's time is its length at [paceMps], or 0 with no times. */
    private fun staircase(block: Double, paceMps: Double?): Pair<Route, List<LatLng>> {
        val poly = ArrayList<LatLng>(); val corners = ArrayList<LatLng>()
        var e = 0.0; var n = 0.0
        poly += pt(0.0, 0.0)
        for (k in 0 until 9) {
            repeat((block / 10).toInt()) { if (k % 2 == 0) e += 10.0 else n += 10.0; poly += pt(e, n) }
            corners += pt(e, n)
        }
        val secs = paceMps?.let { block / it } ?: 0.0
        val ms = ArrayList<Maneuver>()
        ms += Maneuver(ManeuverType.DEPART, "Head east", poly.first(), block, secs)
        for (k in 0 until 8) {
            val left = k % 2 == 0
            ms += Maneuver(
                if (left) ManeuverType.TURN_LEFT else ManeuverType.TURN_RIGHT,
                if (left) "Turn left onto North Sycamore Street" else "Turn right onto East Covell Boulevard",
                corners[k], block, secs, instructionNoRoad = if (left) "Turn left" else "Turn right",
            )
        }
        ms += Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0)
        val total = block * 9
        return Route(poly, listOf(RouteLeg(total, secs * 9, null, ms)), total, secs * 9, null) to corners.dropLast(1)
    }

    @Test fun `a town drive keeps to the steps' pace, slows for each turn, and starts and ends at rest`() {
        val (route, corners) = staircase(110.0, 11.2)
        val fixes = DemoTrace.fromRoute(route)
        assertTrue(fixes.size > 2)
        assertTrue("waits, then pulls away", fixes.take(3).all { it.speed == 0f } && fixes[3].speed in 0.5f..1.5f)
        assertEquals(0f, fixes.last().speed, 0f)
        assertTrue("never over the pace", fixes.all { it.speed <= 11.3f })
        assertTrue("gets up to the pace between turns", fixes.any { it.speed > 9.5f })
        for (c in corners) {
            val near = fixes.minBy { LatLng(it.lat, it.lng).distanceTo(c) }
            assertTrue("a corner is taken at ${near.speed} m/s", near.speed <= 7.5f)
        }
        // One fix a second, on the line, ending at the end.
        fixes.zipWithNext().forEach { (a, b) -> assertEquals(1000L, b.t - a.t) }
        assertTrue(LatLng(fixes.last().lat, fixes.last().lng).distanceTo(route.polyline.last()) < 1.0)
    }

    @Test fun `a fast straight road is driven fast`() {
        val poly = (0..500).map { pt(it * 10.0, 0.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 5000.0, 5000.0 / 30.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        val fixes = DemoTrace.fromRoute(Route(poly, listOf(RouteLeg(5000.0, 5000.0 / 30.0, null, ms)), 5000.0, 5000.0 / 30.0, null))
        assertTrue(fixes.maxOf { it.speed } > 29.5f)
    }

    @Test fun `a route with no times runs at the default pace`() {
        val (route, _) = staircase(400.0, null)
        val top = DemoTrace.fromRoute(route).maxOf { it.speed }
        assertTrue("$top", top > 12.5f && top <= 13.5f)
    }

    @Test fun `a drive handed a new route mid-way carries on at its speed`() {
        val (route, _) = staircase(400.0, 11.2)
        val fixes = DemoTrace.fromRoute(route, startMps = 9.0)
        assertTrue("${fixes.first().speed}", fixes.first().speed in 8.5f..9.5f)
    }

    @Test fun `bends are slower the sharper they are`() {
        assertTrue(DemoTrace.turnSpeed(5.0) > 100.0)
        assertTrue(DemoTrace.turnSpeed(30.0) > DemoTrace.turnSpeed(60.0))
        assertTrue(DemoTrace.turnSpeed(60.0) > DemoTrace.turnSpeed(90.0))
        assertTrue(DemoTrace.turnSpeed(90.0) > DemoTrace.turnSpeed(170.0))
    }

    /** Lines cut off or never started when [fixes] are driven through the engine. The player
     *  says one line at a time: a plain line waits its turn, an interrupting one cuts what is
     *  playing and drops what is waiting. 0.07 s a character plus 0.2 s (Pixel 4a, Kokoro). */
    private fun linesLost(route: Route, fixes: List<ReplayFix>): Int {
        var st = NavState()
        var lost = 0; var freeAt = 0.0
        val pending = ArrayList<DoubleArray>()
        for (f in fixes) {
            val t = f.t / 1000.0
            val (s, ev) = NavEngine.update(route, st, LatLng(f.lat, f.lng), speedMps = f.speed.toDouble(), bearingDeg = f.bearing.toDouble())
            st = s
            for (l in ev.filterIsInstance<NavEvent.Speak>()) {
                val dur = 0.2 + 0.07 * l.text.length
                if (l.interrupt) {
                    lost += pending.count { it[1] > t + 0.05 }
                    pending.clear(); freeAt = t + dur; pending += doubleArrayOf(t, freeAt)
                } else {
                    val start = maxOf(t, freeAt); freeAt = start + dur; pending += doubleArrayOf(start, freeAt)
                }
            }
        }
        return lost
    }

    @Test fun `the realistic drive leaves each spoken line time to finish on town blocks`() {
        val (route, _) = staircase(110.0, 11.2)
        assertEquals(0, linesLost(route, DemoTrace.fromRoute(route)))
        // The old constant 72 km/h trace over the same blocks cut lines off.
        assertTrue(linesLost(route, DemoTrace.fromRoute(route.polyline)) > 0)
    }
}
