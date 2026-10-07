package app.vela.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * BMSSP vs the Dijkstra baseline on seeded random directed graphs, both transform modes.
 * The oracle is exact: integer millisecond weights make distance comparison bit-equal.
 */
class SsspCorrectnessTest {

    private fun randomGraph(rng: Random, n: Int, m: Int, maxW: Int): SsspGraph {
        val g = SsspGraph(n)
        repeat(m) {
            val from = rng.nextInt(n)
            val to = rng.nextInt(n)
            g.addEdge(from, to, rng.nextInt(maxW).toLong())
        }
        return g
    }

    private fun assertMatchesBaseline(g: SsspGraph, source: Int, constantDegree: Boolean) {
        val baseline = DijkstraSssp.run(g.adjacency(), source)
        val bmssp = Bmssp(g)
        bmssp.prepareGraph(constantDegree)
        val result = bmssp.execute(source)

        assertEquals(
            "distance vector mismatch (cd=$constantDegree, n=${g.vertexCount})",
            baseline.distanceMs.toList(),
            result.distanceMs.toList(),
        )

        for (v in 0 until g.vertexCount) {
            if (result.distanceTo(v) >= OO) {
                assertNull("unreachable vertex $v got a path (cd=$constantDegree)", result.pathTo(v))
                continue
            }
            val path = assertNotNullSafe(result.pathTo(v), v, constantDegree)
            assertEquals("path must start at source (cd=$constantDegree)", source.toLong(), path[0].toLong())
            assertEquals("path must end at target (cd=$constantDegree)", v.toLong(), path[path.size - 1].toLong())
            var sum = 0L
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                val hop = result.distanceTo(b) - result.distanceTo(a)
                assertTrue(
                    "path edge $a->$b not present with weight $hop (cd=$constantDegree)",
                    g.adjacency()[a].any { it.to == b && it.weightMs == hop },
                )
                sum += hop
            }
            assertEquals("path length must equal distance (cd=$constantDegree)", result.distanceTo(v), sum)
        }
    }

    private fun assertNotNullSafe(path: IntArray?, v: Int, cd: Boolean): IntArray {
        assertNotNull("reachable vertex $v has no path (cd=$cd)", path)
        return path!!
    }

    @Test
    fun randomDirectedGraphsMatchBaselineBothModes() {
        for (seed in 0L until 30L) {
            val rng = Random(seed)
            val n = 2 + rng.nextInt(240)
            val m = rng.nextInt(4 * n)
            val g = randomGraph(rng, n, m, 5000)
            val source = rng.nextInt(n)
            assertMatchesBaseline(g, source, true)
            assertMatchesBaseline(g, source, false)
        }
    }

    @Test
    fun zeroWeightEdgesAndSelfLoops() {
        val g = SsspGraph(4)
        g.addEdge(0, 1, 0)
        g.addEdge(1, 2, 0)
        g.addEdge(2, 2, 7) // self-loop
        g.addEdge(0, 0, 3) // self-loop on the source
        g.addEdge(1, 3, 10)
        assertMatchesBaseline(g, 0, true)
        assertMatchesBaseline(g, 0, false)
    }

    @Test
    fun parallelEdgesKeepTheMinimum() {
        val g = SsspGraph(2)
        g.addEdge(0, 1, 100)
        g.addEdge(0, 1, 40) // dedup must keep this one
        g.addEdge(0, 1, 70)
        val bmssp = Bmssp(g)
        bmssp.prepareGraph(true)
        assertEquals(40L, bmssp.execute(0).distanceTo(1))
    }

    @Test
    fun singleVertexGraph() {
        val g = SsspGraph(1)
        val bmssp = Bmssp(g)
        bmssp.prepareGraph(true)
        val r = bmssp.execute(0)
        assertEquals(0L, r.distanceTo(0))
        assertEquals(listOf(0), r.pathTo(0)?.toList())
    }

    @Test
    fun unreachableVerticesStayInfinite() {
        val g = SsspGraph(3)
        g.addEdge(0, 1, 5)
        val bmssp = Bmssp(g)
        bmssp.prepareGraph(false)
        val r = bmssp.execute(0)
        assertEquals(5L, r.distanceTo(1))
        assertEquals(OO, r.distanceTo(2))
    }

    /** Manhattan-style lattice with one-way streets: the closest shape to a road network. */
    @Test
    fun directedGridMatchesBaseline() {
        val side = 24
        val g = SsspGraph(side * side)
        val rng = Random(7)
        fun id(r: Int, c: Int) = r * side + c
        for (r in 0 until side) for (c in 0 until side) {
            if (r + 1 < side) g.addEdge(id(r, c), id(r + 1, c), 1000L + rng.nextInt(2000))
            if (c + 1 < side) {
                // east bound every other row, west bound on the rest
                if (r % 2 == 0) g.addEdge(id(r, c), id(r, c + 1), 1000L + rng.nextInt(2000))
                else g.addEdge(id(r, c + 1), id(r, c), 1000L + rng.nextInt(2000))
            }
        }
        assertMatchesBaseline(g, id(3, 5), true)
    }

    @Test
    fun preparedInstanceAnswersEverySource() {
        // The obf engine caches one prepared Bmssp per coverage box and reroutes by calling
        // execute() again on it; the transform is source-independent and execute() resets
        // scratch, so each source must match the Dijkstra oracle exactly.
        val rng = Random(987654321L)
        for (constantDegree in booleanArrayOf(false, true)) {
            val g = randomGraph(rng, 512, 1400, 5000)
            val bmssp = Bmssp(g)
            bmssp.prepareGraph(constantDegree)
            repeat(4) { attempt ->
                val source = rng.nextInt(g.vertexCount)
                val baseline = DijkstraSssp.run(g.adjacency(), source)
                val result = bmssp.execute(source)
                assertEquals(
                    "reused prepareGraph mismatch (cd=$constantDegree, source=$source, attempt=$attempt)",
                    baseline.distanceMs.toList(),
                    result.distanceMs.toList(),
                )
                for (v in 0 until g.vertexCount) {
                    if (result.distanceTo(v) >= OO) continue
                    val path = assertNotNullSafe(result.pathTo(v), v, constantDegree)
                    assertEquals(source.toLong(), path[0].toLong())
                    assertEquals(v.toLong(), path[path.size - 1].toLong())
                }
            }
        }
    }
}
