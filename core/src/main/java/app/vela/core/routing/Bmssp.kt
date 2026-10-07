package app.vela.core.routing

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2

/**
 * Deterministic single-source shortest paths in O(m log^(2/3) n) expected comparison time -
 * Duan, Mao, Mao, Shu, Yin, "Breaking the Sorting Barrier for Directed Single-Source Shortest
 * Paths", arXiv:2504.17033 (STOC 2025), the first comparison-based SSSP below n log n.
 *
 * This is a faithful Kotlin port of the authors' MIT-licensed reference implementation
 * (`bmssp-expected.hpp`, github.com/lcs147/bmssp): the pivot-finding Bellman-Ford-style pass
 * (their Algorithm 1), the k+1-extractions Dijkstra base case (Algorithm 2), and the recursive
 * batched-queue routine (Algorithm 3) over [BatchPQ]. Edge weights must be non-negative
 * integer milliseconds; integer weights make [UDist] exact (the reference only sanitizes
 * floating point weights).
 *
 * Theoretical caveat stated honestly: the O(m log^(2/3) n) bound hides large constants. The
 * authors' own implementation study (arXiv:2511.03007) measures this faithful algorithm at
 * several times slower than a tuned binary-heap Dijkstra for graphs far below n = 10^8. It is
 * shipped here because asymptotics matter at city-scale road networks, and because the fork
 * must be measured, not marketed. See core/src/test .../SsspBenchmarkTest.kt and FORK.md.
 *
 * Documented deviations from the C++ reference (semantics preserved):
 *  - `treesz`/`last_complete_lvl` are IntArray, not short: batch quotas at city scale overflow
 *    16 bits where the reference would wrap.
 *  - k/t are clamped to >= 1 for tiny graphs (the reference's pow(log2 n, 1/3) floors to 0 at
 *    n < 8, which then divides by zero in the level count).
 *  - batch_size/quota bit-shifts clamp instead of overflowing the shift count.
 *
 * Not thread-safe: one instance per routing call (graph construction is owned by the caller).
 */
class Bmssp(private val n: Int, private val oriAdj: Array<Array<OutEdge>>) {
    constructor(graph: SsspGraph) : this(graph.vertexCount, graph.adjacency())

    // CSR layout of the active graph: out-edges of u are adjTo[adjOff[u] until adjOff[u+1]),
    // same vertex order and same per-vertex edge order the Array<Array<OutEdge>> layout had.
    private var adjV = 0
    private var adjOff = IntArray(1)
    private var adjTo = IntArray(0)
    private var adjW = LongArray(0)
    private var d = LongArray(0)
    private var pred = IntArray(0)
    private var pathSz = IntArray(0)
    private var nodeMap = IntArray(0)
    private var nodeRevMap = IntArray(0)
    private var cdTransformed = false

    private var root = IntArray(0)
    private var treeSz = IntArray(0)
    private var pivotVis = IntArray(0)
    private var counterPivot = 0
    private var lastCompleteLvl = IntArray(0)
    private var ds = arrayOfNulls<BatchPQ>(0)

    private var k = 1
    private var t = 1
    private var prepared = false

