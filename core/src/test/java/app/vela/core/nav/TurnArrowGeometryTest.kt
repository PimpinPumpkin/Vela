package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
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
        assertEquals(3, arrow.points.size)
        assertEquals(TurnArrowGeometry.BEFORE_M, arrow.points.first().distanceTo(corner), 2.0)
        assertEquals(TurnArrowGeometry.AFTER_M, arrow.points.last().distanceTo(corner), 2.0)
        assertTrue("corner is a vertex", arrow.points.any { it.distanceTo(corner) < 0.5 })
        assertEquals(90.0, arrow.endBearingDeg, 3.0)
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
        assertTrue("split is a vertex", arrow.points.any { it.distanceTo(split) < 0.5 })
        assertEquals(TurnArrowGeometry.AFTER_M, arrow.points.last().distanceTo(split), 2.0)
        assertEquals(335.0, arrow.endBearingDeg, 3.0)
    }

    /** A lane change in the line just before the corner is not a kink in the arrow. */
    @Test fun aLaneChangeIsSimplifiedAway() {
        val j1 = a.destinationPoint(280.0, 0.0).destinationPoint(1.0, 90.0)
        val j2 = a.destinationPoint(290.0, 0.0).destinationPoint(1.0, 90.0)
        val c = a.destinationPoint(300.0, 0.0)
        val e = c.destinationPoint(100.0, 90.0)
        val jog = route(listOf(a, j1, j2, c, e), m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, c), m(ManeuverType.ARRIVE, e))
        val arrow = TurnArrowGeometry.arrows(jog, 0).single()
        assertEquals(3, arrow.points.size)
        assertTrue("the corner, not the jog, is the vertex", arrow.points[1].distanceTo(c) < 0.5)
        assertEquals(90.0, arrow.endBearingDeg, 3.0)
    }

    /** The arrow is cut from the line the map draws, which can sit meters from the guidance line. */
    @Test fun theArrowRidesTheDrawnLine() {
        val r = route(listOf(a, corner, b), m(ManeuverType.DEPART, a), m(ManeuverType.TURN_RIGHT, corner), m(ManeuverType.ARRIVE, b))
        val drawn = listOf(a, corner, b).map { it.destinationPoint(4.0, 315.0) }
        val dc = RouteProjection.cumulative(drawn)
        val arrow = TurnArrowGeometry.arrows(r, 0, drawn).single()
        assertTrue(arrow.points.all { RouteProjection.alongMeters(drawn, dc, it, 0.5) != null })
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
