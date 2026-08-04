package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the map-declutter clustering: a four-way stop's per-approach nodes must merge to ONE
 *  drawn glyph at the intersection, while neighbouring intersections stay separate. */
class MapDeclutterTest {

    // ~0.0001 deg latitude = ~11 m; a four-way stop's nodes sit 10-20 m from the junction centre.
    private fun p(lat: Double, lng: Double) = LatLng(lat, lng)

    @Test
    fun `four approach nodes merge to one cluster at the centroid`() {
        val junction = listOf(
            p(38.5000, -121.7000), p(38.5002, -121.7000), // north + south approach
            p(38.5001, -121.7002), p(38.5001, -121.6998), // west + east approach
        )
        val clusters = MapDeclutter.cluster(junction, 30.0) { it }
        assertEquals(1, clusters.size)
        assertEquals(4, clusters[0].members.size)
        assertEquals(38.5001, clusters[0].centroid.lat, 1e-4)
    }

    @Test
    fun `adjacent intersections on a dense grid stay separate`() {
        // Two junctions ~90 m apart (0.0008 deg lat) - a tight urban grid.
        val a = listOf(p(38.5000, -121.7000), p(38.5001, -121.7000))
        val b = listOf(p(38.5008, -121.7000), p(38.5009, -121.7000))
        val clusters = MapDeclutter.cluster(a + b, 30.0) { it }
        assertEquals(2, clusters.size)
    }

    @Test
    fun `a lone point stays a lone cluster`() {
        val clusters = MapDeclutter.cluster(listOf(p(38.5, -121.7)), 40.0) { it }
        assertEquals(1, clusters.size)
        assertEquals(1, clusters[0].members.size)
    }

    @Test
    fun `distance approximation is metre-accurate at street scale`() {
        // 0.0009 deg latitude = ~100 m.
        val d = MapDeclutter.approxDistanceM(p(38.5, -121.7), p(38.5009, -121.7))
        assertEquals(100.0, d, 1.0)
    }
}
