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

    private fun drive(r: Route, gap: Double, mps: Double = 10.0, trackRide: Boolean = false): List<String> {
        var st = NavState()
        val said = ArrayList<String>()
        var m = 0.0
        while (m <= 1500.0 + gap) {
            val (here, brg) = when {
                m <= 1000.0 -> pt(m, 0.0) to 90.0
                m <= 1000.0 + gap -> pt(1000.0, -(m - 1000.0)) to 180.0
                else -> pt(1000.0 + (m - 1000.0 - gap), -gap) to 90.0
            }
            val (s, ev) = NavEngine.update(r, st, here, speedMps = mps, bearingDeg = brg, trackRide = trackRide)
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

    @Test fun `a pair too tight for the whole line drops the first street, then the second`() {
        // 14 m/s, 60 m apart: 4.3 s before the second turn's own line, the whole pair needs 5.1.
        val mid = drive(route(60.0), 60.0, mps = 14.0).first { it.startsWith("Turn right") }
        assertEquals("Turn right, then turn left onto Oak Avenue", mid)
        // 16 m/s, 60 m apart: 3.75 s, so both streets go.
        val short = drive(route(60.0), 60.0, mps = 16.0)
        assertEquals("$short", "Turn right, then turn left", short.first { it.startsWith("Turn right") })
        // The second turn still gets its own line at the turn, and no approach line in between.
        assertEquals("$short", "Turn left onto Oak Avenue", short.last { "Oak Avenue" in it })
        assertTrue("$short", short.none { it.startsWith("In ") && "Oak Avenue" in it && "Elm Street" !in it })
    }

    @Test fun `the second turn is not announced again on its approach, only at the turn`() {
        val said = drive(route(80.0), 80.0)
        val oak = said.filter { "Oak Avenue" in it }
        assertEquals("$said", "Turn left onto Oak Avenue", oak.last())
        assertTrue("no 'In 250 feet, turn left onto Oak Avenue' in between: $said", oak.none { it.startsWith("In ") && "Elm Street" !in it })
    }

    @Test fun `turns far enough apart are said one at a time, as before`() {
        val gap = NavEngine.THEN_SOON_M + 50.0
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

    @Test fun `on a track ride a turn 150 m on is said on its own`() {
        // Riding at 5 m/s, Oak Avenue 150 m after Elm Street. A car's 400 m far band makes the
        // second turn's first line due at once, so it rides on the first: "..., then turn left".
        val car = drive(route(150.0), 150.0, mps = 5.0)
        assertTrue("$car", car.any { it.startsWith("Turn right") && "then" in it })
        // A track ride's bands are a cyclist's: each turn is said on its own.
        val ride = drive(route(150.0), 150.0, mps = 5.0, trackRide = true)
        assertTrue("$ride", ride.none { "then" in it })
        assertTrue("$ride", ride.any { it.startsWith("Turn right onto Elm Street") })
        assertTrue("$ride", ride.any { it.startsWith("Turn left onto Oak Avenue") || it.endsWith("turn left onto Oak Avenue") })
    }

    @Test fun `on a track ride turns 50 m apart still ride together`() {
        val ride = drive(route(50.0), 50.0, mps = 5.0, trackRide = true)
        assertTrue("$ride", ride.any { it.startsWith("Turn right") && "then turn left" in it })
    }

    /** East 30 m from the start, left onto Oak Avenue, north 300 m. */
    private fun shortStart(): Route {
        val poly = (0..3).map { pt(it * 10.0, 0.0) } + (1..30).map { pt(30.0, it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Start your route", poly.first(), 30.0, 6.0),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", pt(30.0, 0.0), 300.0, 60.0, road = "Oak Avenue", instructionNoRoad = "Turn left"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(330.0, 66.0, null, ms)), 330.0, 66.0, null)
    }

    @Test fun `a turn the track start line named is not said again`() {
        // A drive begun on the track: on the first fix the engine is on the departure step, says
        // nothing, and moves to the left turn 30 m on. NavSession's start line names that turn and
        // records the bands it is inside (NavEngine.bandsWithin); the engine must not say it again.
        val r = shortStart()
        val (first, ev) = NavEngine.update(r, NavState(), pt(0.0, 0.0), speedMps = 0.0, bearingDeg = 90.0, trackRide = true)
        assertTrue("$ev", ev.none { it is NavEvent.Speak })
        assertEquals(1, first.stepIndex)
        val marked = first.copy(spoken = first.spoken + NavEngine.bandsWithin(first.distanceToNextManeuver, 0.0, trackRide = true))
        val (_, ev2) = NavEngine.update(r, marked, pt(3.0, 0.0), speedMps = 3.0, bearingDeg = 90.0, trackRide = true)
        assertTrue("$ev2", ev2.filterIsInstance<NavEvent.Speak>().none { it.text.startsWith("In ") })
        // Unmarked, the engine says the approach line itself: the duplicate this guards against.
        val (_, ev3) = NavEngine.update(r, first, pt(3.0, 0.0), speedMps = 3.0, bearingDeg = 90.0, trackRide = true)
        assertTrue("$ev3", ev3.filterIsInstance<NavEvent.Speak>().any { it.text.startsWith("In ") })
    }
}
