package app.vela.core.nav

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The voice saying a stop is coming, not only that it was passed ([StopAhead]). */
class NavEngineStopCueTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @After fun reset() {
        SpokenDetail.mode = SpokenDetail.Mode.FULL
        NavStringsRegistry.setLocale(Locale.US)
    }

    /** East 1500 m, a left turn onto [road] (null: a road with no name), then north 500 m. */
    private fun route(road: String? = "North Road"): Route {
        val poly = (0..150).map { pt(it * 10.0, 0.0) } + (1..50).map { pt(1500.0, it * 10.0) }
        val text = if (road != null) "Turn left onto $road" else "Turn left"
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 1500.0, 150.0),
            Maneuver(ManeuverType.TURN_LEFT, text, pt(1500.0, 0.0), 500.0, 50.0, road = road, instructionNoRoad = "Turn left"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2000.0, 200.0, null, ms)), 2000.0, 200.0, null)
    }

    /** The stop [north] meters up the north road, its pin [east] meters to the east of it. */
    private fun stopAt(r: Route, north: Double, east: Double = 30.0, intoLot: Boolean = false, label: String = "Davis Food Co-op"): StopAhead {
        val pin = pt(1500.0 + east, north)
        val mark = NavEngine.stopMarks(r, listOf(pin))[0]!!
        // The route's one turn (index 1) is the way into the lot when the test says so.
        return StopAhead(mark, label, NavEngine.stopSide(r, pin, mark), if (intoLot) 1 else -1)
    }

    /** Drives the route at [mps] to 30 m short of its end; every line spoken on the way. */
    private fun drive(r: Route, stop: StopAhead?, mps: Double = 10.0): List<String> {
        var st = NavState()
        val said = ArrayList<String>()
        for (m in generateSequence(0.0) { it + 10.0 }.takeWhile { it <= 1970.0 }) {
            val here = if (m <= 1500.0) pt(m, 0.0) else pt(1500.0, m - 1500.0)
            val (s, ev) = NavEngine.update(r, st, here, speedMps = mps, bearingDeg = if (m < 1500.0) 90.0 else 0.0, stop = stop)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
            if (st.arrived) break
        }
        return said
    }

    @Test fun `the side comes from where the pin sits against the road`() {
        val r = route()
        assertEquals("right", stopAt(r, 200.0, east = 30.0).side)
        assertEquals("left", stopAt(r, 200.0, east = -30.0).side)
        assertNull("a pin on the road is ahead", stopAt(r, 200.0, east = 2.0).side)
        // At the corner the road bends through the mark: no side is claimed.
        val corner = pt(1520.0, -15.0)
        assertNull(NavEngine.stopSide(r, corner, NavEngine.stopMarks(r, listOf(corner))[0]!!))
    }

    @Test fun `the turn before a stop says the stop after it, on its first line and at the turn`() {
        val said = drive(route(), stopAt(route(), 200.0))
        val withStop = said.filter { "Davis Food Co-op" in it }
        assertEquals("$said", 2, withStop.size)
        assertTrue(said.toString(), withStop[0].startsWith("In 400 meters, Turn left onto North Road, then Davis Food Co-op will be on your right"))
        assertEquals("Turn left onto North Road, then Davis Food Co-op will be on your right", withStop[1])
        // The near prompt in between stays short, and nothing else names the stop.
        assertTrue(said.any { it == "In 150 meters, Turn left onto North Road" })
    }

    @Test fun `a stop on a long leg gets its own cue at the near distance`() {
        val r = route()
        val stop = stopAt(r, 450.0) // 450 m past the turn: "then" would not mean soon
        val said = drive(r, stop)
        assertTrue(said.toString(), said.none { "then Davis Food Co-op" in it })
        assertEquals(said.toString(), listOf("In 150 meters, Davis Food Co-op will be on your right"), said.filter { "Davis Food Co-op" in it })
    }

    @Test fun `a stop with no side is ahead`() {
        val r = route()
        val said = drive(r, stopAt(r, 450.0, east = 0.0))
        assertTrue(said.toString(), said.contains("In 150 meters, Davis Food Co-op will be ahead"))
    }

    @Test fun `a turn into the stop's parking lot is said as one`() {
        val r = route(road = null)
        val said = drive(r, stopAt(r, 80.0, intoLot = true))
        assertTrue(said.toString(), said.contains("Turn left into the parking lot, then Davis Food Co-op is on your right"))
        assertTrue(said.toString(), said.any { it.startsWith("In 400 meters, Turn left into the parking lot, then Davis Food Co-op is on your right") })
    }

    @Test fun `the lot wording needs the map to say it is a lot, and a road with no name`() {
        val unknown = route(road = null)
        val plain = drive(unknown, stopAt(unknown, 80.0, intoLot = false))
        assertTrue(plain.toString(), plain.contains("Turn left, then Davis Food Co-op will be on your right"))
        assertTrue(plain.none { "parking lot" in it })
        // A named road is never a parking lot, whatever the lookup said.
        val named = route()
        assertTrue(drive(named, stopAt(named, 80.0, intoLot = true)).none { "parking lot" in it })
        // The turn into the lot is said as one however far past it the stop sits; the stop
        // follows on the same line only within STOP_THEN_M.
        val far = route(road = null)
        val farSaid = drive(far, stopAt(far, NavEngine.STOP_THEN_M + 50.0, intoLot = true))
        assertTrue("$farSaid", farSaid.any { it.endsWith("Turn left into the parking lot") })
        assertTrue("$farSaid", farSaid.none { "parking lot, then" in it })
    }

    @Test fun `brief says the turn and the stop once`() {
        SpokenDetail.mode = SpokenDetail.Mode.BRIEF
        val said = drive(route(), stopAt(route(), 200.0))
        assertEquals(said.toString(), listOf("In 150 meters, Turn left, then Davis Food Co-op will be on your right"), said.filter { "Turn" in it || "Davis" in it })
    }

    @Test fun `highway exits only still says the stop is coming, as it says the destination`() {
        SpokenDetail.mode = SpokenDetail.Mode.EXITS
        val said = drive(route(), stopAt(route(), 200.0))
        assertTrue("the turn in town stays silent: $said", said.none { it.contains("Turn") })
        assertTrue(said.toString(), said.contains("In 150 meters, Davis Food Co-op will be on your right"))
    }

    @Test fun `the stop is said in the app's language`() {
        NavStringsRegistry.setLocale(Locale.FRANCE)
        val said = drive(route(), stopAt(route(), 200.0))
        assertTrue(said.toString(), said.any { it.endsWith("puis Davis Food Co-op sera sur votre droite") })
    }

    @Test fun `with no stop the turn is said as before`() {
        val said = drive(route(), null)
        assertTrue(said.toString(), said.contains("Turn left onto North Road"))
        assertTrue(said.toString(), said.none { "then" in it })
    }

    @Test fun `a stop already passed is not announced`() {
        val r = route()
        val stop = stopAt(r, 200.0)
        var st = NavState(traveledM = 0.0)
        // Start the drive just past the stop: nothing about it is said.
        val said = ArrayList<String>()
        for (n in listOf(230.0, 240.0, 250.0, 260.0)) {
            val (s, ev) = NavEngine.update(r, st, pt(1500.0, n), speedMps = 10.0, bearingDeg = 0.0, stop = stop)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { said += it.text }
        }
        assertTrue(said.toString(), said.none { "Davis Food Co-op" in it })
    }
}
