package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
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

    /* The nameless form is a SECOND string carried beside the named one, so anything that rewrites
       an instruction after the router built it has to rewrite both or the switch silently undoes
       that rewrite. These are the two places that do. */

    @Test fun `a folded exit disambiguates the nameless form too`() {
        // The fold drops the direction that is NOT taken. Missing it on the nameless twin means
        // that with street names off the voice announces both directions again.
        val ramp = Maneuver(
            type = ManeuverType.RAMP_RIGHT,
            instruction = "Take the ramp on the right toward CA-99 West, CA-99 East",
            instructionNoRoad = "Take the ramp toward CA-99 West, CA-99 East",
            location = LatLng(0.0, 0.0), distanceMeters = 120.0, durationSeconds = 0.0,
        )
        val fork = Maneuver(
            type = ManeuverType.FORK_LEFT,
            instruction = "Keep left toward CA-99 West",
            location = LatLng(0.0, 0.0), distanceMeters = 30.0, durationSeconds = 0.0,
        )
        val arrive = Maneuver(
            type = ManeuverType.ARRIVE, instruction = "x",
            location = LatLng(0.0, 0.0), distanceMeters = 0.0, durationSeconds = 0.0,
        )
        val folded = RouteGeometry.consolidateExits(listOf(ramp, fork, arrive))[0]
        SpokenRoadNames.enabled = false
        assertFalse(
            "the nameless form still names the direction that was not taken",
            folded.spokenInstruction().contains("CA-99 East"),
        )
        assertTrue(folded.spokenInstruction().contains("CA-99 West"))
    }

    @Test fun `traffic-light guidance survives turning street names off`() {
        // Pass-the-lights is its own feature with its own switch; dropping street names must not
        // take it away as a side effect.
        val poly = (0..4).map { LatLng(38.5, -121.7 + it * 0.0005) }
        val mans = listOf(
            Maneuver(
                type = ManeuverType.DEPART, instruction = "Head east",
                location = poly[0], distanceMeters = 150.0, durationSeconds = 0.0,
            ),
            Maneuver(
                type = ManeuverType.TURN_LEFT,
                instruction = "Turn left onto Main Street",
                instructionNoRoad = "Turn left",
                location = poly[4], distanceMeters = 0.0, durationSeconds = 0.0,
            ),
        )
        val route = Route(poly, listOf(RouteLeg(150.0, 0.0, null, mans)), 150.0, 0.0, null)
        val onApproach = LatLng(38.5, -121.7 + 0.001)
        val turn = RouteGeometry.enrichWithLights(route, listOf(onApproach)).legs[0].maneuvers.last()

        assertTrue("named form keeps the clause", turn.instruction.startsWith("Pass the traffic light, then"))
        SpokenRoadNames.enabled = false
        assertTrue(
            "the clause went missing once street names were off: '${turn.spokenInstruction()}'",
            turn.spokenInstruction().startsWith("Pass the traffic light, then"),
        )
        assertFalse(turn.spokenInstruction().contains("Main Street"))
    }
}
