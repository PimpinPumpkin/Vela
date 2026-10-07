package app.vela.core.routing

import app.vela.core.routing.RoadGraphBuilder.Companion.packE7
import app.vela.core.routing.RoadGraphBuilder.Companion.weightMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snap/weld/direction semantics of the road graph builder, independent of any map reader. */
class RoadGraphBuilderTest {

    private fun p(lat: Int, lon: Int) = packE7(lat, lon)

    @Test
    fun crossingRoadsWeldAtSharedCoordinate() {
        val b = RoadGraphBuilder()
        // east-west road through (1000000, 1000000); north-south road crosses exactly there
        b.addPolyline(
            RoadGraphBuilder.RoadPolyline(
                longArrayOf(p(1000000, 900000), p(1000000, 1000000), p(1000000, 1100000)),
                longArrayOf(5000, 5000),
            ),
        )
        b.addPolyline(
            RoadGraphBuilder.RoadPolyline(
                longArrayOf(p(900000, 1000000), p(1000000, 1000000), p(1100000, 1000000)),
                longArrayOf(4000, 4000),
            ),
        )
        val net = b.build()
        // 6 endpoints but ONE shared crossing -> 5 nodes
        assertEquals(5, net.graph.vertexCount)

        val crossing = net.nearestNode(1000000, 1000000, 100)!!
        assertEquals(4, net.graph.degree(crossing)) // four arms out of the crossing

        // Straight across the crossing: 5000 + 5000 ms west arm to east arm
        val west = net.nearestNode(1000000, 900000, 100)!!
        val east = net.nearestNode(1000000, 1100000, 100)!!
        val res = DijkstraSssp.run(net.graph.adjacency(), west)
        assertEquals(10000L, res.distanceTo(east))
    }

    @Test
    fun nearMissCoordinatesDoNotWeld() {
        val b = RoadGraphBuilder()
        b.addPolyline(
            RoadGraphBuilder.RoadPolyline(longArrayOf(p(1000000, 900000), p(1000000, 1000000)), longArrayOf(1000)),
        )
        b.addPolyline(
            // 1e-7 degrees apart = 1.1 cm: visually touching, structurally NOT an intersection
            RoadGraphBuilder.RoadPolyline(longArrayOf(p(999999, 1000000), p(1000001, 1000000)), longArrayOf(1000)),
        )
        val net = b.build()
        assertEquals(4, net.graph.vertexCount) // no welding
        assertNull(net.nearestNode(2000000, 2000000, 100)) // far away -> null
        assertNotNull(net.nearestNode(1000000, 1000000, 300))
    }

    @Test
    fun oneWayPolylineOnlyAddsForwardEdges() {
        val b = RoadGraphBuilder()
        b.addPolyline(
            RoadGraphBuilder.RoadPolyline(
                longArrayOf(p(0, 0), p(0, 1000)),
                longArrayOf(3000),
                forward = true,
                backward = false,
            ),
        )
        val net = b.build()
        val adj = net.graph.adjacency()
        assertEquals(1, adj[0].size)
        assertEquals(0, adj[1].size)
        val r = DijkstraSssp.run(adj, 1)
        assertEquals(OO, r.distanceTo(0))
    }

    @Test
    fun degenerateRepeatedPointsCollapse() {
        val b = RoadGraphBuilder()
        b.addPolyline(
            RoadGraphBuilder.RoadPolyline(
                longArrayOf(p(0, 0), p(0, 0), p(0, 500)),
                longArrayOf(700, 700), // the zero-length hop is skipped, never added
            ),
        )
        val net = b.build()
        assertEquals(2, net.graph.vertexCount)
        val r = DijkstraSssp.run(net.graph.adjacency(), 0)
        assertTrue(r.distanceTo(1) in 1..700)
    }

    @Test
    fun weightsUseSpeedFallbackAndFloorAtOneMs() {
        // 111 m at 40 km/h (fallback for a bad speed value) ~= 10.0 s
        val w = weightMs(111.0, 0.0)
        assertTrue("fallback weight was $w", w in 9900..10100)
        // sub-millisecond hop still weights >= 1 ms
        assertEquals(1L, weightMs(0.01, 100.0))
    }
}
