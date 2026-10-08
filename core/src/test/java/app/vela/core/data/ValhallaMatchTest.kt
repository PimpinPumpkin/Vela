package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map-match reply (a live `trace_route` capture, Davis to Sacramento) and the check that a
 *  match really follows the line it was given. */
class ValhallaMatchTest {
    private val reply = javaClass.getResource("/valhalla_trace_davis_sacramento.json")!!.readText()

    @Test fun signsBecomeExitNumbersAndDestinations() {
        val r = ValhallaRouter.parse(reply).firstOrNull()
        assertNotNull(r)
        val text = r!!.maneuvers.map { it.instruction }
        assertTrue("an exit number is read out: $text", text.any { it.contains("4A") })
        assertTrue("and where the sign says it goes: $text", text.any { it.contains("toward I 5 North") })
        // A numbered road's shield comes from the names; the road keeps its real name.
        val fwy = r.maneuvers.firstOrNull { it.road == "Capital City Freeway" }
        assertNotNull("the freeway is named, not called by its number: ${r.maneuvers.map { it.road }}", fwy)
        assertEquals("US 50", fwy!!.ref)
    }

    // A straight kilometer east from the Davis fixture, one point per 100 m.
    private val line = (0..10).map { LatLng(38.5449, -121.7405 + it * 0.00115) }

    @Test fun theSameRoadDrawnTwiceFollows() {
        val lane = line.map { LatLng(it.lat + 0.00006, it.lng) } // about 7 m to one side
        assertTrue(ValhallaRouter.followsLine(lane, line))
    }

    @Test fun theNextStreetOverDoesNot() {
        val other = line.map { LatLng(it.lat + 0.0004, it.lng) } // about 44 m away
        assertFalse(ValhallaRouter.followsLine(other, line))
    }

    @Test fun aMatchThatStopsHalfwayDoesNot() {
        assertFalse(ValhallaRouter.followsLine(line.take(6), line))
    }

    @Test fun aMatchWithADetourInItDoesNot() {
        val detour = line.take(5) + listOf(LatLng(38.5460, -121.7350)) + line.drop(5)
        assertFalse(ValhallaRouter.followsLine(detour, line))
    }

    // The name check: what the matched path is called after a turn, from its own edges.
    private val at = LatLng(38.5449, -121.7405)
    private fun e(name: String?, m: Double, soft: Boolean = false) = ValhallaRouter.Edge(listOfNotNull(name), m, at, soft)

    @Test fun aNameThatHoldsAfterTheTurnIsKept() {
        assertEquals("B Street", ValhallaRouter.checkedRoad("B Street", 400.0, listOf(e("B Street", 30.0), e("B Street", 200.0))))
    }

    @Test fun aShortStreetBeforeTheNextTurnIsKept() {
        // 29 m on one street, then a right: the step is short, and so is the hold it needs.
        assertEquals("B Street", ValhallaRouter.checkedRoad("B Street", 47.0, listOf(e("B Street", 29.0), e("3rd Street", 200.0))))
    }

    @Test fun aNamedTurnLaneBeforeTheStreetIsALeadIn() {
        assertEquals("B Street", ValhallaRouter.checkedRoad("B Street", 90.0, listOf(e("A Street", 29.0, soft = true), e(null, 8.0), e("B Street", 62.0))))
    }

    @Test fun theStreetAtTheTurnWinsOverTheOneItBecomes() {
        // The step text names the bridge 120 m on; the turn is onto the street that leads to it.
        val after = listOf(e("Mill Street", 27.0, soft = true), e("Mill Street", 96.0, soft = true), e("Old Bridge", 184.0))
        assertEquals("Mill Street", ValhallaRouter.checkedRoad("Old Bridge", 1241.0, after))
    }

    @Test fun aStubOfTheOldRoadDoesNotNameTheTurn() {
        // "Stay on River Road" when 6 m later the car is on the bridge.
        assertEquals("Old Bridge", ValhallaRouter.checkedRoad("River Road", 670.0, listOf(e("River Road", 6.0), e("Old Bridge", 262.0))))
    }

    @Test fun nothingSolidMeansNoName() {
        assertEquals(null, ValhallaRouter.checkedRoad("C Street", 500.0, listOf(e("A Street", 25.0), e("B Street", 30.0), e("D Street", 300.0))))
    }

    @Test fun noEdgesMeansNoStreetNamesOnTurns() {
        val checked = ValhallaRouter.parse(reply, ValhallaRouter.NameCheck(null)).first()
        val plain = ValhallaRouter.parse(reply).first()
        assertTrue("exits keep their sign text", checked.maneuvers.any { it.instruction.contains("4A") })
        val turns = setOf(app.vela.core.model.ManeuverType.TURN_LEFT, app.vela.core.model.ManeuverType.TURN_RIGHT)
        assertTrue("the capture has named turns", plain.maneuvers.any { it.type in turns && it.road != null })
        assertTrue("none of them is named unchecked", checked.maneuvers.none { it.type in turns && it.road != null })
    }

