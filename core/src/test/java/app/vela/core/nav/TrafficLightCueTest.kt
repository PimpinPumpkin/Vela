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

/** "At the light, turn right" said once per turn, on the first line that can carry it. */
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

    @Test fun `a turn at a light says so on its approach and not again at the turn`() {
        val said = drive(route(400.0, elm = { copy(lightAtTurn = true) }), 400.0)
        val elm = said.filter { "Elm Street" in it || it == "Turn right" }
        assertTrue("$said", elm.first().contains("At the light, turn right onto Elm Street"))
        assertEquals("$said", 1, elm.count { "At the light" in it })
        assertTrue("$said", said.none { "Oak Avenue" in it && "At the light" in it })
    }

    @Test fun `a turn too close to the one before says it on the joined line or at its turn`() {
        val said = drive(route(80.0, elm = { copy(lightAtTurn = true) }, oak = { copy(lightAtTurn = true) }), 80.0)
        val pair = said.first { "Elm Street" in it }
        assertTrue("$said", pair.endsWith("At the light, turn right onto Elm Street, then turn left onto Oak Avenue"))
        // Oak Avenue has no approach line of its own: its line at the turn carries it.
        assertEquals("$said", "At the light, turn left onto Oak Avenue", said.last { "Oak Avenue" in it })
    }

    @Test fun `a light before the turn is passed first, then the turn's own is named`() {
        val said = drive(route(400.0, elm = { copy(lightsBeforeM = listOf(150.0), lightAtTurn = true) }), 400.0)
        val elm = said.filter { "Elm Street" in it || it.startsWith("At the light, turn right") }
        assertTrue("$said", elm.first().contains("Pass the traffic light, then turn right onto Elm Street"))
        assertEquals("$said", 1, elm.count { "At the light" in it })
    }

    @Test fun `no light, no cue`() {
        assertTrue(drive(route(80.0), 80.0).none { "light" in it })
    }
}
