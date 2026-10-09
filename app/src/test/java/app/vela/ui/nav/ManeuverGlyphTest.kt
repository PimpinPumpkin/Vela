package app.vela.ui.nav

import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverGlyphTest {
    @Test
    fun `ramps forks and keeps point only the way the driver goes`() {
        for (t in listOf(ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.SLIGHT_LEFT)) {
            assertEquals(t.name, ManeuverGlyph.SLIGHT_LEFT, maneuverGlyph(t))
        }
        for (t in listOf(ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.SLIGHT_RIGHT)) {
            assertEquals(t.name, ManeuverGlyph.SLIGHT_RIGHT, maneuverGlyph(t))
        }
    }

    @Test
    fun `merges and roundabouts keep their own glyphs`() {
        assertEquals(ManeuverGlyph.MERGE, maneuverGlyph(ManeuverType.MERGE))
        assertEquals(ManeuverGlyph.ROUNDABOUT, maneuverGlyph(ManeuverType.ROUNDABOUT))
        assertEquals(ManeuverGlyph.ROUNDABOUT, maneuverGlyph(ManeuverType.EXIT_ROUNDABOUT))
    }

    @Test
    fun `a sided maneuver never gets the other side's glyph`() {
        for (t in ManeuverType.entries) {
            val g = maneuverGlyph(t).name
            if (t.name.endsWith("_LEFT")) assertTrue("$t -> $g", g.endsWith("LEFT"))
            if (t.name.endsWith("_RIGHT")) assertTrue("$t -> $g", g.endsWith("RIGHT"))
        }
    }
}