    // A corner the step text leaves out: north 555 m on A Street, then east on B Street.
    private fun q(dLat: Double, dLng: Double) = LatLng(38.54 + dLat, -121.74 + dLng)
    private fun corner(first: String, second: String): Pair<app.vela.core.model.Route, List<ValhallaRouter.Edge>> {
        val l = listOf(q(0.0, 0.0), q(0.005, 0.0), q(0.005, 0.005))
        fun man(type: app.vela.core.model.ManeuverType, d: Double) = app.vela.core.model.Maneuver(type, type.name, l.first(), d, d / 10, road = first)
        val r = app.vela.core.model.Route(
            polyline = l, legs = listOf(app.vela.core.model.RouteLeg(990.0, 99.0, null, listOf(man(app.vela.core.model.ManeuverType.DEPART, 990.0), man(app.vela.core.model.ManeuverType.ARRIVE, 0.0)))),
            distanceMeters = 990.0, durationSeconds = 99.0, durationInTrafficSeconds = null,
        )
        val edges = listOf(
            ValhallaRouter.Edge(listOf(first, "MA 2"), 555.0, l[0], false),
            ValhallaRouter.Edge(listOf(second, "MA 2"), 435.0, l[1], false),
        )
        return r to edges
    }

    @Test fun aCornerOntoAnotherStreetGetsItsTurn() {
        val (r, edges) = corner("A Street", "B Street")
        val out = ValhallaRouter.withUnsaidTurns(r, edges)
        val turn = out.maneuvers.single { it.type == app.vela.core.model.ManeuverType.TURN_RIGHT }
        assertEquals("B Street", turn.road)
        assertEquals(990.0, out.maneuvers.sumOf { it.distanceMeters }, 1.0)
        assertTrue("placed at the corner: ${out.maneuvers.first().distanceMeters}", kotlin.math.abs(out.maneuvers.first().distanceMeters - 555.0) < 40.0)
    }

    @Test fun aRoadThatCurvesKeepsItsOneStep() {
        val (r, edges) = corner("A Street", "A Street")
        assertEquals(2, ValhallaRouter.withUnsaidTurns(r, edges).maneuvers.size)
    }

    @Test fun aShortStrayInTheMiddleIsToleratedAndReported() {
        // 3 km of line; the match swings 60 m aside for about 150 m in the middle.
        val long = (0..30).map { LatLng(38.5449, -121.7405 + it * 0.00115) }
        val stray = long.mapIndexed { i, p -> if (i in 14..15) LatLng(p.lat + 0.00055, p.lng) else p }
        val off = ValhallaRouter.offLine(stray, long, 0.0, 0.0)
        assertNotNull(off)
        assertTrue("around the middle, and only there: $off", off!!.isNotEmpty() && off.first().first > 1000.0 && off.last().second < 1900.0)
    }

    @Test fun aMatchThatIsElsewhereForAKilometerIsRefused() {
        val long = (0..30).map { LatLng(38.5449, -121.7405 + it * 0.00115) }
        val away = long.mapIndexed { i, p -> if (i in 8..18) LatLng(p.lat + 0.00055, p.lng) else p }
        assertEquals(null, ValhallaRouter.offLine(away, long, 0.0, 0.0))
    }

    private fun road(m: Double) = ValhallaRouter.Edge(listOf("A Street"), m, line[0], false)
    private fun track(m: Double) = ValhallaRouter.Edge(emptyList(), m, line[0], false, track = true)

    @Test fun `a track between two roads is driven through, one at an end is not`() {
        // Out of a street, 219 m along a farm track, onto the main road.
        assertEquals(219.0, ValhallaRouter.throughTrackM(listOf(road(84.0), track(206.0), track(13.0), road(900.0))), 1e-9)
        // A farm at the end of a track, and a trip that ends up one: the only way there.
        assertEquals(0.0, ValhallaRouter.throughTrackM(listOf(track(300.0), road(900.0))), 1e-9)
        assertEquals(0.0, ValhallaRouter.throughTrackM(listOf(road(900.0), track(120.0), track(80.0))), 1e-9)
        assertEquals(0.0, ValhallaRouter.throughTrackM(listOf(track(300.0))), 1e-9)
        assertEquals(0.0, ValhallaRouter.throughTrackM(emptyList()), 1e-9)
        // Both: only the one in the middle counts.
        assertEquals(50.0, ValhallaRouter.throughTrackM(listOf(track(300.0), road(100.0), track(50.0), road(100.0), track(70.0))), 1e-9)
    }

    @Test fun `an edge's use marks a track`() {
        val text = """{"shape":"_izlhA~rlgdF_ibE_ibE","edges":[
            {"names":["A Street"],"length":0.084,"begin_shape_index":0,"use":"road"},
            {"length":0.206,"begin_shape_index":1,"use":"track"}]}"""
        val edges = ValhallaRouter.parseEdges(text)!!
        assertEquals(listOf(false, true), edges.map { it.track })
        assertEquals(206.0, edges[1].lengthM, 1e-6)
    }

    @Test fun `a turn that stays on the same street is said without its name`() {
        // A Street bends right at a junction and goes on as A Street; then a left onto B Street.
        val text = """{"trip":{"legs":[{"shape":"g_rohAflmegF?o}@fw@??o}@","maneuvers":[
            {"type":1,"street_names":["A Street"],"begin_shape_index":0,"end_shape_index":1,"length":0.087,"time":10},
            {"type":10,"street_names":["A Street"],"begin_shape_index":1,"end_shape_index":2,"length":0.1,"time":12},
            {"type":15,"street_names":["B Street"],"begin_shape_index":2,"end_shape_index":3,"length":0.087,"time":10},
            {"type":4,"begin_shape_index":3,"end_shape_index":3,"length":0,"time":0}]}],
            "summary":{"length":0.274,"time":32}}}"""
        val steps = ValhallaRouter.parse(text).first().maneuvers
        val stay = steps.first { it.instruction.startsWith("Turn right") }
        assertEquals("Turn right", stay.instruction)
        assertEquals("A Street", stay.road)
        assertTrue("a real change of street keeps its name: ${steps.map { it.instruction }}", steps.any { it.instruction == "Turn left onto B Street" })
    }
}
