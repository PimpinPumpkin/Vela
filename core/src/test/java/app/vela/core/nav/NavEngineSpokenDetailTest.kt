package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Brief and Highway-exits-only choices for spoken guidance (issue #718). */
class NavEngineSpokenDetailTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)
    private fun northOf(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @After fun reset() { SpokenDetail.mode = SpokenDetail.Mode.FULL }

    /** East 1500 m to one maneuver of [type], then north 500 m. */
    private fun route(type: ManeuverType, text: String, noRoad: String? = null): Route {
        val poly = (0..150).map { east(it * 10.0) } + (1..50).map { northOf(1500.0, it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1500.0, 0.0),
            Maneuver(type, text, east(1500.0), 500.0, 0.0, instructionNoRoad = noRoad),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2000.0, 200.0, null, ms)), 2000.0, 200.0, null)
    }

    /** Drives east at [mps] up to the maneuver; every line spoken and every buzz on the way. */
    private fun approach(r: Route, mps: Double): Pair<List<String>, Int> {
        var st = NavState()
        val said = ArrayList<String>()
        var buzzes = 0
        var m = 0.0
        while (m <= 1490.0) {
            val (s, ev) = NavEngine.update(r, st, east(m), speedMps = mps, bearingDeg = 90.0)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
            buzzes += ev.count { it is NavEvent.Haptic }
            m += 10.0
        }
        return said to buzzes
    }

    private val turn get() = route(ManeuverType.TURN_LEFT, "Turn left onto North Road", noRoad = "Turn left")
    private val ramp get() = route(ManeuverType.RAMP_RIGHT, "Take exit 12 toward Sacramento")

    @Test fun `full says a turn three times`() {
        assertEquals(3, approach(turn, 10.0).first.size)
    }

    @Test fun `brief says a turn once, near, without the street, and still buzzes at it`() {
        SpokenDetail.mode = SpokenDetail.Mode.BRIEF
        val (said, buzzes) = approach(turn, 10.0)
        assertEquals("$said", 1, said.size)
        assertTrue(said[0], said[0].endsWith("Turn left") && said[0].startsWith("In 150"))
        assertEquals("one on the approach, one at the turn", 2, buzzes)
    }

    @Test fun `brief says an exit once, from far out, with its number`() {
        SpokenDetail.mode = SpokenDetail.Mode.BRIEF
        val said = approach(ramp, 30.0).first
        assertEquals("$said", 1, said.size)
        // 30 m/s puts the far prompt 1050 m out.
        assertTrue(said[0], said[0].contains("Take exit 12") && !said[0].contains("Sacramento") && said[0].startsWith("In 1"))
    }

    @Test fun `exits only is silent for a turn in town`() {
        SpokenDetail.mode = SpokenDetail.Mode.EXITS
        assertTrue(approach(turn, 10.0).first.isEmpty())
    }

    @Test fun `exits only says an exit, and a turn taken at speed`() {
        SpokenDetail.mode = SpokenDetail.Mode.EXITS
        assertEquals(1, approach(ramp, 30.0).first.size)
        assertEquals("a turn off a fast road is an exit in all but name", 1, approach(turn, 25.0).first.size)
    }

    @Test fun `exits only leaves roundabouts and merges silent at any speed`() {
        SpokenDetail.mode = SpokenDetail.Mode.EXITS
        assertTrue(approach(route(ManeuverType.ROUNDABOUT, "Take the second exit"), 25.0).first.isEmpty())
        assertTrue(approach(route(ManeuverType.MERGE, "Merge onto I-80"), 30.0).first.isEmpty())
    }

    @Test fun `the line prepared ahead of time is the one that is spoken`() {
        SpokenDetail.mode = SpokenDetail.Mode.BRIEF
        val ready = NavEngine.upcomingPrompts(turn, fromStep = 1, spoken = emptySet(), speedMps = 10.0, imperial = false)
        assertEquals(approach(turn, 10.0).first, ready)
        SpokenDetail.mode = SpokenDetail.Mode.EXITS
        assertTrue(NavEngine.upcomingPrompts(turn, fromStep = 1, spoken = emptySet(), speedMps = 10.0, imperial = false).isEmpty())
    }

    @Test fun `exits only still says the destination is ahead`() {
        SpokenDetail.mode = SpokenDetail.Mode.EXITS
        assertTrue(SpokenDetail.speaks(SpokenDetail.Mode.EXITS, ManeuverType.ARRIVE, 5.0))
    }
}
