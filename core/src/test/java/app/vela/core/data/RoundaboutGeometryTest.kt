package app.vela.core.data

import app.vela.core.data.RouteGeometry.RbStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roundabout glyph geometry (issue #259). The bearings below are REAL, captured from the FOSSGIS
 * OSRM server for public landmarks in each traffic convention, so this pins the derivation against
 * data rather than against my reading of the docs.
 */
class RoundaboutGeometryTest {

    private fun enter(before: Double, after: Double) = RbStep("rotary", before, after)
    private fun exit(before: Double, after: Double) = RbStep("exit rotary", before, after)

    // Place Charles de Gaulle, Paris - right-hand traffic. Captured: enter 56 -> 140,
    // exit ... -> 115.
    @Test fun `right-hand traffic reads as counter-clockwise`() {
        val g = RouteGeometry.roundaboutGeoms(listOf(enter(56.0, 140.0), exit(29.0, 115.0)))
        assertFalse("entry veers right, so traffic circulates counter-clockwise", g[0]!!.clockwise)
        assertEquals(59.0, g[0]!!.exitAngleDeg, 0.001) // exit road bears 59 deg right of the approach
    }

    // Milton Keynes, UK - left-hand traffic. Captured entries: 123 -> 99, 43 -> 351, 53 -> 22.
    @Test fun `left-hand traffic reads as clockwise`() {
        assertTrue(RouteGeometry.roundaboutGeoms(listOf(enter(123.0, 99.0), exit(99.0, 73.0)))[0]!!.clockwise)
        assertTrue(RouteGeometry.roundaboutGeoms(listOf(enter(43.0, 351.0), exit(109.0, 68.0)))[0]!!.clockwise)
        assertTrue(RouteGeometry.roundaboutGeoms(listOf(enter(53.0, 22.0), exit(22.0, 340.0)))[0]!!.clockwise)
    }

    @Test fun `exit angle is measured off the approach road and wraps correctly`() {
        // 43 -> exit road 68: a 25 deg bear right, even though you went round clockwise to get there.
        assertEquals(25.0, RouteGeometry.roundaboutGeoms(listOf(enter(43.0, 351.0), exit(109.0, 68.0)))[0]!!.exitAngleDeg, 0.001)
        // 53 -> 340: crosses north, so it must come out as -73, not +287.
        assertEquals(-73.0, RouteGeometry.roundaboutGeoms(listOf(enter(53.0, 22.0), exit(22.0, 340.0)))[0]!!.exitAngleDeg, 0.001)
    }

    @Test fun `both steps of the pair carry the same geometry`() {
        val g = RouteGeometry.roundaboutGeoms(listOf(enter(56.0, 140.0), exit(29.0, 115.0)))
        assertEquals(g[0], g[1])
    }

    @Test fun `steps around the roundabout are untouched`() {
        val g = RouteGeometry.roundaboutGeoms(
            listOf(RbStep("depart", 0.0, 90.0), enter(56.0, 140.0), exit(29.0, 115.0), RbStep("arrive", 115.0, null)),
        )
        assertNull(g[0])
        assertNull(g[3])
        assertEquals(false, g[1]!!.clockwise)
    }

    // Two roundabouts in a row on a distributor road: the first must NOT borrow the second's exit
    // step, or its glyph draws an exit belonging to a different junction.
    @Test fun `back to back roundabouts do not borrow each others exit`() {
        val g = RouteGeometry.roundaboutGeoms(
            listOf(enter(123.0, 99.0), exit(99.0, 73.0), enter(37.0, 8.0), exit(102.0, 73.0)),
        )
        assertEquals(-50.0, g[0]!!.exitAngleDeg, 0.001) // 123 -> 73
        assertEquals(36.0, g[2]!!.exitAngleDeg, 0.001) // 37 -> 73
    }

    @Test fun `an enter step with no exit step yields nothing`() {
        assertNull(RouteGeometry.roundaboutGeoms(listOf(enter(56.0, 140.0)))[0])
        assertNull(RouteGeometry.roundaboutGeoms(listOf(enter(56.0, 140.0), enter(20.0, 40.0)))[0])
    }

    // A roundabout entered dead straight gives an entry turn whose SIGN is noise, so the driving
    // side is genuinely unknown - the glyph must fall back to its neutral form rather than guess.
    @Test fun `a dead straight entry does not guess a driving side`() {
        assertNull(RouteGeometry.roundaboutGeoms(listOf(enter(90.0, 92.0), exit(92.0, 120.0)))[0])
    }

    @Test fun `missing bearings yield nothing`() {
        assertNull(RouteGeometry.roundaboutGeoms(listOf(RbStep("rotary", null, 140.0), exit(29.0, 115.0)))[0])
        assertNull(RouteGeometry.roundaboutGeoms(listOf(enter(56.0, 140.0), RbStep("exit rotary", 29.0, null)))[0])
    }

    @Test fun `bearing delta is signed and wraps`() {
        assertEquals(10.0, RouteGeometry.bearingDelta(355.0, 5.0), 0.001)
        assertEquals(-10.0, RouteGeometry.bearingDelta(5.0, 355.0), 0.001)
        assertEquals(180.0, RouteGeometry.bearingDelta(0.0, 180.0), 0.001)
        assertEquals(0.0, RouteGeometry.bearingDelta(90.0, 90.0), 0.001)
    }
}
