package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blue turn bubble: its tail on the corner, the bubble reaching away from the turn. */
class TurnCalloutTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val k = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / k)

    /** North 300 m, then [type] 200 m: west for a left, east for a right. */
    private fun line(left: Boolean) = (0..30).map { pt(0.0, it * 10.0) } + (1..20).map { pt(if (left) -it * 10.0 else it * 10.0, 300.0) }

    private fun turn(type: ManeuverType) = Maneuver(type, "Turn onto Oak Avenue", pt(0.0, 300.0), 200.0, 20.0, road = "Oak Avenue")

    @Test fun `a left turn's bubble reaches right from the corner`() {
        val c = turnCalloutFor(turn(ManeuverType.TURN_LEFT), line(left = true), "Oak Avenue", emptyList())!!
        assertEquals(TurnCallout.RIGHT, c.side)
        assertTrue(c.at.distanceTo(pt(0.0, 300.0)) < 1.0)
        assertEquals(TurnCallout.LEFT, turnCalloutFor(turn(ManeuverType.SHARP_RIGHT), line(left = false), "Oak Avenue", emptyList())!!.side)
    }

    @Test fun `a light on the corner moves the tail along the cross street away from the turn`() {
        val c = turnCalloutFor(turn(ManeuverType.TURN_LEFT), line(left = true), "Oak Avenue", listOf(pt(0.0, 300.0)))!!
        // The new street runs west; the tail goes east, off the arrow, clear of the light's icon.
        assertTrue("${c.at}", c.at.lng > pt(0.0, 300.0).lng)
        assertEquals(20.0, c.at.distanceTo(pt(0.0, 300.0)), 1.0)
    }

    @Test fun `a roundabout exit keeps its bubble over its tail, stepping into the new street`() {
        val c = turnCalloutFor(turn(ManeuverType.EXIT_ROUNDABOUT), line(left = false), "Oak Avenue", listOf(pt(0.0, 300.0)))!!
        assertEquals(TurnCallout.CENTER, c.side)
        assertTrue("${c.at}", c.at.lng > pt(0.0, 300.0).lng)
    }
}
