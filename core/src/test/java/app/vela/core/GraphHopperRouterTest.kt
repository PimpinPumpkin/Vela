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
        // CONTINUE is voice-silent in NavEngine — nothing carrying a real driver action may map
        // to it. The old else-branch funnelled u-turns (±8) into CONTINUE, and a u-turn keeps its
        // road name, so the engine's silence would have swallowed it entirely.
        assertEquals(ManeuverType.UTURN, GraphHopperRouteEngine.ghType(Instruction.U_TURN_LEFT, false))
        assertEquals(ManeuverType.UTURN, GraphHopperRouteEngine.ghType(Instruction.U_TURN_RIGHT, false))
        assertEquals(ManeuverType.EXIT_ROUNDABOUT, GraphHopperRouteEngine.ghType(Instruction.LEAVE_ROUNDABOUT, false))
        assertEquals(ManeuverType.UNKNOWN, GraphHopperRouteEngine.ghType(Instruction.FERRY, false)) // spoken, never silenced
    }

    @Test fun phrasesReadNaturally() {
        assertEquals("Turn right onto the local street", GraphHopperRouteEngine.ghPhrase(ManeuverType.TURN_RIGHT, "the local street"))
        assertEquals("Continue onto Main St", GraphHopperRouteEngine.ghPhrase(ManeuverType.CONTINUE, "Main St"))
        assertEquals("Head out on Elm St", GraphHopperRouteEngine.ghPhrase(ManeuverType.DEPART, "Elm St"))
        assertEquals("Make a U-turn onto Oak Ave", GraphHopperRouteEngine.ghPhrase(ManeuverType.UTURN, "Oak Ave"))
        assertEquals("Arrive at your destination", GraphHopperRouteEngine.ghPhrase(ManeuverType.ARRIVE, null))
        // Roundabouts thread the exit number so they read "take exit N", not the generic "Enter the roundabout".
        assertEquals("At the roundabout, take exit 2 onto Elm St", GraphHopperRouteEngine.ghPhrase(ManeuverType.ROUNDABOUT, "Elm St", 2))
    }

    /** Highway steps read like the OSRM path now: sign destinations on ramps/forks/merges, and a
     *  fork that carries a motorway_junction exit number becomes the off-ramp phrase ("Take exit
     *  72B toward ..."), Google's wording. Surface turns never pick these up (no dest/exit data). */
    @Test fun highwayPhrasesUseRefsAndDestinations() {
        assertEquals(
            "Take exit 72B toward Sacramento",
            GraphHopperRouteEngine.ghPhrase(ManeuverType.KEEP_RIGHT, null, dest = "Sacramento", exitNo = "72B"),
        )
        assertEquals(
            "Take the ramp toward I-80 East",
            GraphHopperRouteEngine.ghPhrase(ManeuverType.RAMP_RIGHT, null, dest = "I-80 East"),
        )
        assertEquals(
            "Keep left toward Davis",
            GraphHopperRouteEngine.ghPhrase(ManeuverType.KEEP_LEFT, null, dest = "Davis"),
        )
        assertEquals("Merge onto I 80", GraphHopperRouteEngine.ghPhrase(ManeuverType.MERGE, "I 80"))
        // No destination/exit data (every surface street): byte-identical to the old phrases.
        assertEquals("Turn right onto Elm St", GraphHopperRouteEngine.ghPhrase(ManeuverType.TURN_RIGHT, "Elm St", dest = null, exitNo = null))
    }

    /**
     * The hand-built [GraphHopperRouteEngine.carModel] must stay byte-for-byte equivalent to the
     * `car.json` shipped inside the GraphHopper jar. The engine builds the model programmatically
     * (Jackson's record introspection of the jar's copy needs an API-34 reflection method absent on
     * older ART), and CH profiles are versioned by the model's contents - so any drift between the
     * two would silently fail every prebuilt-graph load. This test runs on the desktop JVM, where the
     * jar's own loader works, and fails loudly if a GraphHopper upgrade changes car.json.
     */
    @Test fun carModelMatchesJar() {
        val jar = com.graphhopper.util.GHUtility.loadCustomModelFromJar("car.json")
        val mine = GraphHopperRouteEngine.carModel()
        assertEquals(jar.distanceInfluence!!, mine.distanceInfluence!!, 0.0)
        assertEquals(jar.getPriority().toString(), mine.getPriority().toString())
        assertEquals(jar.getSpeed().toString(), mine.getSpeed().toString())
    }

    /** Multi-region: a trip routes on the first installed region whose box covers BOTH endpoints. */
    @Test fun regionBoxCoversEndpoints() {
        // the metro metro box [S, W, N, E]
        val s = 38.30; val w = -122.00; val n = 38.90; val e = -121.20
        assertTrue(GraphHopperRouteEngine.inBox(s, w, n, e, 38.55, -121.74)) // Davis
        assertTrue(GraphHopperRouteEngine.inBox(s, w, n, e, 38.58, -121.49)) // Sacramento
        assertFalse(GraphHopperRouteEngine.inBox(s, w, n, e, 37.77, -122.42)) // San Francisco, out of box
        assertFalse(GraphHopperRouteEngine.inBox(s, w, n, e, 38.55, -120.50)) // east of box, out
    }
}
