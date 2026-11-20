package app.vela.car

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThenStepTest {
    private fun turn(gapM: Double) = Maneuver(ManeuverType.TURN_RIGHT, "", LatLng(38.5449, -121.7405), gapM, 30.0)

    @Test fun `a turn that follows closely shows as then once the first is near`() {
        assertTrue(ManeuverMapper.thenShown(turn(200.0), 300.0))
    }

    @Test fun `a turn far after the next one is not a then`() {
        assertFalse(ManeuverMapper.thenShown(turn(3_000.0), 300.0))
    }

    @Test fun `a close pair still far ahead waits until the first turn is near`() {
        assertFalse(ManeuverMapper.thenShown(turn(200.0), 1_200.0))
    }
}
