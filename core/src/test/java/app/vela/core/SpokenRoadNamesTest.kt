package app.vela.core

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.nav.SpokenRoadNames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The spoken-street-names switch (issue #596). The point of the design is that the nameless form
 * is built by the same per-language TEMPLATE as the named one rather than by stripping a tail, so
 * these check the templates themselves as well as the flag.
 */
class SpokenRoadNamesTest {

    @After fun restore() {
        SpokenRoadNames.enabled = true
        NavStringsRegistry.setLocale(Locale.ENGLISH)
    }

    private fun maneuver(instruction: String, noRoad: String?) = Maneuver(
        type = ManeuverType.TURN_LEFT,
        instruction = instruction,
        instructionNoRoad = noRoad,
        location = LatLng(38.5, -121.7),
        distanceMeters = 100.0,
        durationSeconds = 20.0,
    )

    @Test fun `on by default the voice keeps the street name`() {
        val m = maneuver("Turn left onto Maple Street", "Turn left")
        assertTrue(SpokenRoadNames.enabled)
        assertEquals("Turn left onto Maple Street", m.spokenInstruction())
    }

    @Test fun `off the voice drops it`() {
        SpokenRoadNames.enabled = false
        assertEquals("Turn left", maneuver("Turn left onto Maple Street", "Turn left").spokenInstruction())
    }

    @Test fun `a router that gave us no nameless form keeps saying the name`() {
        // Google's abbreviated steps are scraped prose, so there is nothing to rebuild from. Saying
        // the name is the safe answer; saying a mangled one is not.
        SpokenRoadNames.enabled = false
        assertEquals("Turn left onto Maple Street", maneuver("Turn left onto Maple Street", null).spokenInstruction())
    }

    @Test fun `every language builds a clean nameless turn`() {
        // A null road is not a new case for these tables: unnamed roads are everywhere, so each one
        // already had to phrase a turn without one. This is what makes the switch a null argument
        // rather than fifteen new templates.
        for (tag in listOf("en", "fr", "de", "es", "it", "pt", "nl", "ru", "pl", "sv", "uk", "hu", "iw", "ja", "zh")) {
            NavStringsRegistry.setLocale(Locale.forLanguageTag(tag))
            val s = NavStringsRegistry.current()
            val named = s.phrase("turn", "left", "Maple Street", null, null, null)
            val bare = s.phrase("turn", "left", null, null, null, null)
            assertTrue("$tag: named form should carry the road", named.contains("Maple Street"))
            assertFalse("$tag: bare form must not carry the road", bare.contains("Maple Street"))
            assertTrue("$tag: bare form must not be empty", bare.isNotBlank())
            assertFalse("$tag: bare form left a dangling connector: '$bare'", bare.trimEnd().endsWith(","))
        }
    }
}
