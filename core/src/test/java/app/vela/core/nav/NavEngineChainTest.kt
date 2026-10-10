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

/** Two turns a block apart said on one line ("..., then turn left onto Oak Avenue"). */
class NavEngineChainTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @After fun reset() {
        SpokenDetail.mode = SpokenDetail.Mode.FULL
        NavStringsRegistry.setLocale(Locale.US)
    }

    /** East 1000 m, right onto Elm Street, south [gap] m, left onto Oak Avenue, east 600 m. */
    private fun route(gap: Double): Route {
        val poly = (0..100).map { pt(it * 10.0, 0.0) } + (1..(gap / 10).toInt()).map { pt(1000.0, -it * 10.0) } + (1..60).map { pt(1000.0 + it * 10.0, -gap) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1000.0, 100.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm Street", pt(1000.0, 0.0), gap, gap / 10, road = "Elm Street", instructionNoRoad = "Turn right"),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", pt(1000.0, -gap), 600.0, 60.0, road = "Oak Avenue", instructionNoRoad = "Turn left"),
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

    @Test fun `a turn a block after another rides on its lines`() {
        val said = drive(route(80.0), 80.0)
        val first = said.first { "Elm Street" in it }
        assertTrue("$said", first.endsWith("Turn right onto Elm Street, then turn left onto Oak Avenue"))
        val atTurn = said.first { it.startsWith("Turn right") }
        assertEquals("$said", "Turn right onto Elm Street, then turn left onto Oak Avenue", atTurn)
    }

    @Test fun `the second turn is not announced again on its approach, only at the turn`() {
        val said = drive(route(80.0), 80.0)
        val oak = said.filter { "Oak Avenue" in it }
        assertEquals("$said", "Turn left onto Oak Avenue", oak.last())
        assertTrue("no 'In 250 feet, turn left onto Oak Avenue' in between: $said", oak.none { it.startsWith("In ") && "Elm Street" !in it })
    }

    @Test fun `turns far enough apart are said one at a time, as before`() {
        val gap = NavEngine.CHAIN_M + 170.0
        val said = drive(route(gap), gap)
        assertTrue("$said", said.none { "then turn left" in it })
        assertTrue("the second turn gets its own approach: $said", said.any { it.startsWith("In ") && "Oak Avenue" in it })
    }

    @Test fun `a language with no line for it says the turns apart`() {
        NavStringsRegistry.setLocale(Locale.forLanguageTag("sv"))
        val said = drive(route(80.0), 80.0)
        val both = said.filter { "Elm Street" in it && "Oak Avenue" in it }
        assertTrue("$said", both.isEmpty())
        assertTrue("and the second turn keeps its approach line: $said", said.count { "Oak Avenue" in it } >= 2)
    }

    @Test fun `one short line a turn carries the next turn too`() {
        SpokenDetail.mode = SpokenDetail.Mode.BRIEF
        val said = drive(route(80.0), 80.0)
        assertTrue("$said", said.any { it.endsWith("turn right, then turn left") || it.endsWith("Turn right, then turn left") })
    }

    @Test fun `the pair is found by distance along the road, past a silent rename, never to the arrival`() {
        val r = route(80.0)
        val man = doubleArrayOf(0.0, 1000.0, 1080.0, 1680.0)
        assertEquals(2, NavEngine.chainedNext(r.maneuvers, man, 1))
        assertEquals("the arrival has lines of its own", -1, NavEngine.chainedNext(r.maneuvers, doubleArrayOf(0.0, 1000.0, 1080.0, 1100.0), 2))
        assertEquals(-1, NavEngine.chainedNext(r.maneuvers, doubleArrayOf(0.0, 1000.0, 1000.0 + NavEngine.CHAIN_M + 1.0, 1900.0), 1))
    }
}
