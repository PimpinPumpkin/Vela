package app.vela.core.nav

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Light and stop-sign cues on a drive: the near line, the line at the turn and a "then". */
class TrafficLightCueTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @After fun reset() {
        SpokenDetail.mode = SpokenDetail.Mode.FULL
        NavStringsRegistry.setLocale(Locale.US)
    }

    /** East 1000 m, right onto Elm Street, south [gap] m, left onto Oak Avenue, east 600 m. */
    private fun route(gap: Double, elm: Maneuver.() -> Maneuver = { this }, oak: Maneuver.() -> Maneuver = { this }): Route {
        val poly = (0..100).map { pt(it * 10.0, 0.0) } + (1..(gap / 10).toInt()).map { pt(1000.0, -it * 10.0) } + (1..60).map { pt(1000.0 + it * 10.0, -gap) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1000.0, 100.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm Street", pt(1000.0, 0.0), gap, gap / 10, road = "Elm Street", instructionNoRoad = "Turn right").elm(),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", pt(1000.0, -gap), 600.0, 60.0, road = "Oak Avenue", instructionNoRoad = "Turn left").oak(),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(1600.0 + gap, 160.0, null, ms)), 1600.0 + gap, 160.0, null)
    }

    private fun drive(r: Route, gap: Double, mps: Double = 10.0): List<String> {
        var st = NavState()
        val said = ArrayList<String>()
        var m = 0.0
        while (m <= 1500.0 + gap) {
            val (here, brg) = when {
                m <= 1000.0 -> pt(m, 0.0) to 90.0
                m <= 1000.0 + gap -> pt(1000.0, -(m - 1000.0)) to 180.0
                else -> pt(1000.0 + (m - 1000.0 - gap), -gap) to 90.0
            }
            val (s, ev) = NavEngine.update(r, st, here, speedMps = mps, bearingDeg = brg)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
            m += 5.0
        }
        return said
    }

    @Test fun `a turn at a light says so near the turn and at it, not from far out`() {
        val said = drive(route(400.0, elm = { copy(lightAtTurn = true) }), 400.0)
        val elm = said.filter { "Elm Street" in it || it.startsWith("Turn right") || it.startsWith("At the light, turn right") }
        assertTrue("$said", "light" !in elm.first())
        assertTrue("$said", elm.any { "At the light, turn right onto Elm Street" in it })
        assertTrue("$said", elm.last().startsWith("At the light, turn right"))
        assertTrue("$said", said.none { "light, turn left onto Oak Avenue" in it })
    }

    @Test fun `a turn too close to the one before says it in the then and again at its turn`() {
        val said = drive(route(80.0, elm = { copy(lightAtTurn = true) }, oak = { copy(lightAtTurn = true) }), 80.0)
        // From far out the first turn names no light; the "then" names the second's.
        assertTrue("$said", said.first().endsWith("Turn right onto Elm Street, then at the light, turn left onto Oak Avenue"))
        assertTrue("$said", "At the light, turn right onto Elm Street, then at the light, turn left onto Oak Avenue" in said)
        assertEquals("$said", "At the light, turn left onto Oak Avenue", said.last { "Oak Avenue" in it })
    }

    @Test fun `a stop sign in the then`() {
        val said = drive(route(80.0, oak = { copy(stopAtTurn = true) }), 80.0)
        assertTrue("$said", "Turn right onto Elm Street, then at the stop sign, turn left onto Oak Avenue" in said)
        assertEquals("$said", "At the stop sign, turn left onto Oak Avenue", said.last { "Oak Avenue" in it })
    }

    @Test fun `a line at the turn cut short to fit keeps the next turn's light`() {
        // 60 m apart at 12 m/s: the whole pair does not fit before the second turn's own line.
        val said = drive(route(60.0, elm = { copy(stopAtTurn = true) }, oak = { copy(lightAtTurn = true) }), 60.0, mps = 12.0)
        assertTrue("$said", said.any { it.startsWith("Turn right, then at the light, turn left onto Oak Avenue") })
    }

    @Test fun `one light before a turn with none of its own is gone past on the near line only`() {
        val said = drive(route(400.0, elm = { copy(lightsBeforeM = listOf(120.0)) }), 400.0)
        val elm = said.filter { "Elm Street" in it || it.startsWith("Turn right") || it.startsWith("Go past") }
        assertEquals("$said", 1, elm.count { "light" in it })
        assertTrue("$said", elm.single { "light" in it }.contains("Go past this light, then turn right onto Elm Street"))
    }

    @Test fun `a turn at a light with a light before it says at the light`() {
        val said = drive(route(400.0, elm = { copy(lightsBeforeM = listOf(120.0), lightAtTurn = true) }), 400.0)
        assertTrue("$said", said.none { "Go past" in it })
        assertTrue("$said", said.any { "At the light, turn right onto Elm Street" in it })
    }

    @Test fun `two lights to go past say nothing until one is left`() {
        val said = drive(route(400.0, elm = { copy(lightsBeforeM = listOf(350.0, 120.0)) }), 400.0)
        val elm = said.filter { "Elm Street" in it }
        assertTrue("$said", elm.none { "2 lights" in it })
        assertTrue("$said", elm.count { "light" in it } <= 1)
    }

    @Test fun `the shorter voice settings say no landmark`() {
        SpokenDetail.mode = SpokenDetail.Mode.BRIEF
        val said = drive(route(80.0, elm = { copy(lightAtTurn = true) }, oak = { copy(stopAtTurn = true) }), 80.0)
        assertTrue("$said", said.none { "light" in it || "stop sign" in it })
    }

    @Test fun `no light, no cue`() {
        assertTrue(drive(route(80.0), 80.0).none { "light" in it || "stop sign" in it })
    }
}
