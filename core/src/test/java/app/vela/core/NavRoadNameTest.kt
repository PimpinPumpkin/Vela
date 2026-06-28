package app.vela.core

import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavEngine
import app.vela.core.nav.NavEvent
import app.vela.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock-navigation test for the **"Turn right ONTO <street>"** road names a user reported as
 * missing (only hearing/seeing "turn right").
 *
 * Uses VERBATIM keyless `<step>` markup captured live from `/maps/preview/directions`
 * (Mill Creek WA, 2026-06-28), parses it with the real [DirectionsParser], then **drives the
 * route through [NavEngine]** and checks the spoken announcements carry the street name. It
 * proves regular turns keep their road, and pins the fact that Google's keyless feed **omits
 * the road on some turns** (those legitimately stay a bare "Turn left").
 */
class NavRoadNameTest {

    // Verbatim raw markup from the live feed (whitespace compacted only).
    private val MARSH  = "<step maneuver='STRAIGHT' meters='900'>Continue straight onto <roadlist><road lang='en'>Marsh Rd</road></roadlist></step>"
    private val AVE_C  = "<step maneuver='TURN' meters='220'>Turn <turn side='LEFT'>left</turn> onto <roadlist><road lang='en'>Ave C</road></roadlist></step>"
    private val ST_13  = "<step maneuver='TURN' meters='240'>Turn <turn side='RIGHT'>right</turn> onto <roadlist><road lang='en'>13th St SE</road></roadlist></step>"
    private val BARE   = "<step maneuver='TURN' meters='160'>Turn <turn side='LEFT'>left</turn></step>" // feed carries NO <road> here
    private val ARRIVE = "<step maneuver='DESTINATION' meters='90'>Arrive at your destination</step>"

    @Test
    fun parserKeepsRoadNamesInTheInstruction() {
        DirectionsParser.parseStep(AVE_C).let {
            assertEquals("Turn left onto Ave C", it.instruction)
            assertEquals(ManeuverType.TURN_LEFT, it.type)
        }
        DirectionsParser.parseStep(ST_13).let {
            assertEquals("Turn right onto 13th St SE", it.instruction)
            assertEquals(ManeuverType.TURN_RIGHT, it.type)
        }
        // Google's feed has no <road> for this turn, so the honest keyless text is the bare turn
        // (NOT a bug — there is no street name to say). Documents the real ceiling.
        assertEquals("Turn left", DirectionsParser.parseStep(BARE).instruction)
    }

    @Test
    fun mockDriveAnnouncesRoadNames() {
        val parsed = listOf(MARSH, AVE_C, ST_13, BARE, ARRIVE).map { DirectionsParser.parseStep(it) }
        // Lay the maneuvers out along a straight north-bound line at their cumulative distance.
        val start = LatLng(47.860, -122.205)
        fun north(m: Double) = LatLng(start.lat + m / 111_320.0, start.lng)
        var cum = 0.0
        val placed = parsed.map { mv -> cum += mv.distanceMeters; mv.copy(location = north(cum)) }
        val total = cum
        val poly = (0..total.toInt() step 30).map { north(it.toDouble()) } + north(total)
        val route = Route(
            polyline = poly,
            legs = listOf(RouteLeg(total, total / 12.0, null, placed)),
            distanceMeters = total,
            durationSeconds = total / 12.0,
            durationInTrafficSeconds = null,
        )

        // "Drive" it in ~20 m hops, collecting every spoken announcement.
        val spoken = mutableListOf<String>()
        var st = NavState()
        var d = 0.0
        while (d <= total + 5) {
            val (next, events) = NavEngine.update(route, st, north(d))
            st = next
            events.filterIsInstance<NavEvent.Speak>().forEach { spoken += it.text }
            d += 20.0
        }
        println("MOCK DRIVE — spoken announcements:\n  " + spoken.joinToString("\n  "))

        // The road-bearing turns MUST be announced with the street (the user's ask).
        assertTrue("expected an 'onto Ave C' announcement, got $spoken", spoken.any { it.contains("onto Ave C") })
        assertTrue("expected an 'onto 13th St SE' announcement, got $spoken", spoken.any { it.contains("onto 13th St SE") })
    }
}
