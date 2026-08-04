package app.vela.core.data

import app.vela.core.model.ManeuverType
import net.osmand.router.TurnType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the OsmAnd TurnType -> Vela ManeuverType mapping. The router side is exercised on-device
 *  against a real obf (JVM tests would need a fixture file); the mapping is the part that must
 *  never drift silently - a wrong type here mis-speaks or SILENCES a turn (CONTINUE is
 *  voice-silent in NavEngine). */
class ObfRouteEngineTest {

    private fun t(v: Int): TurnType = TurnType.valueOf(v, false)

    @Test
    fun `plain turns map to their maneuvers`() {
        assertEquals(ManeuverType.CONTINUE, ObfRouteEngine.obfType(t(TurnType.C)))
        assertEquals(ManeuverType.TURN_LEFT, ObfRouteEngine.obfType(t(TurnType.TL)))
        assertEquals(ManeuverType.SLIGHT_LEFT, ObfRouteEngine.obfType(t(TurnType.TSLL)))
        assertEquals(ManeuverType.SHARP_LEFT, ObfRouteEngine.obfType(t(TurnType.TSHL)))
        assertEquals(ManeuverType.TURN_RIGHT, ObfRouteEngine.obfType(t(TurnType.TR)))
        assertEquals(ManeuverType.SLIGHT_RIGHT, ObfRouteEngine.obfType(t(TurnType.TSLR)))
        assertEquals(ManeuverType.SHARP_RIGHT, ObfRouteEngine.obfType(t(TurnType.TSHR)))
        assertEquals(ManeuverType.KEEP_LEFT, ObfRouteEngine.obfType(t(TurnType.KL)))
        assertEquals(ManeuverType.KEEP_RIGHT, ObfRouteEngine.obfType(t(TurnType.KR)))
    }

    @Test
    fun `u-turns are never silenced`() {
        // Both u-turn codes must speak: CONTINUE is voice-silent in NavEngine, and a u-turn
        // mapped there would be swallowed whole (the GraphHopper path had this exact bug).
        assertEquals(ManeuverType.UTURN, ObfRouteEngine.obfType(t(TurnType.TU)))
        assertEquals(ManeuverType.UTURN, ObfRouteEngine.obfType(t(TurnType.TRU)))
    }

    @Test
    fun `roundabouts map by flag and carry their exit`() {
        val rb = TurnType.getExitTurn(3, 0f, false)
        assertEquals(ManeuverType.ROUNDABOUT, ObfRouteEngine.obfType(rb))
        assertEquals(3, rb.exitOut)
    }

    @Test
    fun `unknown codes speak rather than silence`() {
        assertEquals(ManeuverType.UNKNOWN, ObfRouteEngine.obfType(t(TurnType.OFFR)))
    }
}
