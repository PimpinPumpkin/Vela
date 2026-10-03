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

    // A north-running street crossed at its far end by an east-west street (a junction).
    private val south = LatLng(38.5450, -121.7400)
    private val junction = LatLng(38.5460, -121.7400)
    private fun crossStreet() = PaintedRoads.Way(mapOf("highway" to "residential", "name" to "Cross"),
        listOf(LatLng(38.5460, -121.7410), junction, LatLng(38.5460, -121.7390)))

    @Test fun `turn arrows sit in their lanes before the junction`() {
        val main = PaintedRoads.Way(mapOf("highway" to "secondary", "name" to "Main", "oneway" to "yes",
            "turn:lanes" to "left|through|through;right"), listOf(south, junction))
        val arrows = PaintedRoads.build(listOf(main, crossStreet())).filter { it.kind == Kind.ARROW }
        assertEquals(listOf("arrow-left", "arrow-through", "arrow-through-right"), arrows.map { it.icon })
        // Heading north; lanes left to right = west to east; all short of the junction.
        assertTrue(arrows.zipWithNext().all { (a, b) -> a.points[0].lng < b.points[0].lng })
        assertTrue(arrows.all { it.points[0].lat < junction.lat && Math.abs(it.rotDeg) < 1.0 || Math.abs(it.rotDeg - 360) < 1.0 })
    }

    @Test fun `a signal at a junction gets a stop line on the approaching half`() {
        val main = PaintedRoads.Way(mapOf("highway" to "secondary", "name" to "Main", "lanes" to "2"), listOf(south, junction))
        val sig = PaintedRoads.Node(mapOf("highway" to "traffic_signals"), junction)
        val stops = PaintedRoads.build(listOf(main, crossStreet()), listOf(sig)).filter { it.kind == Kind.STOP }
        // Main's northbound approach: the line sits ~8.5 m south of the junction, across the east half.
        val line = stops.single { it.points.all { p -> p.lat < junction.lat - 0.00005 } }
        assertTrue(line.points.all { it.lng >= -121.74001 })
        assertTrue(line.points.any { it.lng > -121.73999 })
    }

    @Test fun `a marked crossing node becomes a crosswalk across the road`() {
        val mid = LatLng(38.5455, -121.7400)
        val main = PaintedRoads.Way(mapOf("highway" to "secondary", "name" to "Main", "lanes" to "2"), listOf(south, mid, junction))
        val x = PaintedRoads.Node(mapOf("highway" to "crossing", "crossing" to "marked"), mid)
        val cw = PaintedRoads.build(listOf(main), listOf(x)).filter { it.kind == Kind.CROSSWALK }
        assertEquals(1, cw.size)
        assertTrue(cw[0].points[0].lng < -121.7400 && cw[0].points[1].lng > -121.7400)
    }

    @Test fun `a divided road gets a median between its halves`() {
        val off = 0.00025 // ~22 m apart
        val nb = PaintedRoads.Way(mapOf("highway" to "primary", "name" to "Blvd", "oneway" to "yes", "lanes" to "2"),
            listOf(LatLng(38.5400, -121.7400), LatLng(38.5450, -121.7400)))
        val sb = PaintedRoads.Way(mapOf("highway" to "primary", "name" to "Blvd", "oneway" to "yes", "lanes" to "2"),
            listOf(LatLng(38.5450, -121.7400 + off), LatLng(38.5400, -121.7400 + off)))
        val med = PaintedRoads.build(listOf(nb, sb)).filter { it.kind == Kind.MEDIAN }
        assertEquals(1, med.size)
        assertTrue(med[0].points.all { it.lng > -121.7400 && it.lng < -121.7400 + off })
        assertTrue("gap minus both carriageways", med[0].widthM in 10.0..16.0)
    }
}
