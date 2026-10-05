package app.vela.ui.map

import app.vela.core.model.LatLng
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBubblePointsTest {
    private fun line(vararg p: Pair<Double, Double>, per: Int = 40): List<LatLng> =
        p.toList().zipWithNext().flatMap { (a, b) -> (0 until per).map { k -> LatLng(a.first + (b.first - a.first) * k / per, a.second + (b.second - a.second) * k / per) } } + LatLng(p.last().first, p.last().second)

    private fun off(p: LatLng, route: List<LatLng>) = route.minOf { RouteBubblePoints.distM(p, it) }

    @Test
    fun `alternates that share their first half are labeled after they split`() {
        // The chosen route runs south; two alternates leave north together, then one turns east.
        val main = line(38.54 to -121.74, 38.50 to -121.70, 38.50 to -121.50)
        val a = line(38.54 to -121.74, 38.60 to -121.74, 38.60 to -121.60, 38.50 to -121.50)
        val b = line(38.54 to -121.74, 38.60 to -121.74, 38.66 to -121.70, 38.66 to -121.50, 38.50 to -121.50)
        val pts = RouteBubblePoints.of(listOf(main, a, b))
        assertTrue("a's label sits on a stretch b also runs", off(pts[1]!!, b) > 150.0)
        assertTrue("b's label sits on a stretch a also runs", off(pts[2]!!, a) > 150.0)
        assertTrue(off(pts[1]!!, main) > 150.0 && off(pts[2]!!, main) > 150.0)
    }

    @Test
    fun `one route gets its middle and a twin still gets a point`() {
        val r = line(38.54 to -121.74, 38.50 to -121.50)
        assertTrue(RouteBubblePoints.of(listOf(r)).single() != null)
        assertTrue(RouteBubblePoints.of(listOf(r, r)).all { it != null })
    }
}
