package app.vela.core.data

import app.vela.core.data.PaintedRoads.Kind
import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintedRoadsTest {
    private val line = listOf(LatLng(38.545, -121.740), LatLng(38.546, -121.740))
    private fun marks(vararg tags: Pair<String, String>) = PaintedRoads.build(listOf(PaintedRoads.Way(mapOf(*tags), line)))
    private fun offsets(m: List<PaintedRoads.Mark>, k: Kind) = m.filter { it.kind == k }.map { it.offsetM }.sorted()

    @Test fun `two-way two lanes is one center line`() {
        val m = marks("highway" to "secondary", "lanes" to "2")
        assertEquals(listOf(0.0), offsets(m, Kind.CENTER))
        assertTrue(offsets(m, Kind.LANE).isEmpty())
    }

    @Test fun `two-way four lanes has a center and one divider each side`() {
        val m = marks("highway" to "primary", "lanes" to "4")
        assertEquals(listOf(0.0), offsets(m, Kind.CENTER))
        assertEquals(listOf(-PaintedRoads.LANE_M, PaintedRoads.LANE_M), offsets(m, Kind.LANE))
    }

    @Test fun `uneven split moves the center line`() {
        val m = marks("highway" to "primary", "lanes" to "3", "lanes:forward" to "2", "lanes:backward" to "1")
        // 3 lanes, 9.9 m: backward lane on the left, so the center sits one lane in from the left edge.
        assertEquals(-1.65, offsets(m, Kind.CENTER).single(), 1e-9)
        assertEquals(1.65, offsets(m, Kind.LANE).single(), 1e-9)
    }

    @Test fun `one-way three lanes has two dividers and no center`() {
        val m = marks("highway" to "secondary", "oneway" to "yes", "lanes" to "3")
        assertTrue(offsets(m, Kind.CENTER).isEmpty())
        assertEquals(listOf(-1.65, 1.65), offsets(m, Kind.LANE).map { Math.round(it * 100) / 100.0 })
    }

    @Test fun `an untagged residential street gets no lines`() {
        assertTrue(marks("highway" to "residential").none { it.kind == Kind.CENTER || it.kind == Kind.LANE })
    }

    @Test fun `an untagged two-way street with bike lanes gets a center line`() {
        val m = marks("highway" to "residential", "cycleway" to "lane")
        assertEquals(listOf(0.0), offsets(m, Kind.CENTER))
        assertEquals(2, offsets(m, Kind.BIKE).size)
    }

    @Test fun `bike lane on a one-way is on the right`() {
        val m = marks("highway" to "tertiary", "oneway" to "yes", "lanes" to "1", "cycleway" to "lane")
        assertEquals(listOf(PaintedRoads.LANE_M / 2 + PaintedRoads.BIKE_OUT_M), offsets(m, Kind.BIKE))
    }

    @Test fun `unmarked crossings are not painted`() {
        assertTrue(marks("highway" to "footway", "footway" to "crossing", "crossing" to "unmarked").isEmpty())
        assertEquals(1, marks("highway" to "footway", "footway" to "crossing", "crossing" to "uncontrolled").size)
        assertEquals(1, marks("highway" to "footway", "footway" to "crossing", "crossing:markings" to "zebra").size)
    }

    @Test fun `markings stop short of an intersection but the surface does not`() {
        // A two-way street with bike lanes crossed halfway by a differently named street.
        val a = LatLng(38.545, -121.740); val mid = LatLng(38.5455, -121.740); val b = LatLng(38.546, -121.740)
        val main = PaintedRoads.Way(mapOf("highway" to "tertiary", "name" to "Main", "cycleway" to "lane"), listOf(a, mid, b))
        val cross = PaintedRoads.Way(mapOf("highway" to "residential", "name" to "Cross"), listOf(LatLng(38.5455, -121.7405), mid, LatLng(38.5455, -121.7395)))
        val m = PaintedRoads.build(listOf(main, cross))
        val centers = m.filter { it.kind == Kind.CENTER }
        assertEquals("one center piece each side of the junction", 2, centers.size)
        for (c in centers) assertTrue(c.points.none { it == mid })
        val surface = m.single { it.kind == Kind.SURFACE }
        assertEquals(listOf(a, mid, b), surface.points)
    }
}
