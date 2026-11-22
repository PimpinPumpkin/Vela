package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

/**
 * Where a tapped point goes in an area being drawn (issue #846). Shapes are laid out in meters
 * east and north of a fixture point, so each case is easy to picture.
 */
class AreaInsertTest {

    private val davisLat = 38.5449
    private val davisLng = -121.7405

    /** Points given in meters (east, north) of ([lat0], [lng0]), as lat, lng, lat, lng... */
    private fun ll(vararg en: Pair<Double, Double>, lat0: Double = davisLat, lng0: Double = davisLng): List<Double> =
        en.flatMap { (e, n) -> listOf(lat0 + n / 111_320.0, lng0 + e / (111_320.0 * cos(Math.toRadians(lat0)))) }

    /** Adds each point the way the drawing bar does and returns the index each one took. */
    private fun traceIndexes(pts: List<Double>): List<Int> {
        val out = ArrayList<Double>()
        return pts.chunked(2).map { (lat, lng) ->
            val at = AreaInsert.indexFor(out, lat, lng)
            out.addAll(at * 2, listOf(lat, lng))
            at
        }
    }

    private val square = arrayOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)

    @Test fun `a square tapped in order stays in order`() {
        assertEquals(listOf(0, 1, 2, 3), traceIndexes(ll(*square)))
    }

    @Test fun `a concave L traced in order keeps its order`() {
        val l = ll(0.0 to 0.0, 200.0 to 0.0, 200.0 to 100.0, 100.0 to 100.0, 100.0 to 200.0, 0.0 to 200.0)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), traceIndexes(l))
    }

    @Test fun `a tap inside the area is added at the end, as a dent`() {
        val q = ll(50.0 to 50.0)
        assertEquals(4, AreaInsert.indexFor(ll(*square), q[0], q[1]))
    }

    @Test fun `a tap past the side the closing edge would cross goes into that side`() {
        // The triangle of the report, tapped below its base: at the end, the new side from the
        // top point down to the tap would cross the base.
        val tri = ll(0.0 to 0.0, 100.0 to 0.0, 50.0 to 100.0)
        val q = ll(50.0 to -40.0)
        assertEquals(1, AreaInsert.indexFor(tri, q[0], q[1]))
    }

    @Test fun `a tap right of a square joins its right side`() {
        val q = ll(140.0 to 50.0)
        assertEquals(2, AreaInsert.indexFor(ll(*square), q[0], q[1]))
    }

    @Test fun `a tap above a square joins its top side`() {
        val q = ll(50.0 to 140.0)
        assertEquals(3, AreaInsert.indexFor(ll(*square), q[0], q[1]))
    }

    @Test fun `a tap left of a square is added at the end, on the closing side`() {
        val q = ll(-40.0 to 50.0)
        assertEquals(4, AreaInsert.indexFor(ll(*square), q[0], q[1]))
    }

    @Test fun `fewer than three points always add at the end`() {
        val q = ll(50.0 to -40.0)
        assertEquals(0, AreaInsert.indexFor(emptyList(), q[0], q[1]))
        assertEquals(2, AreaInsert.indexFor(ll(0.0 to 0.0, 100.0 to 0.0), q[0], q[1]))
    }

    @Test fun `the same shape on the ground gets the same answer at any latitude`() {
        // 20 m east and 25 m south of the square's corner: the bottom side adds 54.6 m of
        // perimeter, the right side 58.6 m. Raw degrees at 60 N stretch east-west twice over and
        // would pick the right side.
        val corner = 120.0 to -25.0
        for (lat0 in listOf(0.0, davisLat, 60.0, -60.0)) {
            val q = ll(corner, lat0 = lat0, lng0 = 10.0)
            assertEquals("at $lat0", 1, AreaInsert.indexFor(ll(*square, lat0 = lat0, lng0 = 10.0), q[0], q[1]))
        }
    }

    // Plane coordinates from here: exact sides, on one line where the case needs it.

    private val sx = doubleArrayOf(0.0, 10.0, 10.0, 0.0)
    private val sy = doubleArrayOf(0.0, 0.0, 10.0, 10.0)

    @Test fun `a tap on a side goes into that side`() {
        assertEquals(1, AreaInsert.indexFor(sx, sy, 5.0, 0.0)) // bottom
        assertEquals(2, AreaInsert.indexFor(sx, sy, 10.0, 4.0)) // right
        assertEquals(4, AreaInsert.indexFor(sx, sy, 0.0, 6.0)) // the closing side
    }

    @Test fun `a tap on the line of a side but past its end does not fold back along it`() {
        // At the end the new side back to the first point would run along the bottom; into the
        // bottom the two new sides fold onto each other. The right side takes it cleanly.
        assertEquals(2, AreaInsert.indexFor(sx, sy, 15.0, 0.0))
    }

    @Test fun `a closing side that only passes through a corner counts as a crossing`() {
        // A 20 by 10 rectangle. From the last point (0, 10) to (30, -5) runs exactly through the
        // corner (20, 0), so the end is out; the right side adds the least.
        val rx = doubleArrayOf(0.0, 20.0, 20.0, 0.0)
        val ry = doubleArrayOf(0.0, 0.0, 10.0, 10.0)
        assertEquals(2, AreaInsert.indexFor(rx, ry, 30.0, -5.0))
    }

    @Test fun `an outline that already crosses itself takes the fewest new crossings, the end winning ties`() {
        // Bow ties, as a dragged point can leave. Here every place crosses one side: the end wins.
        assertEquals(4, AreaInsert.indexFor(doubleArrayOf(0.0, 0.0, 9.0, 7.0), doubleArrayOf(0.0, 4.0, 5.0, 6.0), 5.0, 6.0))
        // Here the end crosses two and two sides cross one: the one that adds less wins.
        assertEquals(3, AreaInsert.indexFor(doubleArrayOf(0.0, 5.0, 2.0, 2.0), doubleArrayOf(0.0, 9.0, 9.0, 2.0), 4.0, 4.0))
    }
}
