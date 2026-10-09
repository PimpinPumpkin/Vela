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
}
