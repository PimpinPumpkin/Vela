package app.vela.core.routing

/**
 * Shared plumbing for the on-device SSSP engines in this package: the weighted directed
 * road graph, the totally-ordered distance key the sorting-barrier algorithm needs, and a
 * textbook Dijkstra baseline used to PROVE the new algorithm correct and to benchmark it.
 *
 * Weights are integer milliseconds. Integer weights mean the distance keys are exact
 * (no float rounding), which is what lets the correctness tests demand bit-equal
 * distances against the baseline. A graph source that only knows real-valued weights
 * (e.g. the obf engine's m/s speeds) must quantize to whole milliseconds first.
 */

/** One directed edge: `to` vertex, `weightMs` travel time. */
class OutEdge(val to: Int, val weightMs: Long) {
    init {
        require(weightMs >= 0) { "negative edge weight $weightMs (Dijkstra-family SSSP requires non-negative weights)" }
    }
}

/** Mutable adjacency-list graph. Vertices are 0..[vertexCount)-1. */
class SsspGraph(val vertexCount: Int) {
    private val adj = arrayOfNewArrayList(vertexCount)

    fun addEdge(from: Int, to: Int, weightMs: Long) {
        require(from in 0 until vertexCount && to in 0 until vertexCount) {
            "edge $from->$to outside 0..$vertexCount"
        }
        adj[from].add(OutEdge(to, weightMs))
    }

    fun degree(from: Int): Int = adj[from].size

    /** Frozen adjacency, the form both SSSP engines consume. */
    fun adjacency(): Array<Array<OutEdge>> =
        Array(vertexCount) { i -> adj[i].toTypedArray() }

    private companion object {
        fun arrayOfNewArrayList(n: Int) = Array(n) { ArrayList<OutEdge>() }
    }
}

/**
 * The unique distance key (paper Assumption 2.1, "distinct distances"): a total order over
 * (distance, path-edge-count, vertex, predecessor) so no two queue entries ever compare equal,
 * which the recursion's "complete sets are disjoint" invariants rely on. With integer weights
 * the primary key is already exact; the tie-breakers make the order strict regardless.
 */
data class UDist(val dist: Long, val pathEdges: Int, val vertex: Int, val pred: Int) : Comparable<UDist> {
    override fun compareTo(other: UDist): Int {
        if (dist != other.dist) return if (dist < other.dist) -1 else 1
        if (pathEdges != other.pathEdges) return pathEdges - other.pathEdges
        if (vertex != other.vertex) return vertex - other.vertex
        return pred - other.pred
    }
}

/** One SSSP answer: distances and a predecessor tree, both indexed by REAL vertex ids. */
class SsspResult internal constructor(
    val distanceMs: LongArray,
    val predecessor: IntArray,
    /** Bmssp-only extras for path reconstruction; null for the baseline. */
    private val pathEdges: IntArray?,
) {
    /** Distance from the source to [vertex] in ms, or [OO] if unreachable. */
    fun distanceTo(vertex: Int): Long = distanceMs[vertex]

    /**
     * The shortest path source..vertex as vertex ids, or null if vertex is unreachable.
     * Walks the predecessor tree to its self-loop (the source) and reverses. The path-edge
     * counter (when available) is only a safety cap, because the constant-degree transform
     * counts zero-weight cycle hops that the real predecessor chain does not have.
     */
    fun pathTo(vertex: Int): IntArray? {
        if (distanceMs[vertex] >= OO) return null
        val cap = (pathEdges?.get(vertex)?.plus(1) ?: distanceMs.size + 1)
            .coerceAtMost(distanceMs.size + 1)
        val trail = IntArray(cap)
        var len = 0
        var u = vertex
        while (true) {
            trail[len++] = u
            val p = predecessor[u]
            if (p == u) break
            if (len >= cap) return null // predecessor cycle: cannot happen for a consistent tree
            u = p
        }
        val path = trail.copyOfRange(0, len)
        path.reverse()
        return path
    }
}

/** The "infinity" sentinel, mirroring the reference implementation (std::numeric_limits max / 10). */
const val OO: Long = Long.MAX_VALUE / 10

/**
 * Dijkstra with a binary heap - the post-1984 incumbent this fork exists to challenge.
 * O(m + n log n). Used only as the correctness oracle + benchmark baseline, never as the
 * shipping router; keeping it in the shipped binary is what lets the phone verify the new
 * engine against a known-good one on the same graph.
 */
object DijkstraSssp {
    fun run(adjacency: Array<Array<OutEdge>>, source: Int): SsspResult {
        val n = adjacency.size
        val dist = LongArray(n) { OO }
        val pred = IntArray(n) { it }
        val heap = java.util.PriorityQueue<Pair<Long, Int>>(n.coerceAtLeast(16)) { a, b -> a.first.compareTo(b.first) }
        dist[source] = 0
        heap.add(0L to source)
        while (heap.isNotEmpty()) {
            val (du, u) = heap.poll()
            if (du > dist[u]) continue // stale entry
            for (e in adjacency[u]) {
                val nd = du + e.weightMs
                if (nd < dist[e.to]) {
                    dist[e.to] = nd
                    pred[e.to] = u
                    heap.add(nd to e.to)
                }
            }
        }
        return SsspResult(dist, pred, null)
    }
}
