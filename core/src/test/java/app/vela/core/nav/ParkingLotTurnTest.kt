package app.vela.core.nav

import app.vela.core.data.naming.RoadNameTiles
import app.vela.core.data.naming.RoadNameTiles.RoadLine
import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling a turn into a parking lot from any other turn onto a road with no name. */
class ParkingLotTurnTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    /** Samples north from the street, 10 to 60 m in, the way the route takes past the turn. */
    private val samples = (0..6).map { pt(0.0, 10.0 + it * 8.0) }
    private val street = RoadLine("minor", null, listOf(pt(-200.0, 0.0), pt(200.0, 0.0)))

    @Test fun `a route running down a parking aisle is a lot`() {
        val aisle = RoadLine("service", "parking_aisle", listOf(pt(0.0, 0.0), pt(0.0, 80.0)))
        assertTrue(ParkingLotTurn.onLotAisles(samples, listOf(street, aisle)))
    }

    @Test fun `an entrance road into the aisles is a lot too`() {
        val entrance = RoadLine("service", null, listOf(pt(0.0, 0.0), pt(0.0, 30.0)))
        val aisle = RoadLine("service", "parking_aisle", listOf(pt(0.0, 30.0), pt(0.0, 80.0)))
        assertTrue(ParkingLotTurn.onLotAisles(samples, listOf(street, entrance, aisle)))
    }

    @Test fun `a driveway, an alley or a street is not`() {
        val driveway = RoadLine("service", "driveway", listOf(pt(0.0, 0.0), pt(0.0, 80.0)))
        assertFalse("a driveway alone could be a house's", ParkingLotTurn.onLotAisles(samples, listOf(street, driveway)))
        val alley = RoadLine("service", "alley", listOf(pt(0.0, 0.0), pt(0.0, 80.0)))
        val aisleBeyond = RoadLine("service", "parking_aisle", listOf(pt(0.0, 50.0), pt(0.0, 80.0)))
        assertFalse(ParkingLotTurn.onLotAisles(samples, listOf(street, alley, aisleBeyond)))
        val lane = RoadLine("minor", null, listOf(pt(0.0, 0.0), pt(0.0, 80.0)))
        assertFalse(ParkingLotTurn.onLotAisles(samples, listOf(street, lane)))
    }

    @Test fun `a stretch the map does not have is not`() {
        val aisleAside = RoadLine("service", "parking_aisle", listOf(pt(40.0, 0.0), pt(40.0, 80.0)))
        assertFalse(ParkingLotTurn.onLotAisles(samples, listOf(street, aisleAside)))
        assertFalse(ParkingLotTurn.onLotAisles(emptyList(), listOf(street)))
    }

    @Test fun `the map tiles carry parking aisles, and one reads as a lot`() {
        val raw = javaClass.getResourceAsStream("/tiles/davis-14-2651-6288.pbf")!!.use { it.readBytes() }
        val roads = RoadNameTiles.decodeRoads(raw, 14, 2651, 6288)
        val aisles = roads.filter { it.service == "parking_aisle" }
        assertTrue("the Davis fixture has parking aisles", aisles.isNotEmpty())
        assertTrue(aisles.all { it.cls == "service" })
        assertTrue("footpaths are not car roads", roads.none { it.cls == "path" })
        // Driving down the longest aisle in the tile, sampled between its own vertices.
        val longest = aisles.maxByOrNull { a -> a.points.zipWithNext().sumOf { (p, q) -> lengthM(p, q) } }!!
        val along = longest.points.zipWithNext().map { (p, q) -> LatLng((p.lat + q.lat) / 2, (p.lng + q.lng) / 2) }
        assertTrue(ParkingLotTurn.onLotAisles(along, roads))
        assertEquals(0, roads.count { it.points.size < 2 })
    }

    private fun lengthM(a: LatLng, b: LatLng): Double {
        val dy = (b.lat - a.lat) * 111_320.0
        val dx = (b.lng - a.lng) * 111_320.0 * Math.cos(Math.toRadians(a.lat))
        return Math.hypot(dx, dy)
    }

    // A trip east along a street, then left (north) into a lot, arriving [past] meters in.
    private fun tripInto(past: Double, road: String? = null, type: app.vela.core.model.ManeuverType = app.vela.core.model.ManeuverType.TURN_LEFT): app.vela.core.model.Route {
        val line = listOf(pt(-300.0, 0.0), pt(0.0, 0.0), pt(0.0, past))
        val ms = listOf(
            app.vela.core.model.Maneuver(app.vela.core.model.ManeuverType.DEPART, "Head east on 3rd Street", line[0], 300.0, 30.0, road = "3rd Street"),
            app.vela.core.model.Maneuver(type, if (road == null) "Turn left" else "Turn left onto $road", line[1], past, 10.0, road = road, instructionNoRoad = "Turn left"),
            app.vela.core.model.Maneuver(app.vela.core.model.ManeuverType.ARRIVE, "Arrive", line[2], 0.0, 0.0),
        )
        return app.vela.core.model.Route(line, listOf(app.vela.core.model.RouteLeg(300.0 + past, 40.0, null, ms)), 300.0 + past, 40.0, null)
    }

    @Test fun `the last turn of a trip is a candidate when it is a bare turn near the end`() {
        val (k, turnM, left) = NavEngine.destinationLotTurns(tripInto(70.0)).single()
        assertEquals(1, k)
        assertEquals(300.0, turnM, 1.0)
        assertTrue(left)
    }

    @Test fun `a named street, a far arrival or a turn that is not one is no candidate`() {
        assertTrue(NavEngine.destinationLotTurns(tripInto(70.0, road = "B Street")).isEmpty())
        assertTrue(NavEngine.destinationLotTurns(tripInto(NavEngine.LOT_DEST_BACK_M + 30.0)).isEmpty())
        assertTrue(NavEngine.destinationLotTurns(tripInto(70.0, type = app.vela.core.model.ManeuverType.ROUNDABOUT)).isEmpty())
    }

    // East along a street, left into a lot, 50 m north, right along an aisle, arriving 90 m east.
    private fun tripThroughLot(): app.vela.core.model.Route {
        val line = listOf(pt(-300.0, 0.0), pt(0.0, 0.0), pt(0.0, 50.0), pt(90.0, 50.0))
        val ms = listOf(
            app.vela.core.model.Maneuver(app.vela.core.model.ManeuverType.DEPART, "Head east on 3rd Street", line[0], 300.0, 30.0, road = "3rd Street"),
            app.vela.core.model.Maneuver(app.vela.core.model.ManeuverType.TURN_LEFT, "Turn left", line[1], 50.0, 8.0, instructionNoRoad = "Turn left"),
            app.vela.core.model.Maneuver(app.vela.core.model.ManeuverType.TURN_RIGHT, "Turn right", line[2], 90.0, 12.0, instructionNoRoad = "Turn right"),
            app.vela.core.model.Maneuver(app.vela.core.model.ManeuverType.ARRIVE, "Arrive", line[3], 0.0, 0.0),
        )
        return app.vela.core.model.Route(line, listOf(app.vela.core.model.RouteLeg(440.0, 50.0, null, ms)), 440.0, 50.0, null)
    }

    @Test fun `the same turns are found before a stop's mark, and the first into a lot is the stop's lot turn`() {
        // The stop sits 30 m along the aisle: its mark is 380 m along the line.
        val trip = tripThroughLot()
        val turns = NavEngine.lotTurnsBefore(trip, 380.0)
        assertEquals(listOf(1, 2), turns.map { it.first })
        assertTrue("a stop far past the turns has none", NavEngine.lotTurnsBefore(tripInto(NavEngine.LOT_DEST_BACK_M + 50.0), NavEngine.LOT_DEST_BACK_M + 340.0).isEmpty())
    }

    @Test fun `the bare turns that close a trip are candidates in the order driven`() {
        val turns = NavEngine.destinationLotTurns(tripThroughLot())
        assertEquals(listOf(1, 2), turns.map { it.first })
        assertEquals(300.0, turns[0].second, 1.0)
        assertEquals(350.0, turns[1].second, 1.0)
        assertEquals(listOf(true, false), turns.map { it.third })
    }

    @Test fun `the turn off the street is the one worded, not the turn between the aisles`() {
        val entrance = RoadLine("service", null, listOf(pt(0.0, 0.0), pt(0.0, 20.0)))
        val aisleNorth = RoadLine("service", "parking_aisle", listOf(pt(0.0, 20.0), pt(0.0, 50.0)))
        val aisleEast = RoadLine("service", "parking_aisle", listOf(pt(0.0, 50.0), pt(120.0, 50.0)))
        withRoads(listOf(street, entrance, aisleNorth, aisleEast)) {
            val r = ParkingLotTurn.wordDestination(tripThroughLot()) { left -> if (left) "Turn left into the parking lot" else "Turn right into the parking lot" }
            assertEquals("Turn left into the parking lot", r.maneuvers[1].instruction)
            assertEquals("the turn inside the lot keeps its words", "Turn right", r.maneuvers[2].instruction)
        }
    }

    @Test fun `a lane first and the lot after it words the turn into the lot`() {
        // The first bare turn is onto an unnamed lane; the lot is entered by the second.
        val lane = RoadLine("minor", null, listOf(pt(0.0, 0.0), pt(0.0, 50.0)))
        val aisleEast = RoadLine("service", "parking_aisle", listOf(pt(0.0, 50.0), pt(120.0, 50.0)))
        withRoads(listOf(street, lane, aisleEast)) {
            val r = ParkingLotTurn.wordDestination(tripThroughLot()) { left -> if (left) "L" else "R" }
            assertEquals("Turn left", r.maneuvers[1].instruction)
            assertEquals("R", r.maneuvers[2].instruction)
        }
    }

    private fun withRoads(roads: List<RoadLine>, body: suspend () -> Unit) = kotlinx.coroutines.runBlocking {
        val before = RoadNameTiles.fetch
        try {
            RoadNameTiles.testRoads = roads
            body()
        } finally {
            RoadNameTiles.testRoads = null
            RoadNameTiles.fetch = before
        }
    }

    @Test fun `the last turn into the destination's lot says so, in every place the step is read`() {
        val aisle = RoadLine("service", "parking_aisle", listOf(pt(0.0, 0.0), pt(0.0, 90.0)))
        withRoads(listOf(street, aisle)) {
            val r = ParkingLotTurn.wordDestination(tripInto(70.0)) { left -> if (left) "Turn left into the parking lot" else "Turn right into the parking lot" }
            val turn = r.maneuvers[1]
            assertEquals("Turn left into the parking lot", turn.instruction)
            assertEquals("the voice says it with street names off too", "Turn left into the parking lot", turn.instructionNoRoad)
            assertEquals("the step is still a left turn, where it was", app.vela.core.model.ManeuverType.TURN_LEFT, turn.type)
            assertEquals(null, turn.road)
            assertTrue(r.made.contains("lotTurn"))
            assertEquals("the other steps are untouched", "Head east on 3rd Street", r.maneuvers[0].instruction)
        }
    }

    @Test fun `a last turn onto a plain unnamed lane keeps its words`() {
        val lane = RoadLine("minor", null, listOf(pt(0.0, 0.0), pt(0.0, 90.0)))
        withRoads(listOf(street, lane)) {
            val trip = tripInto(70.0)
            assertTrue(ParkingLotTurn.wordDestination(trip) { "x" } === trip)
        }
    }

    @Test fun `with no map to ask the turn keeps its words`() = kotlinx.coroutines.runBlocking {
        val before = RoadNameTiles.fetch
        try {
            RoadNameTiles.fetch = null
            val trip = tripInto(70.0)
            assertTrue(ParkingLotTurn.wordDestination(trip) { "x" } === trip)
        } finally { RoadNameTiles.fetch = before }
    }
}
