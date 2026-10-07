package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
import app.vela.core.model.bearingTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSmoothingTest {
    private val start = LatLng(38.55, -121.74) // Davis fixture

    /** A line walked as (bearing, meters) legs, a vertex every [step] meters. */
    private fun walk(vararg legs: Pair<Double, Double>, step: Double = 10.0): List<LatLng> {
        val out = mutableListOf(start)
        for ((brg, m) in legs) {
            var left = m
            while (left > 1e-6) {
                val d = minOf(step, left)
                out += out.last().destinationPoint(d, brg)
                left -= d
            }
        }
        return out
    }

    private fun maxOffsetFromLine(poly: List<LatLng>, a: LatLng, b: LatLng): Double = poly.maxOf { crossTrack(it, a, b) }

    private fun segDist(p: LatLng, a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * Math.cos(Math.toRadians(a.lat)); val ky = 110_540.0
        val bx = (b.lng - a.lng) * kx; val by = (b.lat - a.lat) * ky
        val x = (p.lng - a.lng) * kx; val y = (p.lat - a.lat) * ky
        val l2 = bx * bx + by * by
        val t = if (l2 < 1e-6) 0.0 else ((x * bx + y * by) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(x - t * bx, y - t * by)
    }

    private fun crossTrack(p: LatLng, a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * Math.cos(Math.toRadians(a.lat)); val ky = 110_540.0
        val bx = (b.lng - a.lng) * kx; val by = (b.lat - a.lat) * ky
        val x = (p.lng - a.lng) * kx; val y = (p.lat - a.lat) * ky
        return Math.abs(x * by - y * bx) / Math.sqrt(bx * bx + by * by)
    }

    @Test fun `a jog around a median is straightened`() {
        // East 200 m, step 6 m north over 10 m, east 40 m, step back, east 200 m.
        val poly = walk(90.0 to 200.0, 31.0 to 11.6, 90.0 to 40.0, 149.0 to 11.6, 90.0 to 200.0)
        val raw = maxOffsetFromLine(poly, poly.first(), poly.last())
        assertTrue("fixture has a jog ($raw m)", raw > 5.0)
        val out = RouteSmoothing.straightenJogs(poly)
        assertTrue("jog removed", maxOffsetFromLine(out, poly.first(), poly.last()) < 0.5)
        assertEquals(poly.first(), out.first()); assertEquals(poly.last(), out.last())
    }

    @Test fun `a real right turn keeps its corner`() {
        val poly = walk(90.0 to 200.0, 180.0 to 200.0)
        val corner = poly[20]
        val out = RouteSmoothing.straightenJogs(poly)
        assertTrue("corner kept", out.any { it.distanceTo(corner) < 0.5 })
        assertEquals(poly, out)
    }

    @Test fun `a gentle curve is not flattened`() {
        // 30 degrees of curve over 300 m, in 3-degree steps.
        val legs = (0 until 10).map { (90.0 + it * 3.0) to 30.0 }.toTypedArray()
        val poly = walk(90.0 to 100.0, *legs, 120.0 to 100.0)
        assertEquals(poly, RouteSmoothing.straightenJogs(poly))
    }

    @Test fun `a road that moves over for good is left alone`() {
        // A divided road starting: steps 8 m north and STAYS there (no return within the span).
        val poly = walk(90.0 to 200.0, 31.0 to 15.5, 90.0 to 300.0)
        assertEquals(poly, RouteSmoothing.straightenJogs(poly))
    }

    @Test fun `real Google lines change only in short stretches`() {
        val dir = java.io.File(javaClass.classLoader!!.getResource("google_lines")!!.toURI())
        var stretches = 0; var lines = 0
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".txt") }) {
            val poly = app.vela.core.data.google.PolylineCodec.decode(f.readText().trim())
            val out = RouteSmoothing.straightenJogs(poly)
            val lenIn = RouteProjection.cumulative(poly).last(); val lenOut = RouteProjection.cumulative(out).last()
            assertTrue("${f.name}: length ${lenIn} -> ${lenOut}", lenOut <= lenIn && lenIn - lenOut < lenIn * 0.005)
            assertTrue("${f.name}: every kept point is the router's", out.all { it in poly })
            val worst = poly.maxOf { v -> (0 until out.size - 1).minOf { k -> segDist(v, out[k], out[k + 1]) } }
            assertTrue("${f.name}: a router point is ${worst} m from the drawn line", worst <= RouteSmoothing.MAX_JOG_M)
            stretches += poly.size - out.size; lines++
        }
        println("ROUTESMOOTH lines=$lines removedVertices=$stretches")
    }

    @Test fun `the F Street wobble is taken out`() {
        // The open router's line near a Davis fixture junction: 338, 17, 347, 315, 346, 357 degrees
        // over 7-15 m segments (fetched 2026-10-03).
        val poly = listOf(
            LatLng(38.550844, -121.738171), LatLng(38.551078, -121.738368), LatLng(38.551294, -121.738494),
            LatLng(38.551551, -121.738625), LatLng(38.551609, -121.738602), LatLng(38.551699, -121.738628),
            LatLng(38.551789, -121.738741), LatLng(38.55192, -121.738782), LatLng(38.551936, -121.738783),
            LatLng(38.55205, -121.738784), LatLng(38.552125, -121.738796), LatLng(38.552199, -121.738823),
        )
        val out = RouteSmoothing.removeZigzags(poly)
        assertTrue("wobble vertices dropped (${poly.size} -> ${out.size})", out.size <= poly.size - 2)
        for (i in 1 until out.size - 1) {
            val t = ((out[i].let { b -> b.bearingTo(out[i + 1]) - out[i - 1].bearingTo(b) } + 540.0) % 360.0) - 180.0
            assertTrue("turn at $i is ${t}", Math.abs(t) < 25.0)
        }
    }

    @Test fun `a roundabout keeps every vertex`() {
        // A 15 m radius circle in 12 steps: every turn the same way.
        val c = start
        val ring = (0..12).map { c.destinationPoint(15.0, it * 30.0) }
        assertEquals(ring, RouteSmoothing.removeZigzags(ring))
    }

    @Test fun `gentle bends are rounded a little, real corners and the ends stay`() {
        // A gentle curve in 40 m pieces turning 15 degrees each, then a right-angle corner.
        val poly = walk(90.0 to 40.0, 105.0 to 40.0, 120.0 to 40.0, 135.0 to 40.0, 225.0 to 120.0, step = 40.0)
        val out = RouteSmoothing.roundBends(poly)
        assertEquals(poly.first(), out.first()); assertEquals(poly.last(), out.last())
        val corner = poly[4] // where 135 becomes 225: a 90 degree turn
        assertTrue("corner kept", out.any { it.distanceTo(corner) < 0.01 })
        val worst = poly.maxOf { v -> (0 until out.size - 1).minOf { k -> segDist(v, out[k], out[k + 1]) } }
        assertTrue("moves the line at most ~1.5 m ($worst)", worst < 1.5)
        assertTrue("rounded (more points)", out.size > poly.size)
    }

    @Test fun `a traffic circle bulge on a straight street is drawn straight, a median split is not`() {
        // East 200 m, then half of a 5 m ring (a residential traffic circle), then east 200 m.
        val before = walk(90.0 to 200.0)
        val c = before.last().destinationPoint(5.0, 90.0)
        val ring = (1 until 12).map { k -> c.destinationPoint(5.0, 270.0 - k * 15.0) }
        val after = walk(90.0 to 200.0).let { w -> val off = c.destinationPoint(5.0, 90.0); w.map { p -> off.destinationPoint(p.distanceTo(w.first()), 90.0) } }
        val poly = before + ring + after
        assertTrue("fixture bulges", maxOffsetFromLine(poly, poly.first(), poly.last()) > 4.0)
        val out = RouteSmoothing.straightenCircles(poly)
        assertTrue("straightened", maxOffsetFromLine(out, poly.first(), poly.last()) < 0.5)
        // The 40 m median jog of the test above is longer than a circle: left alone.
        val median = walk(90.0 to 200.0, 31.0 to 11.6, 90.0 to 40.0, 149.0 to 11.6, 90.0 to 200.0)
        assertEquals(median, RouteSmoothing.straightenCircles(median))
    }

    @Test fun `a distance on the line maps to the same place on its straightened copy`() {
        // Three median jogs near the start (each 10 m to the side), then 3 km of straight road.
        val jog = arrayOf(31.0 to 11.6, 90.0 to 40.0, 149.0 to 11.6)
        val poly = walk(90.0 to 200.0, *jog, 90.0 to 200.0, *jog, 90.0 to 200.0, *jog, 90.0 to 3000.0)
        val smooth = RouteSmoothing.straightenJogs(poly)
        assertTrue("the jogs were straightened", smooth.size < poly.size)
        val at = RouteSmoothing.alongOriginal(poly, smooth)!!
        val cum = DoubleArray(smooth.size).also { for (k in 1 until smooth.size) it[k] = it[k - 1] + smooth[k - 1].distanceTo(smooth[k]) }
        val polyCum = DoubleArray(poly.size).also { for (k in 1 until poly.size) it[k] = it[k - 1] + poly[k - 1].distanceTo(poly[k]) }
        fun at(line: List<LatLng>, c: DoubleArray, m: Double): LatLng {
            var k = 1
            while (k < c.size - 1 && c[k] < m) k++
            val t = ((m - c[k - 1]) / (c[k] - c[k - 1])).coerceIn(0.0, 1.0)
            return LatLng(line[k - 1].lat + (line[k].lat - line[k - 1].lat) * t, line[k - 1].lng + (line[k].lng - line[k - 1].lng) * t)
        }
        // Past the jogs the two lines are the same road, so the same distance is the same point.
        for (m in listOf(1200.0, 2000.0, 3500.0)) {
            val onLine = at(poly, polyCum, m)
            val mapped = at(smooth, cum, RouteSmoothing.mapAlong(at, cum, m))
            assertTrue("at $m m the copy is ${onLine.distanceTo(mapped)} m away", onLine.distanceTo(mapped) < 0.5)
        }
        // One ratio for the whole route, as it was, leaves it meters ahead after the jogs.
        val ratio = cum.last() / polyCum.last()
        val drift = at(poly, polyCum, 1200.0).distanceTo(at(smooth, cum, 1200.0 * ratio))
        assertTrue("one ratio drifts $drift m (lengths ${polyCum.last()} and ${cum.last()})", drift > 3.0)
        // Inside a jog the copy is beside the line: the jog's width away, and no further.
        val mid = 200.0 + 11.6 + 20.0
        val beside = at(poly, polyCum, mid).distanceTo(at(smooth, cum, RouteSmoothing.mapAlong(at, cum, mid)))
        assertTrue("inside the jog the copy is $beside m away", beside in 9.0..11.0)
    }

    @Test fun `a line that is not a copy has no map`() {
        val poly = walk(90.0 to 200.0)
        val other = walk(0.0 to 200.0)
        assertEquals(null, RouteSmoothing.alongOriginal(poly, other.drop(1)))
    }
}
