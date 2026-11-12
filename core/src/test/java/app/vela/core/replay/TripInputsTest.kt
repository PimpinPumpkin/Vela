package app.vela.core.replay

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recorded drive keeps what the nav engine was handed and what each router answered. */
class TripInputsTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val step = 0.00032 // ~28 m of longitude here

    private fun route(n: Int, from: Int = 0): Route {
        val poly = (from until from + n).map { LatLng(lat, lng0 + step * it) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 28.0 * (n - 1), 60.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(28.0 * (n - 1), 60.0, null, ms)), 28.0 * (n - 1), 60.0, null)
    }

    private fun fix(i: Int) = "$lat,${lng0 + step * i},${1756700000000L + i * 1000},90.0,15.0,0,4.0,gps,0\n"
    private fun input(i: Int, kind: Char = 'f', ms: Long = 2) =
        "N,${1756700000000L + i * 1000 + ms}," + TripLog.inputPayload(kind, ms, LatLng(lat, lng0 + step * i), 15.0, 4.0, if (kind == 'd') null else 90.0) + "\n"

    /** Ten fixes on the start route, a reroute block, then ten more; one dead-reckoned point. */
    private fun csv(withRouting: Boolean = true) = buildString {
        append("META,Davis,1756700000000,$lat,${lng0 + step * 30},3109\n")
        append(TripLog.encodeRoute(route(31), "start"))
        if (withRouting) append("K,1756700000000," + TripLog.routingNote(false, false, false, false, true, true, "DRIVE") + "\n")
        for (i in 0 until 10) { append(fix(i)); append(input(i)) }
        append("N,1756700010500," + TripLog.inputPayload('d', 1500, LatLng(lat, lng0 + step * 9.5), 14.0, null, null) + "\n")
        append("K,1756700010600,route answers (reroute): open router 1 in 700 ms, google 2 in 900 ms, 1 against heading 90, 1 set aside\n")
        append("RC,1756700010600," + TripLog.candidatePayload("reroute", "google", listOf("against", "aside"), route(5, 10)) + "\n")
        append(TripLog.encodeRoute(route(21, 10), "reroute"))
        for (i in 10 until 20) { append(fix(i)); append(input(i)) }
    }

    @Test fun `inputs are read with the route block and the fix they follow`() {
        val p = TripLog.parse(csv())
        assertEquals(20, p.points.size)
        assertEquals(2, p.segments.size)
        assertEquals(21, p.inputs.size)
        val first = p.inputs.first()
        assertEquals(0, first.segment); assertEquals(0, first.afterFix); assertEquals('f', first.kind)
        assertEquals(90.0, first.bearing!!, 0.0); assertEquals(4.0, first.accuracyM!!, 0.0)
        val reckoned = p.inputs[10]
        assertEquals('d', reckoned.kind); assertFalse(reckoned.fromFix)
        assertEquals("written before the reroute block, so on the start route", 0, reckoned.segment)
        assertEquals(9, reckoned.afterFix); assertEquals(1500L, reckoned.msSinceFix)
        assertNull("no heading was passed", reckoned.bearing)
        val after = p.inputs[11]
        assertEquals(1, after.segment); assertEquals(10, after.afterFix)
    }

    @Test fun `a router answer line is not a fix or a route`() {
        val p = TripLog.parse(csv())
        assertEquals(20, p.points.size)
        assertEquals(2, p.segments.size)
        assertTrue(p.events.any { it.tag == "K" && it.text.startsWith("route answers (reroute)") })
        val rc = csv().lines().first { it.startsWith("RC,") }.split(',')
        assertEquals("reroute", rc[2]); assertEquals("google", rc[3]); assertEquals("against;aside", rc[4])
        assertEquals(4, app.vela.core.data.google.PolylineCodec.decode(rc[7]).size - 1)
    }

    @Test fun `the drive's units come from its routing note`() {
        assertEquals(false, TripLog.recordedImperial(TripLog.parse(csv()).events))
        assertNull(TripLog.recordedImperial(TripLog.parse(csv(withRouting = false)).events))
        assertTrue(TripLog.routingNote(true, true, false, false, true, false, "DRIVE").startsWith("routing: units imperial, avoid tolls on"))
    }

    @Test fun `a shared copy leaves engine inputs and router answers out`() {
        val n = 200
        val big = buildString {
            append("META,Davis,1756700000000,$lat,${lng0 + step * (n - 1)},3109\n")
            append(TripLog.encodeRoute(route(n), "start"))
            for (i in 0 until n) { append(fix(i)); append(input(i)) }
            append("RC,1756700100000," + TripLog.candidatePayload("reroute", "open", emptyList(), route(50, 60)) + "\n")
            append("K,1756700100000,route answers (reroute): open router 1 in 700 ms, google not back, heading 90\n")
        }
        val out = TripScrub.scrub(big)!!.csv
        assertTrue(out.lines().none { it.startsWith("N,") || it.startsWith("RC,") })
        assertTrue("the summary has no position and is kept", out.contains("route answers (reroute)"))
    }

    @Test fun `the audit runs on what the engine was handed`() {
        val report = TripLog.audit(csv())!!
        // One card per input: 11 on the start route (with the reckoned one), 10 on the reroute.
        assertEquals(21, report.cards.size)
        assertEquals("a card's fix is the fix its input followed", 9, report.cards[10].fixIndex)
        assertEquals(10, report.cards[11].fixIndex)
        assertTrue(report.cards.all { it.fixIndex in 0 until 20 })
    }

    @Test fun `an older file without inputs still audits from its fixes`() {
        val old = csv().lines().filterNot { it.startsWith("N,") }.joinToString("\n")
        val report = TripLog.audit(old)!!
        assertEquals(20, report.cards.size)
        assertEquals(10, report.cards[10].fixIndex)
    }
}