    /**
     * De-duplicates parallel edges (keeping the min weight) and, when [execConstantDegree]
     * is true, applies the paper's reduction that turns any directed graph into one of
     * out-degree 2: every original edge becomes a custom node; each original vertex's
     * out-edges form a zero-weight cycle; each edge-node (i->j) links to node (j->i) with the
     * real weight. Road networks are not constant-degree, so the obf engine passes true.
     *
     * Both the de-duplication and the transform build flat CSR arrays instead of the
     * reference's per-vertex std::map + std::vector structures: on road-network scale
     * (Delaware: 745k vertices, 1.48M edges) the map/ArrayList form OOMed a 512 MB heap.
     * Custom-node ids number the two orientations of each distinct pair 2q / 2q+1 in sorted
     * pair order instead of the reference's first-encounter numbering; the transformed graph
     * is an isomorphic copy (cycles still walk neighbors by ascending target, every custom
     * node keeps {cycle-next, counterpart} in that iteration order), and distances and
     * predecessor trees are invariant under the relabeling. Cycle membership mirrors the
     * reference's symmetric map entries: for every pair {i,j} that appears in either direction,
     * node (i,j) walks vertex i's cycle and node (j,i) vertex j's. prepareGraph is
     * source-independent: one prepared instance can serve any number of execute() calls.
     */
    fun prepareGraph(execConstantDegree: Boolean) {
        cdTransformed = execConstantDegree

        // Erase duplicated edges: within one source i, repeated target j keeps the min weight.
        val tmpTargetSrc = IntArray(n) { -1 }
        val tmpTargetIdx = IntArray(n) { -1 }
        val eTotal = oriAdj.sumOf { it.size }
        val candTo = IntArray(eTotal)
        val candW = LongArray(eTotal)
        val dedupOff = IntArray(n + 1)
        for (i in 0 until n) {
            var m = dedupOff[i]
            for (e in oriAdj[i]) {
                val j = e.to
                if (tmpTargetSrc[j] != i) {
                    tmpTargetSrc[j] = i
                    tmpTargetIdx[j] = m
                    candTo[m] = j
                    candW[m] = e.weightMs
                    m++
                } else {
                    val id = tmpTargetIdx[j]
                    if (e.weightMs < candW[id]) candW[id] = e.weightMs
                }
            }
            dedupOff[i + 1] = m
        }
        val mTotal = dedupOff[n]

        val total: Int
        if (!execConstantDegree) {
            adjOff = dedupOff
            adjTo = candTo
            adjW = candW
            adjV = n
            total = n
            nodeMap = IntArray(n) { it }
            nodeRevMap = IntArray(n) { it }
        } else {
            // Custom node ids: sort every distinct undirected pair once, give its two
            // orientations ids 2q (lower vertex first) and 2q+1; a self-loop uses only 2q+1
            // (the reference's std::map overwrite on a self-loop lands on the second id too,
            // and its swallowed first id becomes a degree-0 orphan exactly like ours).
            nodeMap = IntArray(n) // indexed by REAL vertex
            val und = LongArray(mTotal)
            for (i in 0 until n) {
                for (p in dedupOff[i] until dedupOff[i + 1]) {
                    val j = candTo[p]
                    und[p] = if (i < j) (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)
                             else (j.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
                }
            }
            java.util.Arrays.sort(und)
            var uq = 0
            for (p in 0 until mTotal) if (p == 0 || und[p] != und[p - 1]) und[uq++] = und[p]
            val cnt = 2 * uq + 1
            val sentinel = cnt - 1
            val idFlat = IntArray(mTotal)
            for (i in 0 until n) {
                for (p in dedupOff[i] until dedupOff[i + 1]) {
                    val j = candTo[p]
                    val ukey = if (i < j) (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)
                               else (j.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
                    val q = java.util.Arrays.binarySearch(und, 0, uq, ukey)
                    idFlat[p] = 2 * q + if (i < j) 0 else 1
                }
            }
            adjV = cnt
            nodeRevMap = IntArray(cnt)
            // The reference's edge-id maps are symmetric: a real edge (i->j) creates map entries
            // edgeId[i][j] AND edgeId[j][i], so a pair's two edge-nodes each join the zero cycle
            // of one endpoint. Arrival at node (k,j) then transfers for free along j's cycle to
            // whichever real out-edge j departs along. Every custom node therefore carries
            // exactly one cycle edge; real out-edges add the second (constant out-degree 2).
            val deg = IntArray(cnt)
            for (u in 0 until sentinel) deg[u] = 1
            for (p in 0 until mTotal) deg[idFlat[p]]++
            adjOff = IntArray(cnt + 1)
            for (u in 0 until cnt) adjOff[u + 1] = adjOff[u] + deg[u]
            adjTo = IntArray(adjOff[cnt])
            adjW = LongArray(adjOff[cnt])
            val cursor = adjOff.copyOfRange(0, cnt)
            // Reverse candidates: the (i -> j) real edge puts key i into vertex j's map. Store
            // them as (source << 32 | dedup position of the real edge).
            val inCnt = IntArray(n)
            for (i in 0 until n) for (p in dedupOff[i] until dedupOff[i + 1]) inCnt[candTo[p]]++
            val inOff = IntArray(n + 1)
            for (v in 0 until n) inOff[v + 1] = inOff[v] + inCnt[v]
            val inTo = LongArray(mTotal)
            val inCur = inOff.copyOfRange(0, n)
            for (i in 0 until n) {
                for (p in dedupOff[i] until dedupOff[i + 1]) {
                    val v = candTo[p]
                    inTo[inCur[v]++] = (i.toLong() shl 32) or (p.toLong() and 0xFFFFFFFFL)
                }
            }
            var maxKeys = 0
            for (v in 0 until n) {
                maxKeys = maxOf(maxKeys, (dedupOff[v + 1] - dedupOff[v]) + (inOff[v + 1] - inOff[v]))
            }
            val scratch = LongArray(maxKeys) // (neighbor << 32 | custom-node id), sorted by neighbor
            // Zero-weight cycles over each vertex's edge-nodes. The reference iterates a
            // std::map (ordered by target id); walk neighbors by ascending id so cycles match
            // edge-for-edge. A vertex's keys are its real out-targets union its in-sources;
            // both list kinds resolve to the same custom id for a shared neighbor, so repeated
            // neighbors are deduplicated.
            for (i in 0 until n) {
                var len = 0
                for (p in dedupOff[i] until dedupOff[i + 1]) {
                    scratch[len++] = (candTo[p].toLong() shl 32) or (idFlat[p].toLong() and 0xFFFFFFFFL)
                }
                for (q in inOff[i] until inOff[i + 1]) {
                    val e = inTo[q]
                    val src = (e ushr 32).toInt()
                    val pos = (e and 0xFFFFFFFFL).toInt()
                    val id = if (src == i) idFlat[pos] else idFlat[pos] xor 1
                    scratch[len++] = (src.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
                }
                if (len == 0) { nodeMap[i] = sentinel; continue }
                java.util.Arrays.sort(scratch, 0, len)
                var w = 0
                for (p in 0 until len) {
                    if (p == 0 || (scratch[p] ushr 32) != (scratch[p - 1] ushr 32)) scratch[w++] = scratch[p]
                }
                len = w
                for (p in 0 until len) {
                    val from = (scratch[p] and 0xFFFFFFFFL).toInt()
                    val to = (scratch[(p + 1) % len] and 0xFFFFFFFFL).toInt()
                    val at = cursor[from]++
                    adjTo[at] = to
                    adjW[at] = 0L
                    nodeRevMap[from] = i
                }
                nodeMap[i] = (scratch[0] and 0xFFFFFFFFL).toInt()
            }
            // Real edges: edge-node (i,j) -> edge-node (j,i) with the travel-time weight.
            for (i in 0 until n) {
                for (p in dedupOff[i] until dedupOff[i + 1]) {
                    val from = idFlat[p]
                    val at = cursor[from]++
                    adjTo[at] = if (i == candTo[p]) from else from xor 1
                    adjW[at] = candW[p]
                }
            }
            total = cnt
        }

        d = LongArray(total)
        root = IntArray(total)
        pred = IntArray(total)
        treeSz = IntArray(total)
        pathSz = IntArray(total)
        lastCompleteLvl = IntArray(total)
        pivotVis = IntArray(total)
        k = maxOf(1, floor(Math.pow(log2(total.toDouble()), 1.0 / 3.0)).toInt())
        t = maxOf(1, floor(Math.pow(log2(total.toDouble()), 2.0 / 3.0)).toInt())
        val levels = ceil(log2(total.toDouble()) / t).toInt().coerceAtLeast(0)
        ds = Array(levels) { BatchPQ(total) }
        prepared = true
    }

    /**
     * Run the recursive algorithm from [source] (REAL vertex id) and return distances +
     * predecessor tree indexed by REAL vertex ids. Unreachable vertices carry [OO].
     */
    fun execute(source: Int): SsspResult {
        check(prepared) { "call prepareGraph() first" }
        d.fill(OO)
        lastCompleteLvl.fill(-1)
        pivotVis.fill(-1)
        for (i in pred.indices) pred[i] = i
        pathSz.fill(0)

        var s = source
        if (cdTransformed) {
            s = nodeMap[s]
            if (s == d.size - 1) {
                // The source has no out-edges: it landed on the shared sentinel that every
                // zero-out-degree vertex shares. Anchoring BMSSP there would hand distance 0
                // to every isolated vertex. The whole answer is trivial anyway: only the
                // source is reachable, at 0.
                val distanceMs = LongArray(n) { OO }
                distanceMs[source] = 0
                return SsspResult(distanceMs, IntArray(n) { it }, IntArray(n))
            }
        }
        d[s] = 0
        pathSz[s] = 0

        val levels = ceil(log2(adjV.toDouble()) / t).toInt()
        bmsspRec(levels, UDist(OO, 0, 0, 0), intArrayOf(s))

        if (!cdTransformed) {
            return SsspResult(d.copyOf(), pred.copyOf(), pathSz.copyOf())
        }
        val distanceMs = LongArray(n) { d[nodeMap[it]] }
        val predecessor = IntArray(n) { customToReal(getPred(nodeMap[it])) }
        val pathEdges = IntArray(n) { pathSz[nodeMap[it]] }
        return SsspResult(distanceMs, predecessor, pathEdges)
    }

    // ------------------------------------------------------------------------

    private fun customToReal(id: Int): Int = nodeRevMap[id]

    /** The custom-node predecessor whose REAL vertex differs from u's (exit the cycle run). */
    private fun getPred(u: Int): Int {
        val realU = customToReal(u)
        var dad = u
        do {
            dad = pred[dad]
        } while (customToReal(dad) == realU && pred[dad] != dad)
        return dad
    }

    private fun getDist(u: Int, v: Int, w: Long) = UDist(d[u] + w, pathSz[u] + 1, v, u)
    private fun getDist(u: Int) = UDist(d[u], pathSz[u], u, pred[u])
    private fun updateDist(u: Int, v: Int, w: Long) {
        pred[v] = u
        d[v] = d[u] + w
        pathSz[v] = pathSz[u] + 1
    }

    /** Algorithm 1 (pivot finding): k Bellman-Ford-style rounds; either the batch is big
     *  enough (> k|S| reached) or the vertices whose reachability-trees hold k members pivot. */
    private fun findPivots(b: UDist, s: IntArray): Pair<IntArray, List<Int>> {
        counterPivot++

        val vis = ArrayList<Int>(2 * k * s.size)
        for (x in s) {
            vis.add(x)
            pivotVis[x] = counterPivot
        }

        var active = s
        for (x in s) { root[x] = x; treeSz[x] = 0 }
        for (round in 1..k) {
            val nwActive = ArrayList<Int>(active.size * 4)
            for (u in active) {
                for (x in adjOff[u] until adjOff[u + 1]) {
                    val v = adjTo[x]
                    val w = adjW[x]
                    if (getDist(u, v, w) <= getDist(v)) {
                        updateDist(u, v, w)
                        if (getDist(v) < b) {
                            root[v] = root[u]
                            nwActive.add(v)
                        }
                    }
                }
            }
            for (x in nwActive) {
                if (pivotVis[x] != counterPivot) {
                    pivotVis[x] = counterPivot
                    vis.add(x)
                }
            }
            if (vis.size > k * s.size) return s.copyOf() to vis
            active = nwActive.toIntArray()
        }

        val p = ArrayList<Int>(vis.size / k + 1)
        for (u in vis) treeSz[root[u]]++
        for (u in s) if (treeSz[u] >= k) p.add(u)
        return p.toIntArray() to vis
    }

    /** Algorithm 2 (base case): Dijkstra until k+1 vertices finalize; the (k+1)-th becomes
     *  the new bound and is left for the parent level. */
    private fun baseCase(b: UDist, x: Int): Pair<UDist, ArrayList<Int>> {
        val complete = ArrayList<Int>(k + 1)
        val heap = java.util.PriorityQueue<UDist>()
        heap.add(getDist(x))
        while (heap.isNotEmpty() && complete.size < k + 1) {
            val du = heap.poll()
            val u = du.vertex
            if (du > getDist(u)) continue
            complete.add(u)
            for (x in adjOff[u] until adjOff[u + 1]) {
                val v = adjTo[x]
                val w = adjW[x]
                val newDist = getDist(u, v, w)
                val oldDist = getDist(v)
                if (newDist <= oldDist && newDist < b) {
                    updateDist(u, v, w)
                    heap.add(newDist)
                }
            }
        }
        if (complete.size <= k) return b to complete

        val nb = getDist(complete[complete.size - 1])
        complete.removeAt(complete.size - 1)
        return nb to complete
    }

    /** Algorithm 3 (recursion): batch-extract below a trial bound, recurse a level down to
     *  finalize them, relax out of every finalized vertex, and batch-prepend everything that
     *  dropped below the level's floor. `quota` bounds one level's work so a level can exit
     *  partial instead of grinding to exhaustion. */
    private fun bmsspRec(lvl: Int, b: UDist, s: IntArray): Pair<UDist, ArrayList<Int>> {
        if (lvl == 0) return baseCase(b, s[0])

        val (pivots, bellmanVis) = findPivots(b, s)

        val batchSize = if ((lvl - 1).toLong() * t >= 30) Int.MAX_VALUE.toLong() else 1L shl ((lvl - 1) * t)
        val queue = ds[lvl - 1]!!
        queue.initialize(batchSize.toInt(), b)
        for (p in pivots) queue.insert(getDist(p))

        var lastCompleteB = b
        for (p in pivots) if (getDist(p) < lastCompleteB) lastCompleteB = getDist(p)

        val complete = ArrayList<Int>()
        val quota = if (lvl.toLong() * t >= 62) Long.MAX_VALUE else k.toLong() * (1L shl (lvl * t))
        while (complete.size.toLong() < quota && queue.size() > 0) {
            val (tryingB, miniS) = queue.pull()
            val (completeB, nwComplete) = bmsspRec(lvl - 1, tryingB, miniS.toIntArray())

            complete.addAll(nwComplete)

            val canPrepend = ArrayList<UDist>(nwComplete.size * 5 + miniS.size)
            for (u in nwComplete) {
                queue.erase(u) // priority-queue fix: a finalized vertex must leave the queue
                lastCompleteLvl[u] = lvl
                for (x in adjOff[u] until adjOff[u + 1]) {
                    val v = adjTo[x]
                    val w = adjW[x]
                    val newDist = getDist(u, v, w)
                    if (newDist <= getDist(v)) {
                        updateDist(u, v, w)
                        if (tryingB <= newDist && newDist < b) {
                            queue.insert(newDist)
                        } else if (completeB <= newDist && newDist < tryingB) {
                            canPrepend.add(newDist)
                        }
                    }
                }
            }
            for (x in miniS) {
                if (completeB <= getDist(x)) canPrepend.add(getDist(x))
            }
            queue.batchPrepend(canPrepend)

            lastCompleteB = completeB
        }
        val retB = if (queue.size() == 0) b else lastCompleteB

        // Bellman-Ford-visited vertices that this level finalized on the side (not via a pull
        // recursion) join the complete set for the parent, if under the level's exit bound.
        for (x in bellmanVis) {
            if (lastCompleteLvl[x] != lvl && getDist(x) < retB) complete.add(x)
        }
        return retB to complete
    }

}
