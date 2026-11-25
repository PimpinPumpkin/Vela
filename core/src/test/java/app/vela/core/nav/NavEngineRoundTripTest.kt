package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A round trip (issue #929): the route ends where it starts. Leaving, the car sits at the end as
 *  much as at the start; the drive must set off normally and arrive only after going round. */
class NavEngineRoundTripTest {
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(38.54))
    private fun p(northM: Double, eastM: Double) = LatLng(38.54 + northM / 111_320.0, -121.74 + eastM / mPerDegLng)

    /** A square block driven anticlockwise from its southwest corner: east, north, west, south,
     *  back to the start, a vertex every 20 m. [departAt] moves the depart step off the line's
     *  first point, as a router's rounding can. */
    private fun loop(side: Double, departAt: LatLng = p(0.0, 0.0)): Route {
        val corners = listOf(p(0.0, 0.0), p(0.0, side), p(side, side), p(side, 0.0), p(0.0, 0.0))
        val line = ArrayList<LatLng>()
        val n = (side / 20.0).toInt()
        for (c in 0 until 4) {
            val a = corners[c]; val b = corners[c + 1]
            for (i in 0 until n) {
                val f = i.toDouble() / n
                line += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
        }
        line += corners[0]
        val total = line.zipWithNext { a, b -> a.distanceTo(b) }.sum()
        fun man(type: ManeuverType, at: LatLng, d: Double) = Maneuver(type, type.name, at, d, d / 10)
        return Route(
            polyline = line,
            legs = listOf(RouteLeg(total, total / 10, null, listOf(
                man(ManeuverType.DEPART, departAt, side),
                man(ManeuverType.TURN_LEFT, corners[1], side),
                man(ManeuverType.TURN_LEFT, corners[2], side),
                man(ManeuverType.TURN_LEFT, corners[3], side),
                man(ManeuverType.ARRIVE, corners[0], 0.0),
            ))),
            distanceMeters = total, durationSeconds = total / 10, durationInTrafficSeconds = null,
        )
    }

    private class Drive(val route: Route) {
        var st = NavState()
        var arrivedAt: Double? = null
        var maxStep = 0
        fun fix(at: LatLng, speed: Double, along: Double) {
            if (arrivedAt != null) return
            val (s, ev) = NavEngine.update(route, st, at, speedMps = speed)
            st = s
            maxStep = maxOf(maxStep, s.stepIndex)
            if (ev.any { it is NavEvent.Arrived }) arrivedAt = along
        }
    }

    /** Parked at the start for longer than the parked-at-the-end count, then round the block. */
    private fun parkedThenRound(side: Double, startFix: LatLng) {
        val route = loop(side)
        val cum = NavEngine.cumulative(route.polyline)
        val total = cum.last()
        val d = Drive(route)
        repeat(20) { d.fix(startFix, 0.0, 0.0) }
        assertEquals("parked at the start of a ${total.toInt()} m loop", null, d.arrivedAt)
        assertTrue("progress at the start is ${d.st.traveledM} m", d.st.traveledM < 30.0)
        assertTrue("on the departing step, not the last", d.st.stepIndex < route.maneuvers.lastIndex)
        var m = 0.0
        while (m <= total) { d.fix(RouteProjection.pointAt(route.polyline, cum, m), 10.0, m); m += 10.0 }
        val at = d.arrivedAt
        assertTrue("arrives at the end of a ${total.toInt()} m loop", at != null)
        assertTrue("arrived ${at!!.toInt()} m in, of ${total.toInt()}", at >= total - 50.0)
    }

    @Test fun longLoopStartsAndArrivesAtTheEnd() = parkedThenRound(500.0, p(0.0, 0.0))

    @Test fun shortLoopStartsAndArrivesAtTheEnd() = parkedThenRound(100.0, p(0.0, 0.0))

    // The fix at the start has drifted 15 m up the road the trip comes back on.
    @Test fun shortLoopWithTheFixOnTheWayBack() = parkedThenRound(100.0, p(15.0, 0.0))

    @Test fun midLoopWithTheFixOnTheWayBack() = parkedThenRound(150.0, p(15.0, 0.0))

    @Test fun longLoopWithTheFixOnTheWayBack() = parkedThenRound(500.0, p(15.0, 0.0))

    /** Every turn of the loop comes in turn: the depart step is the start, not the end. */
    @Test fun turnsComeInOrder() = turnsInOrder(loop(300.0))

    // A depart step a meter off the start, nearer the road the trip comes back on.
    @Test fun turnsComeInOrderWithTheDepartOffTheLine() = turnsInOrder(loop(300.0, p(1.0, -0.5)))

    private fun turnsInOrder(route: Route) {
        val cum = NavEngine.cumulative(route.polyline)
        val d = Drive(route)
        var m = 0.0
        val steps = ArrayList<Int>()
        while (m <= 1150.0) {
            d.fix(RouteProjection.pointAt(route.polyline, cum, m), 10.0, m)
            if (steps.lastOrNull() != d.st.stepIndex) steps += d.st.stepIndex
            m += 10.0
        }
        assertEquals(listOf(1, 2, 3, 4), steps)
        assertFalse(d.st.arrived)
    }

    /** Only a route whose start is within arrival range of its end is held off its last stretch. */
    @Test fun onlyALoopIsGuarded() {
        val square = loop(100.0).polyline
        assertEquals(400.0 / 3, NavEngine.loopGuardM(square, NavEngine.cumulative(square).last()), 1.0)
        val big = loop(500.0).polyline
        assertEquals(150.0, NavEngine.loopGuardM(big, NavEngine.cumulative(big).last()), 0.0)
        // Ends 60 m up the road back, short of the start: a reroute near a destination, not a loop.
        val short = square.dropLast(3)
        assertEquals(0.0, NavEngine.loopGuardM(short, NavEngine.cumulative(short).last()), 0.0)
        val straight = (0..20).map { p(0.0, it * 20.0) }
        assertEquals(0.0, NavEngine.loopGuardM(straight, NavEngine.cumulative(straight).last()), 0.0)
    }
}
