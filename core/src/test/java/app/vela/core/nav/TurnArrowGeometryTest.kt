package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
import app.vela.core.model.bearingTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turn arrows (issue #804) on routes laid out near Davis (latitude 38.54). */
class TurnArrowGeometryTest {
    private val a = LatLng(38.5400, -121.7400)
    private val corner = a.destinationPoint(200.0, 0.0)
    private val b = corner.destinationPoint(200.0, 90.0)
    private fun m(type: ManeuverType, at: LatLng) = Maneuver(type, "", at, 0.0, 0.0)
    private fun route(poly: List<LatLng>, vararg ms: Maneuver) = Route(
        polyline = poly,
        legs = listOf(RouteLeg(400.0, 60.0, null, ms.toList())),
        distanceMeters = 400.0, durationSeconds = 60.0, durationInTrafficSeconds = null,
    )
    private val lShape = route(listOf(a, corner, b), m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, corner), m(ManeuverType.ARRIVE, b))

    @Test fun oneArrowThroughTheCorner() {
        val arrows = TurnArrowGeometry.arrows(lShape, 0)
        assertEquals(1, arrows.size)
        val arrow = arrows[0]
        assertEquals(TurnArrowGeometry.BEFORE_M, arrow.points.first().distanceTo(corner), 2.0)
        assertEquals(TurnArrowGeometry.AFTER_M, arrow.points.last().distanceTo(corner), 2.0)
        // Rounded: the arc's middle is 5 m x (sqrt 2 - 1) inside the corner, and no point is the corner.
        val nearest = arrow.points.minOf { it.distanceTo(corner) }
        assertEquals(TurnArrowGeometry.ROUND_M * (Math.sqrt(2.0) - 1), nearest, 0.3)
        assertTrue("an arc, not a fold", arrow.points.size >= 10)
        assertEquals(90.0, arrow.endBearingDeg, 3.0)
        assertEquals(200.0 - TurnArrowGeometry.BEFORE_M, arrow.fromM, 0.5)
        assertEquals(200.0 + TurnArrowGeometry.AFTER_M, arrow.toM, 0.5)
    }

    /** The share of the shaft before a point on the line, for cutting it at the car. */
    @Test fun theShaftIsCutByMetersAlongTheLine() {
        val arrow = TurnArrowGeometry.arrows(lShape, 0).single()
        assertEquals(0.0, arrow.fraction(0.0), 1e-9)
        assertEquals(0.0, arrow.fraction(arrow.fromM), 1e-9)
        assertEquals(1.0, arrow.fraction(arrow.toM), 1e-9)
        assertEquals(1.0, arrow.fraction(arrow.toM + 50.0), 1e-9)
        assertEquals("the corner is halfway along a symmetric arrow", 0.5, arrow.fraction(200.0), 0.01)
        // Before the arc the shaft and the line run together, meter for meter.
        assertEquals(10.0 / arrow.lengthM, arrow.fraction(arrow.fromM + 10.0), 0.002)
        var last = -1.0
        var m = arrow.fromM
        while (m <= arrow.toM) { val f = arrow.fraction(m); assertTrue(f >= last); last = f; m += 0.5 }
        assertTrue("the arc is shorter than the corner it cuts", arrow.lengthM < arrow.toM - arrow.fromM)
    }

    @Test fun arrowsStartAtTheStepInHand() {
        assertEquals(0, TurnArrowGeometry.arrows(lShape, 2).size)
        assertEquals(1, TurnArrowGeometry.arrows(lShape, 1).size)
    }

    @Test fun aStraightStepGetsNone() {
        val straight = route(listOf(a, corner, b), m(ManeuverType.DEPART, a), m(ManeuverType.STRAIGHT, corner), m(ManeuverType.ARRIVE, b))
        assertEquals(0, TurnArrowGeometry.arrows(straight, 0).size)
    }

    /** A keep-left whose split lies 60 m past the maneuver point: the arrow runs on through the split. */
    @Test fun aForkPastThePointRunsThroughTheSplit() {
        val split = a.destinationPoint(160.0, 0.0)
        val end = split.destinationPoint(120.0, 335.0)
        val point = a.destinationPoint(100.0, 0.0)
        val fork = route(listOf(a, split, end), m(ManeuverType.DEPART, a), m(ManeuverType.KEEP_LEFT, point), m(ManeuverType.ARRIVE, end))
        val arrows = TurnArrowGeometry.arrows(fork, 0)
        assertEquals(1, arrows.size)
        val arrow = arrows[0]
        assertEquals(TurnArrowGeometry.BEFORE_M, arrow.points.first().distanceTo(point), 2.0)
        assertTrue("through the split", arrow.points.any { it.distanceTo(split) < 0.5 })
        assertEquals(TurnArrowGeometry.AFTER_M, arrow.points.last().distanceTo(split), 2.0)
        assertEquals(335.0, arrow.endBearingDeg, 3.0)
    }

    /** A lane change in the line just before the corner is a smooth step in the arrow, never a kink. */
    @Test fun aLaneChangeIsNoKink() {
        val j1 = a.destinationPoint(280.0, 0.0).destinationPoint(1.0, 90.0)
        val j2 = a.destinationPoint(290.0, 0.0).destinationPoint(1.0, 90.0)
        val c = a.destinationPoint(300.0, 0.0)
        val e = c.destinationPoint(100.0, 90.0)
        val jog = route(listOf(a, j1, j2, c, e), m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, c), m(ManeuverType.ARRIVE, e))
        val arrow = TurnArrowGeometry.arrows(jog, 0).single()
        for (k in 1 until arrow.points.size - 1) {
            val b0 = arrow.points[k - 1].bearingTo(arrow.points[k])
            val b1 = arrow.points[k].bearingTo(arrow.points[k + 1])
            val bend = Math.abs(((b1 - b0 + 540.0) % 360.0) - 180.0)
            assertTrue("vertex $k bends $bend", bend <= TurnArrowGeometry.ARC_STEP_DEG + 0.5)
        }
        assertEquals(90.0, arrow.endBearingDeg, 3.0)
    }

    /** A road that weaves a meter or two either way: the shaft weaves with it. */
    @Test fun aWavyRoadIsFollowed() {
        val wave = (0..60).map { i -> a.destinationPoint(i * 5.0, 0.0).destinationPoint(1.5 * Math.sin(i * 5.0 / 40.0 * 2 * Math.PI), 90.0) }
        val c = wave.last()
        val e = c.destinationPoint(100.0, 90.0)
        val line = wave + listOf(e)
        val r = route(line, m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, c), m(ManeuverType.ARRIVE, e))
        val cum = RouteProjection.cumulative(line)
        val arrow = TurnArrowGeometry.arrows(r, 0).single()
        val k = arrow.points.size / 4 // on the arm before the corner
        assertTrue("on the line", RouteProjection.alongMeters(line, cum, arrow.points[k], 0.6) != null)
    }

    /** The arrow is cut from the line the map draws, which can sit meters from the guidance line. */
    @Test fun theArrowRidesTheDrawnLine() {
        val r = route(listOf(a, corner, b), m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, corner), m(ManeuverType.ARRIVE, b))
        val drawn = listOf(a, corner, b).map { it.destinationPoint(4.0, 315.0) }
        val dc = RouteProjection.cumulative(drawn)
        val arrow = TurnArrowGeometry.arrows(r, 0, drawn).single()
        // Within the rounded corner's inset of the drawn line, and its ends on it.
        assertTrue(arrow.points.all { RouteProjection.alongMeters(drawn, dc, it, 2.2) != null })
        assertTrue(RouteProjection.alongMeters(drawn, dc, arrow.points.first(), 0.5) != null)
        assertTrue(RouteProjection.alongMeters(drawn, dc, arrow.points.last(), 0.5) != null)
    }

    /** On a curve the shaft stays on the line: no segment's middle is more than 2 m off it. */
    @Test fun aCurvedTurnStaysOnTheLine() {
        val center = a.destinationPoint(200.0, 0.0).destinationPoint(25.0, 90.0)
        val arc = (0..18).map { center.destinationPoint(25.0, 270.0 + it * 5.0) }
        val line = listOf(a) + arc + listOf(arc.last().destinationPoint(150.0, 90.0))
        val r = route(line, m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, arc[9]), m(ManeuverType.ARRIVE, line.last()))
        val cum = RouteProjection.cumulative(line)
        val arrow = TurnArrowGeometry.arrows(r, 0).single()
        for (k in 1 until arrow.points.size) {
            val p0 = arrow.points[k - 1]; val p1 = arrow.points[k]
            val mid = LatLng((p0.lat + p1.lat) / 2, (p0.lng + p1.lng) / 2)
            assertTrue("segment $k off the line", RouteProjection.alongMeters(line, cum, mid, 2.0) != null)
        }
    }
}
