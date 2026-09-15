package app.vela.core

import app.vela.core.data.OfflinePhrases
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device router's instruction text (the obf engine synthesizes it; OsmAnd ships none in
 *  Vela's languages) and the region-box test. Mirrors `OsrmRouterTest` for the online router. */
class OfflinePhrasesTest {
    @Test fun phrasesReadNaturally() {
        assertEquals("Turn right onto the local street", OfflinePhrases.phrase(ManeuverType.TURN_RIGHT, "the local street"))
        assertEquals("Continue onto Main St", OfflinePhrases.phrase(ManeuverType.CONTINUE, "Main St"))
        assertEquals("Head out on Elm St", OfflinePhrases.phrase(ManeuverType.DEPART, "Elm St"))
        assertEquals("Make a U-turn onto Oak Ave", OfflinePhrases.phrase(ManeuverType.UTURN, "Oak Ave"))
        assertEquals("Arrive at your destination", OfflinePhrases.phrase(ManeuverType.ARRIVE, null))
        // Roundabouts thread the exit number so they read "take the Nth exit", not the generic "Enter the roundabout".
        assertEquals("At the roundabout, take the 2nd exit onto Elm St", OfflinePhrases.phrase(ManeuverType.ROUNDABOUT, "Elm St", 2))
    }

    /** Highway steps read like the OSRM path: sign destinations on ramps/forks/merges, and a fork
     *  that carries an exit number becomes the off-ramp phrase ("Take exit 72B toward ..."),
     *  Google's wording. Surface turns never pick these up (no dest/exit data). */
    @Test fun highwayPhrasesUseRefsAndDestinations() {
        assertEquals("Take exit 72B toward Sacramento", OfflinePhrases.phrase(ManeuverType.KEEP_RIGHT, null, dest = "Sacramento", exitNo = "72B"))
        assertEquals("Take the ramp toward I-80 East", OfflinePhrases.phrase(ManeuverType.RAMP_RIGHT, null, dest = "I-80 East"))
        assertEquals("Keep left toward Davis", OfflinePhrases.phrase(ManeuverType.KEEP_LEFT, null, dest = "Davis"))
        assertEquals("Merge onto I 80", OfflinePhrases.phrase(ManeuverType.MERGE, "I 80"))
        assertEquals("Turn right onto Elm St", OfflinePhrases.phrase(ManeuverType.TURN_RIGHT, "Elm St", dest = null, exitNo = null))
    }

    /** A trip routes on the installed regions whose boxes cover its endpoints. */
    @Test fun regionBoxCoversEndpoints() {
        val s = 38.30; val w = -122.00; val n = 38.90; val e = -121.20
        assertTrue(OfflinePhrases.inBox(s, w, n, e, 38.55, -121.74)) // Davis
        assertTrue(OfflinePhrases.inBox(s, w, n, e, 38.58, -121.49)) // Sacramento
        assertFalse(OfflinePhrases.inBox(s, w, n, e, 37.77, -122.42)) // San Francisco, out of box
        assertFalse(OfflinePhrases.inBox(s, w, n, e, 38.55, -120.50)) // east of box, out
    }
}
