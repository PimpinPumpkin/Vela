package app.vela.core

import app.vela.core.data.GraphHopperRouteEngine
import app.vela.core.model.ManeuverType
import com.graphhopper.util.Instruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device engine ([GraphHopperRouteEngine]) maps GraphHopper's `Instruction.sign` to Vela's
 * [ManeuverType] (arrow + haptic) and synthesizes the instruction text (GraphHopper ships none unless
 * handed a Translation). Mirrors `OsrmRouterTest` for the online router — same model, two backends.
 */
class GraphHopperRouterTest {
    @Test fun signsMapToVela() {
        assertEquals(ManeuverType.DEPART, GraphHopperRouteEngine.ghType(Instruction.CONTINUE_ON_STREET, first = true))
        assertEquals(ManeuverType.CONTINUE, GraphHopperRouteEngine.ghType(Instruction.CONTINUE_ON_STREET, first = false))
        assertEquals(ManeuverType.TURN_LEFT, GraphHopperRouteEngine.ghType(Instruction.TURN_LEFT, false))
        assertEquals(ManeuverType.TURN_RIGHT, GraphHopperRouteEngine.ghType(Instruction.TURN_RIGHT, false))
        assertEquals(ManeuverType.SLIGHT_LEFT, GraphHopperRouteEngine.ghType(Instruction.TURN_SLIGHT_LEFT, false))
        assertEquals(ManeuverType.SHARP_RIGHT, GraphHopperRouteEngine.ghType(Instruction.TURN_SHARP_RIGHT, false))
        assertEquals(ManeuverType.KEEP_RIGHT, GraphHopperRouteEngine.ghType(Instruction.KEEP_RIGHT, false))
        assertEquals(ManeuverType.ROUNDABOUT, GraphHopperRouteEngine.ghType(Instruction.USE_ROUNDABOUT, false))
        assertEquals(ManeuverType.ARRIVE, GraphHopperRouteEngine.ghType(Instruction.FINISH, false))
        assertEquals(ManeuverType.UTURN, GraphHopperRouteEngine.ghType(Instruction.U_TURN_UNKNOWN, false))
    }

    @Test fun phrasesReadNaturally() {
        assertEquals("Turn right onto 164th St SE", GraphHopperRouteEngine.ghPhrase(ManeuverType.TURN_RIGHT, "164th St SE"))
        assertEquals("Continue onto Main St", GraphHopperRouteEngine.ghPhrase(ManeuverType.CONTINUE, "Main St"))
        assertEquals("Head out on Elm St", GraphHopperRouteEngine.ghPhrase(ManeuverType.DEPART, "Elm St"))
        assertEquals("Make a U-turn onto Oak Ave", GraphHopperRouteEngine.ghPhrase(ManeuverType.UTURN, "Oak Ave"))
        assertEquals("Arrive at your destination", GraphHopperRouteEngine.ghPhrase(ManeuverType.ARRIVE, null))
    }

    /** Multi-region: a trip routes on the first installed region whose box covers BOTH endpoints. */
    @Test fun regionBoxCoversEndpoints() {
        // Seattle metro box [S, W, N, E]
        val s = 47.55; val w = -122.45; val n = 48.05; val e = -122.10
        assertTrue(GraphHopperRouteEngine.inBox(s, w, n, e, 47.86, -122.20)) // Silver Firs (Everett)
        assertTrue(GraphHopperRouteEngine.inBox(s, w, n, e, 47.66, -122.30)) // Seattle
        assertFalse(GraphHopperRouteEngine.inBox(s, w, n, e, 45.52, -122.68)) // Portland — out
        assertFalse(GraphHopperRouteEngine.inBox(s, w, n, e, 47.86, -121.50)) // east of box — out
    }
}
