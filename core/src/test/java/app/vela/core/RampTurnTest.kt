package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class RampTurnTest {
    private fun m(type: ManeuverType, text: String, len: Double, road: String? = null, ref: String? = null) =
        Maneuver(type = type, instruction = text, distanceMeters = len, durationSeconds = 10.0, location = LatLng(38.54, -121.74), road = road, ref = ref)

    @Test
    fun `a nameless turn just before a merge names the road it leads to`() {
        val out = RouteGeometry.rampTurns(listOf(
            m(ManeuverType.TURN_LEFT, "Turn left", 386.0),
            m(ManeuverType.MERGE, "Merge onto Dwight D. Eisenhower Highway", 14000.0, road = "Dwight D. Eisenhower Highway", ref = "I 80"),
        ))
        assertEquals("Take the ramp on the left toward I 80", out[0].instruction)
        assertEquals(ManeuverType.TURN_LEFT, out[0].type)
    }

    @Test
    fun `a named turn, a far merge and a turn before another turn are left alone`() {
        val merge = m(ManeuverType.MERGE, "Merge onto I 80", 9000.0, ref = "I 80")
        assertEquals("Turn left onto 1st Street", RouteGeometry.rampTurns(listOf(m(ManeuverType.TURN_LEFT, "Turn left onto 1st Street", 200.0, road = "1st Street"), merge))[0].instruction)
        assertEquals("Turn left", RouteGeometry.rampTurns(listOf(m(ManeuverType.TURN_LEFT, "Turn left", 2500.0), merge))[0].instruction)
        assertEquals("Turn left", RouteGeometry.rampTurns(listOf(m(ManeuverType.TURN_LEFT, "Turn left", 200.0), m(ManeuverType.TURN_RIGHT, "Turn right onto B Street", 300.0, road = "B Street")))[0].instruction)
    }
}
