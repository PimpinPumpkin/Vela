package app.vela.core.data.naming

import app.vela.core.model.LatLng
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridDrawLineTest {
    private val start = LatLng(38.55, -121.74) // Davis fixture

    private fun walk(from: LatLng, brg: Double, m: Double, step: Double = 10.0): List<LatLng> {
        val out = mutableListOf(from)
        var left = m
        while (left > 1e-6) { val d = minOf(step, left); out += out.last().destinationPoint(d, brg); left -= d }
        return out
    }

    @Test fun `a shared run is drawn on the open route, not Google's line`() {
        val open = walk(start, 90.0, 600.0)
        // Google's line runs 3 m north of it, sparser.
        val google = walk(start.destinationPoint(3.0, 0.0), 90.0, 600.0, step = 75.0)
        val line = HybridRoute.drawLine(google, open, emptyList(), emptyMap())
        assertNotNull(line)
        assertTrue("every drawn point is on the open route", line!!.all { p -> open.minOf { it.distanceTo(p) } < 6.0 && p.lat < start.lat + 0.00001 })
    }

    @Test fun `a stretch with a matched shape is drawn on that shape, without one on Google's line`() {
        val open = walk(start, 90.0, 900.0)
        val google = walk(start.destinationPoint(2.0, 0.0), 90.0, 900.0, step = 50.0)
        val st = HybridRoute.Stretch(300.0, 600.0)
        val shape = walk(start.destinationPoint(300.0, 90.0), 90.0, 300.0, step = 5.0)
        val withShape = HybridRoute.drawLine(google, open, listOf(st), mapOf(st to shape))!!
        assertTrue(shape.all { s -> withShape.any { it.distanceTo(s) < 0.5 } })
        val without = HybridRoute.drawLine(google, open, listOf(st), emptyMap())!!
        // Google's own vertices inside the stretch are kept.
        val inside = google.filter { it.distanceTo(google.first()) in 320.0..580.0 }
        assertTrue(inside.isNotEmpty() && inside.all { g -> without.any { it.distanceTo(g) < 0.5 } })
    }

    @Test fun `a piece that does not line up keeps Google's line`() {
        // The open route goes somewhere else entirely: nothing of it is drawn.
        val open = walk(start, 0.0, 600.0)
        val google = walk(start, 90.0, 600.0, step = 50.0)
        val line = HybridRoute.drawLine(google, open, emptyList(), emptyMap())!!
        assertEquals(google.size, line.size)
    }
}
