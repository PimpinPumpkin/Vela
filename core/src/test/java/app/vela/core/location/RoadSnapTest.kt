package app.vela.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** The no-route dot snap (issue #803), on made-up roads around a Davis fixture point. */
class RoadSnapTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val ky = 111_320.0
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))

    /** A point [e] meters east and [n] meters north of the fixture. */
    private fun at(e: Double, n: Double) = doubleArrayOf(lat0 + n / ky, lng0 + e / kx)

    /** A straight road through the given (east, north) points in meters. */
    private fun road(vararg en: Pair<Double, Double>, oneway: Int = 0, penalty: Double = 0.0) = RoadSnap.Road(
        en.map { lat0 + it.second / ky }.toDoubleArray(),
        en.map { lng0 + it.first / kx }.toDoubleArray(),
        oneway, penalty,
    )

    private val eastWest = road(-500.0 to 0.0, 500.0 to 0.0)

    private fun snapAt(s: RoadSnap, e: Double, n: Double, speed: Double, course: Double?, roads: List<RoadSnap.Road>?, ms: Long): Boolean {
        val p = at(e, n)
        return s.onFix(p[0], p[1], speed, course, roads, ms)
    }

    /** Where the snapped dot is, in meters from the fixture. */
    private fun dot(s: RoadSnap, e: Double, n: Double): Pair<Double, Double> {
        val p = at(e, n)
        return ((p[1] + s.offLng - lng0) * kx) to ((p[0] + s.offLat - lat0) * ky)
    }

    @Test fun `a fix beside the road it runs along moves onto it`() {
        val s = RoadSnap()
        assertTrue(snapAt(s, 0.0, 18.0, 12.0, 90.0, listOf(eastWest), 0))
        val (e, n) = dot(s, 0.0, 18.0)
        assertEquals(0.0, e, 0.2)
        assertEquals(0.0, n, 0.2)
        assertEquals(18.0, s.lastDistM, 0.2)
    }

    @Test fun `a road across the course is left alone`() {
        val s = RoadSnap()
        assertFalse(snapAt(s, 0.0, 10.0, 12.0, 0.0, listOf(eastWest), 0))
    }

    @Test fun `a road too far away is left alone`() {
        val s = RoadSnap()
        assertFalse(snapAt(s, 0.0, 35.0, 12.0, 90.0, listOf(eastWest), 0))
    }

    @Test fun `walking pace does not start a snap`() {
        val s = RoadSnap()
        assertFalse(snapAt(s, 0.0, 10.0, 2.0, 90.0, listOf(eastWest), 0))
        assertFalse(snapAt(s, 0.0, 10.0, 12.0, null, listOf(eastWest), 1000))
    }

    @Test fun `two parallel roads about as near are a guess`() {
        val s = RoadSnap()
        val north = road(-500.0 to 20.0, 500.0 to 20.0)
        assertFalse(snapAt(s, 0.0, 9.0, 12.0, 90.0, listOf(eastWest, north), 0))
    }

    @Test fun `a street beats a nearer alley behind the houses`() {
        val s = RoadSnap()
        val alley = road(-500.0 to 30.0, 500.0 to 30.0, penalty = RoadSnap.SERVICE_PENALTY_M)
        assertTrue(snapAt(s, 0.0, 19.0, 12.0, 90.0, listOf(eastWest, alley), 0))
        assertEquals(0.0, dot(s, 0.0, 19.0).second, 0.2)
    }

    @Test fun `a divided road takes the carriageway going the car's way`() {
        val s = RoadSnap()
        val eastbound = road(-500.0 to 0.0, 500.0 to 0.0, oneway = 1)
        val westbound = road(500.0 to 14.0, -500.0 to 14.0, oneway = 1)
        // Nearer the westbound line, driving east.
        assertTrue(snapAt(s, 0.0, 10.0, 25.0, 90.0, listOf(eastbound, westbound), 0))
        assertEquals(0.0, dot(s, 0.0, 10.0).second, 0.2)
        // Driving west from the same spot takes the other one.
        val w = RoadSnap()
        assertTrue(snapAt(w, 0.0, 4.0, 25.0, 270.0, listOf(eastbound, westbound), 0))
        assertEquals(14.0, dot(w, 0.0, 4.0).second, 0.2)
    }

    @Test fun `going straight through a junction stays on the road`() {
        val s = RoadSnap()
        val northSouth = road(0.0 to -500.0, 0.0 to 500.0)
        assertTrue(snapAt(s, 2.0, 12.0, 12.0, 90.0, listOf(eastWest, northSouth), 0))
        assertEquals(0.0, dot(s, 2.0, 12.0).second, 0.2)
    }

    @Test fun `a fix between the two roads of a fork lets go`() {
        val main = road(-500.0 to 0.0, 500.0 to 0.0)
        val branch = road(0.0 to 0.0, 300.0 to 60.0) // leaves at about 11 degrees
        assertTrue(snapAt(RoadSnap(), -100.0, 8.0, 20.0, 90.0, listOf(main, branch), 0))
        // 100 m past the split the lines are 20 m apart.
        assertFalse(snapAt(RoadSnap(), 100.0, 10.0, 20.0, 85.0, listOf(main, branch), 0))
    }

    @Test fun `no road for two fixes lets go, one is held`() {
        val s = RoadSnap()
        assertTrue(snapAt(s, 0.0, 15.0, 12.0, 90.0, listOf(eastWest), 0))
        assertTrue(snapAt(s, 12.0, 15.0, 12.0, 90.0, emptyList(), 1000))
        assertFalse(snapAt(s, 24.0, 15.0, 12.0, 90.0, emptyList(), 2000))
    }

    @Test fun `a stop holds the road until the car has moved off it`() {
        val s = RoadSnap()
        assertTrue(snapAt(s, 0.0, 15.0, 12.0, 90.0, listOf(eastWest), 0))
        assertTrue(snapAt(s, 3.0, 16.0, 0.0, null, listOf(eastWest), 1000))
        assertEquals(0.0, dot(s, 3.0, 16.0).second, 1.5)
        // Into a parking lot at a crawl: 30 m from where the snap was set, it lets go.
        assertFalse(snapAt(s, 10.0, 45.0, 2.0, 0.0, listOf(eastWest), 9000))
        assertNull(s.lastDistM.takeIf { !it.isNaN() })
    }

    @Test fun `the road the dot was on wins a near tie`() {
        val north = road(-500.0 to 20.0, 500.0 to 20.0)
        // 8 m from one road and 12 from the other is a guess for a fresh snap...
        assertFalse(snapAt(RoadSnap(), 12.0, 8.0, 12.0, 90.0, listOf(eastWest, north), 0))
        // ...but not for a dot that was on the nearer road a second ago.
        val s = RoadSnap()
        assertTrue(snapAt(s, 0.0, 3.0, 12.0, 90.0, listOf(eastWest, north), 0))
        assertTrue(snapAt(s, 12.0, 8.0, 12.0, 90.0, listOf(eastWest, north), 1000))
        assertEquals(0.0, dot(s, 12.0, 8.0).second, 0.2)
    }

    @Test fun `only roads a car drives at speed count`() {
        assertEquals(0.0, RoadSnap.penaltyFor("primary", null)!!, 0.0)
        assertEquals(0.0, RoadSnap.penaltyFor("minor", null)!!, 0.0)
        assertEquals(RoadSnap.SERVICE_PENALTY_M, RoadSnap.penaltyFor("service", "alley")!!, 0.0)
        assertNull(RoadSnap.penaltyFor("service", "parking_aisle"))
        assertNull(RoadSnap.penaltyFor("path", null))
        assertNull(RoadSnap.penaltyFor("rail", null))
        assertNull(RoadSnap.penaltyFor("primary_construction", null))
        assertNull(RoadSnap.penaltyFor(null, null))
    }
}
