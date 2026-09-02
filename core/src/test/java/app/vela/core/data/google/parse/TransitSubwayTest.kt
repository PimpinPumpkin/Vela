package app.vela.core.data.google.parse

import app.vela.core.model.TransitMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subway legs from Google's transit payload (issue #284).
 *
 * The fixtures are the REAL `[14]` nodes captured live from a Manhattan-to-Wall-Street trip on
 * 2026-08-31, not invented shapes. Two things were wrong and both are pinned here:
 *
 *  1. A subway line is drawn as an agency BULLET, so its `[14]` entry carries `null` where a bus
 *     carries a text pill and puts the identity in an icon instead. Vela only looked for the pill,
 *     found no line, and fell through to `mode = WALK` - so every subway leg rendered as an empty
 *     walking step while buses were fine.
 *  2. The vehicle class was guessed from every string in the leg, so a "2" train to FLATBUSH Av
 *     matched "bus" (checked first) and reported a subway as a bus.
 */
class TransitSubwayTest {

    private fun node(raw: String): JsonElement = Json.parseToJsonElement(raw)

    // Live capture: an MTA "2" train leg. Note [0][1] is null - no text badge exists.
    private val subwayBadges = node(
        """[[5,null,[3,"us-ny-mta/2.png",null,"2 Line",[["//maps.gstatic.com/mapfiles/transit/iw2/svg/us-ny-mta/2.svg",1,[44,44],null,0]]]],[7,["Flatbush Av-Brooklyn College"]]]"""
    )

    // Live capture: an M101 bus leg on the same trip - a text pill, which always worked.
    private val busBadges = node(
        """[[4,null,[3,"bus2.png",null,"Bus",[["//maps.gstatic.com/mapfiles/transit/iw2/svg/bus2.svg",1,[44,44],null,0]]]],[5,["M101",1,"#1d59b3","#ffffff"]],[7,["Limited East Village 3 Av-6 St via Lex"]]]"""
    )

    /** A whole leg carries the generic vehicle icon, which is what names the mode. */
    private fun legWith(badges: String, vehicleIcon: String, extraText: String) = node(
        """[[null,null,null,null,null,null,null,null,null,null,null,null,null,null,$badges],
            null,null,null,null,
            [["$extraText",null,null,null,[null,null,40.7,-74.0]],["End",null,null,null,[null,null,40.6,-74.0]],3,null,null,null,null,[]],
            "$vehicleIcon"]"""
    )

    @Test fun `a subway line drawn as a bullet is still a line, not a walk`() {
        val lines = TransitParser.parseLinesForTest(subwayBadges, null)
        assertTrue("a bullet-only line must still be found", lines.isNotEmpty())
        assertEquals("the line name comes from the agency icon, not the localized label", "2", lines[0].name)
    }

    @Test fun `a bus keeps its text pill and its colours`() {
        val lines = TransitParser.parseLinesForTest(busBadges, null)
        assertEquals("M101", lines[0].name)
        assertEquals("#1d59b3", lines[0].colorHex)
    }

    // The generic vehicle icon must not be mistaken for a line: a bus would become line "bus2".
    @Test fun `the generic vehicle icon never becomes a line name`() {
        val lines = TransitParser.parseLinesForTest(busBadges, null)
        assertTrue("no line may be named after a mode icon", lines.none { it.name.startsWith("bus") })
    }

    @Test fun `a subway to Flatbush is a subway, not a bus`() {
        val leg = legWith(subwayBadges.toString(), "subway2.png", "Flatbush Av-Brooklyn College")
        val mode = TransitParser.guessModeForTest(leg)
        assertEquals("'Flatbush' must not decide the vehicle", TransitMode.SUBWAY, mode)
    }

    @Test fun `a bus leg is still a bus`() {
        val leg = legWith(busBadges.toString(), "bus2.png", "3 Av-6 St")
        assertEquals(TransitMode.BUS, TransitParser.guessModeForTest(leg))
    }

    // Place names that merely contain a mode word must not steer the guess at all.
    @Test fun `place names containing mode words are ignored`() {
        val leg = legWith(subwayBadges.toString(), "subway2.png", "Columbus Circle")
        assertEquals(TransitMode.SUBWAY, TransitParser.guessModeForTest(leg))
        val rail = legWith(subwayBadges.toString(), "rail.png", "Bushwick Av")
        assertEquals(TransitMode.TRAIN, TransitParser.guessModeForTest(rail))
    }

    @Test fun `the mode falls back to generic rather than guessing from prose`() {
        val leg = legWith("""[[7,["Somewhere"]]]""", "unknown-thing.png", "Busy Street")
        assertNotNull(TransitParser.guessModeForTest(leg))
        assertEquals(TransitMode.GENERIC, TransitParser.guessModeForTest(leg))
    }
}
