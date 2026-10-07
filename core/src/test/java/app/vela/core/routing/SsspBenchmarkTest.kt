package app.vela.core.routing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * Honest head-to-head: the sorting-barrier algorithm vs binary-heap Dijkstra on the same
 * graphs, same JVM. Opt-in like the repo's obf probe tests (`-DvelaBench=true`) so unit CI
 * stays fast; run locally to reproduce the numbers quoted in FORK.md.
 *
 * Expectation set in advance (from the authors' implementation study arXiv:2511.03007):
 * BMSSP loses wall-clock at these sizes. The point of this harness is to MEASURE, and to
 * show the ratio shrinking as n grows - not to win.
 */
class SsspBenchmarkTest {

    private fun sparseRandom(n: Int, seed: Long): SsspGraph {
        val rng = Random(seed)
        val g = SsspGraph(n)
        // 2 outgoing edges per vertex on average, weights 1..20000 ms
        repeat(2 * n) {
            g.addEdge(rng.nextInt(n), rng.nextInt(n), 1L + rng.nextInt(20000))
        }
        return g
    }

    /** Road-like lattice: side x side blocks, two lanes each direction + arterials. */
    private fun roadLattice(side: Int, seed: Long): SsspGraph {
        val rng = Random(seed)
        val n = side * side
        val g = SsspGraph(n)
        fun id(r: Int, c: Int) = r * side + c
        for (r in 0 until side) for (c in 0 until side) {
            if (r + 1 < side) {
                val w = 2000L + rng.nextInt(6000)
                g.addEdge(id(r, c), id(r + 1, c), w)
                if (rng.nextDouble() < 0.5) g.addEdge(id(r + 1, c), id(r, c), 2000L + rng.nextInt(6000))
            }
            if (c + 1 < side) {
                val w = 2000L + rng.nextInt(6000)
                g.addEdge(id(r, c), id(r, c + 1), w)
                if (rng.nextDouble() < 0.5) g.addEdge(id(r, c + 1), id(r, c), 2000L + rng.nextInt(6000))
            }
            // arterials: fast long hops on every 4th row/column
            if (r % 4 == 0 && r + 4 < side) g.addEdge(id(r, c), id(r + 4, c), 4000L)
            if (c % 4 == 0 && c + 4 < side) g.addEdge(id(r, c), id(r, c + 4), 4000L)
        }
        return g
    }

    private fun bench(label: String, g: SsspGraph, source: Int) {
        val adj = g.adjacency()

        val t0 = System.nanoTime()
        val dijkstra = DijkstraSssp.run(adj, source)
        val tD = (System.nanoTime() - t0) / 1_000_000

        val bmssp = Bmssp(g)
        bmssp.prepareGraph(true)
        val t1 = System.nanoTime()
        val result = bmssp.execute(source)
        val tB = (System.nanoTime() - t1) / 1_000_000

        // The benchmark is only meaningful if the answer is the answer.
        assertTrue("$label: distance vectors differ", dijkstra.distanceMs.contentEquals(result.distanceMs))

        val reached = result.distanceMs.count { it < OO }
        println(
            "bench %-22s n=%7d  dijkstra=%8d ms  bmssp=%8d ms  ratio=%.2fx  reached=%d/%d"
                .format(label, g.vertexCount, tD, tB, tB.toDouble() / maxOf(1, tD), reached, g.vertexCount),
        )
    }

    @Test
    fun benchmarkOnGrowingGraphs() {
        assumeTrue(System.getProperty("velaBench") == "true")
        bench("random-sparse", sparseRandom(1 shl 12, 11), 0)
        bench("random-sparse", sparseRandom(1 shl 14, 12), 0)
        bench("random-sparse", sparseRandom(1 shl 16, 13), 0)
        bench("road-lattice", roadLattice(90, 14), 4050)
        bench("road-lattice", roadLattice(200, 15), 20000)
        bench("road-lattice", roadLattice(320, 16), 51200)
    }
}
