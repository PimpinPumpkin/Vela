package app.vela.core.routing

import java.util.TreeMap

/**
 * The batched priority queue from Duan et al. 2025 (Sec. 2.2), ported from the MIT-licensed
 * reference implementation `bmssp-expected.hpp` (github.com/lcs147/bmssp). This is the data
 * structure that breaks the Omega(m log n) comparison-sorting lower bound: instead of popping
 * the min one extraction at a time, `pull` returns EVERY element below a threshold in one
 * batch, which lets the recursion spend O(1) comparisons per batch instead of per element.
 *
 * Internals (mirroring the reference):
 *  - D1: doubly-linked blocks of entries, each <= M entries, ordered by upper-bound `UB`.
 *    `insert` appends to the first block whose UB >= the key; a block that outgrows M is
 *    median-split. D1 holds the "normal" inserts.
 *  - D0: front blocks pushed by `batchPrepend` - a batch known to be smaller than everything
 *    already queued, so it lands in O(|batch| log(|batch|/M)) instead of O(|batch| log n).
 *  - `pull`: if at most M elements sit in the head blocks, return them all with the sentinel
 *    bound; else quickselect the (M+1)-th smallest as the new bound and delete everything
 *    strictly below it.
 *  - Entries live in intrusive doubly-linked lists so `erase(key)` is O(1).
 *
 * Memory shape (this port, sized for a phone heap): the queue stores no objects per entry.
 * Every entry lives in flat arrays indexed by transformed node id (0 until [total]): the key
 * unpacked into parallel primitive lanes, the intrusive links stored as node indices, the
 * blocks in an int pool. Queued-ness and side ride on one generation stamp (+gen = D1,
 * -gen = D0), so `initialize` resets in O(1) by advancing the generation instead of
 * clearing. The reference stores its per-vertex state in hash tables; boxing the node ids in
 * HashMaps made a whole-state search OOM a 512 MB heap on the Delaware probe (~2.2M nodes),
 * while these arrays cost a fixed 32 B per node per level queue and allocate nothing during
 * the search. Level queues allocate their arrays on first use, so unused levels cost 0.
 *
 * Documented deviations from the C++ reference (semantics preserved):
 *  - Block identity: the reference tie-breaks equal UBs by std::list node address; we use a
 *    per-generation increasing block id. Both form a total order over blocks.
 *  - Selection: the reference uses Floyd-Rivest; we use quickselect. Both are the
 *    expected-linear selection the algorithm's analysis permits.
 *  - The reference preallocates per-vertex hash arrays; we use flat primitive arrays with a
 *    generation stamp. Same lookup shape (O(1) by node id), no boxing.
 *
 * Not thread-safe; the router builds one queue per recursion level per call.
 */
internal class BatchPQ(total: Int) {
    // Per-node entry state. A node is queued iff stamp[v] == +gen (D1) or -gen (D0).
    // keyDist/keyEdges/keyPred hold the queued UDist unpacked (its `vertex` field is v);
    // linkPrev/linkNext are the intrusive list links (node ids, -1 == null); blockOf is the
    // block pool slot. All lanes are only read for stamped nodes, so nothing needs clearing
    // between initializations.
    private var stamp = IntArray(0)
    private var keyDist = LongArray(0)
    private var keyEdges = IntArray(0)
    private var keyPred = IntArray(0)
    private var linkPrev = IntArray(0)
    private var linkNext = IntArray(0)
    private var blockOf = IntArray(0)
    private var gen = 0
    private val total = total

    // Block pool: few blocks are live at once (one per batchPrepend leaf, one per split).
    // The slot index doubles as the block id (increasing with creation within a generation).
    private var blkPrev = IntArray(16)
    private var blkNext = IntArray(16)
    private var blkHead = IntArray(16)
    private var blkTail = IntArray(16)
    private var blkSize = IntArray(16)
    private var blkUsed = 0

    private var m = 0
    private var sentinel = UDist(OO, 0, 0, 0)
    private var size = 0

    private var d0Head = -1
    private var d0Tail = -1
    private var d1Head = -1
    private var d1Tail = -1

    /** (UB key, block id) composite: ordered like the reference's `UBs` std::set pair. */
    private class UbKey(val d: UDist, val id: Long) : Comparable<UbKey> {
        override fun compareTo(other: UbKey): Int {
            val c = d.compareTo(other.d)
            return if (c != 0) c else id.compareTo(other.id)
        }
    }

    private val ubs = TreeMap<UbKey, Int>()

    /** Reset to an empty queue at recursion level entry: batch capacity M, sentinel bound B. */
    fun initialize(m: Int, b: UDist) {
        require(m >= 1) { "batch capacity must be >= 1, got $m" }
        this.m = m
        this.sentinel = b
        size = 0
        if (stamp.size < total) {
            stamp = IntArray(total)
            keyDist = LongArray(total)
            keyEdges = IntArray(total)
            keyPred = IntArray(total)
            linkPrev = IntArray(total)
            linkNext = IntArray(total)
            blockOf = IntArray(total)
        }
        gen++
        if (gen == Int.MAX_VALUE) {
            stamp.fill(0)
            gen = 1
        }
        d0Head = -1; d0Tail = -1
        blkUsed = 0
        val seed = newBlock()
        d1Head = seed; d1Tail = seed
        ubs.clear(); ubs[UbKey(sentinel, seed.toLong())] = seed
    }

    fun size(): Int = size

    /** Ordinary insert into the D1 side (O(log #blocks) ordered-map search + O(1) splice). */
    fun insert(x: UDist) {
        val v = x.vertex
        if (queued(v)) {
            if (cmp(v, x) > 0) delete(v) else return
        }
        val ubEntry = ubs.ceilingEntry(UbKey(x, Long.MIN_VALUE))
            ?: error("UB index lost its sentinel block")
        val block = ubEntry.value
        storeKey(v, x)
        appendToBlock(block, v)
        stamp[v] = gen
        size++
        if (blkSize[block] > m) split(block)
    }

    /**
     * The batch operation the whole algorithm is built on: every key in [keys] is known to be
     * below the current queue contents, so it is prepended as whole blocks (no per-element
     * log-n sifts). Duplicates are dropped; a key that improves an existing entry replaces it.
     */
    fun batchPrepend(keys: List<UDist>) = batchPrependRec(keys)

    /**
     * Extract one batch: returns (bound, vertices-below-bound), deleting those vertices.
     * bound == the initialization sentinel when the whole queue came out (queue drained).
     */
    fun pull(): Pair<UDist, MutableList<Int>> {
        val s0 = ArrayList<Int>(2 * m + 4)
        var block = d0Head
        while (block != -1 && s0.size <= m) { collect(block, s0); block = blkNext[block] }
        val s1 = ArrayList<Int>(m + 4)
        block = d1Head
        while (block != -1 && s1.size <= m) { collect(block, s1); block = blkNext[block] }

        if (s1.size + s0.size <= m) {
            val ret = ArrayList<Int>(s0.size + s1.size)
            for (v in s0) { ret.add(v); delete(v) }
            for (v in s1) { ret.add(v); delete(v) }
            return sentinel to ret
        }
        val all = ArrayList<UDist>(s0.size + s1.size)
        for (v in s0) all.add(keyOf(v))
        for (v in s1) all.add(keyOf(v))
        val med = selectKth(all, m)
        val ret = ArrayList<Int>(m)
        for (v in s0) if (cmp(v, med) < 0) { ret.add(v); delete(v) }
        for (v in s1) if (cmp(v, med) < 0) { ret.add(v); delete(v) }
        return med to ret
    }

    /** Drop [key] from the queue if present (the "priority-queue fix" after a vertex completes). */
    fun erase(key: Int) {
        if (queued(key)) delete(key)
    }

    // ------------------------------------------------------------------------

    private fun queued(v: Int): Boolean {
        val s = stamp[v]
        return s == gen || s == -gen
    }

    private fun keyOf(v: Int) = UDist(keyDist[v], keyEdges[v], v, keyPred[v])

    private fun storeKey(v: Int, x: UDist) {
        keyDist[v] = x.dist; keyEdges[v] = x.pathEdges; keyPred[v] = x.pred
    }

    /** Compare node [v]'s stored key with [x]; identical total order to UDist.compareTo. */
    private fun cmp(v: Int, x: UDist): Int {
        if (keyDist[v] != x.dist) return if (keyDist[v] < x.dist) -1 else 1
        if (keyEdges[v] != x.pathEdges) return keyEdges[v] - x.pathEdges
        if (v != x.vertex) return v - x.vertex
        return keyPred[v] - x.pred
    }

    private fun newBlock(): Int {
        if (blkUsed == blkPrev.size) {
            val grown = blkPrev.size * 2
            blkPrev = blkPrev.copyOf(grown); blkNext = blkNext.copyOf(grown)
            blkHead = blkHead.copyOf(grown); blkTail = blkTail.copyOf(grown)
            blkSize = blkSize.copyOf(grown)
        }
        val b = blkUsed++
        blkPrev[b] = -1; blkNext[b] = -1; blkHead[b] = -1; blkTail[b] = -1; blkSize[b] = 0
        return b
    }

    private fun collect(block: Int, into: ArrayList<Int>) {
        var v = blkHead[block]
        while (v != -1) { into.add(v); v = linkNext[v] }
    }

    private fun appendToBlock(block: Int, v: Int) {
        blockOf[v] = block
        // Lanes are never cleared between generations: splice from a clean slate so a node
        // re-queued in a new generation cannot inherit a stale link from the old one.
        linkPrev[v] = -1; linkNext[v] = -1
        val tail = blkTail[block]
        if (tail == -1) { blkHead[block] = v; blkTail[block] = v } else { linkNext[tail] = v; linkPrev[v] = tail; blkTail[block] = v }
        blkSize[block]++
    }

    private fun unlinkEntry(v: Int) {
        val b = blockOf[v]
        val p = linkPrev[v]; val n = linkNext[v]
        if (p != -1) linkNext[p] = n else blkHead[b] = n
        if (n != -1) linkPrev[n] = p else blkTail[b] = p
        linkPrev[v] = -1; linkNext[v] = -1
        blkSize[b]--
    }

    private fun removeBlock0(b: Int) {
        val p = blkPrev[b]; val n = blkNext[b]
        if (p != -1) blkNext[p] = n else d0Head = n
        if (n != -1) blkPrev[n] = p else d0Tail = p
        blkPrev[b] = -1; blkNext[b] = -1
    }

    private fun removeBlock1(b: Int) {
        val p = blkPrev[b]; val n = blkNext[b]
        if (p != -1) blkNext[p] = n else d1Head = n
        if (n != -1) blkPrev[n] = p else d1Tail = p
        blkPrev[b] = -1; blkNext[b] = -1
    }

    /** Remove vertex [a]'s queued entry (reference `delete_`). */
    private fun delete(a: Int) {
        if (!queued(a)) return
        val st = stamp[a]
        stamp[a] = 0
        val block = blockOf[a]
        unlinkEntry(a)
        if (st == gen) {
            if (blkSize[block] == 0) {
                val ubEntry = ubs.ceilingEntry(UbKey(keyOf(a), Long.MIN_VALUE))
                    ?: error("UB index lost its sentinel block")
                if (ubEntry.key.d != sentinel) {
                    ubs.remove(ubEntry.key)
                    removeBlock1(block)
                }
                // UB == sentinel: the block is the tail sentinel block; keep it (reference parity).
            }
        } else {
            if (blkSize[block] == 0) removeBlock0(block)
        }
        size--
    }

    /** Median-split an over-capacity D1 block into two, fixing the UB index (reference `split`). */
    private fun split(block: Int) {
        val sz = blkSize[block]
        val keys = ArrayList<UDist>(sz)
        collectKeys(block, keys)
        val med = selectKth(keys, sz / 2)

        val fresh = newBlock()
        val after = blkNext[block]
        blkPrev[fresh] = block; blkNext[fresh] = after
        blkNext[block] = fresh
        if (after != -1) blkPrev[after] = fresh else d1Tail = fresh

        var v = blkHead[block]
        while (v != -1) {
            val nxt = linkNext[v]
            if (cmp(v, med) >= 0) {
                unlinkEntry(v)
                appendToBlock(fresh, v)
            }
            v = nxt
        }

        // The old UB entry for `block` sits at or just above (med with pred-1); move it to the
        // fresh block's range and give both halves their own bound (reference's UB1/UB2 dance).
        val ub1 = UDist(med.dist, med.pathEdges, med.vertex, med.pred - 1)
        val lb = ubs.ceilingEntry(UbKey(ub1, Long.MIN_VALUE)) ?: error("UB index lost its sentinel block")
        val ub2 = lb.key.d
        ubs.remove(lb.key)
        ubs[UbKey(ub1, block.toLong())] = block
        ubs[UbKey(ub2, fresh.toLong())] = fresh
    }

    private fun collectKeys(block: Int, into: ArrayList<UDist>) {
        var v = blkHead[block]
        while (v != -1) { into.add(keyOf(v)); v = linkNext[v] }
    }

    /** Recursive half-splitting prepend: O(|l| log(|l|/M)) block pushes, reference-faithful. */
    private fun batchPrependRec(l: List<UDist>) {
        val sz = l.size
        if (sz == 0) return
        if (sz <= m) {
            val fresh = newBlock()
            val old = d0Head
            blkNext[fresh] = old
            if (old != -1) blkPrev[old] = fresh else d0Tail = fresh
            d0Head = fresh
            for (x in l) {
                val v = x.vertex
                if (queued(v)) {
                    if (cmp(v, x) > 0) delete(v) else continue
                }
                storeKey(v, x)
                appendToBlock(fresh, v)
                stamp[v] = -gen
                size++
            }
            if (blkSize[fresh] == 0) removeBlock0(fresh)
            return
        }
        val med = selectKth(ArrayList(l), sz / 2)
        val less = ArrayList<UDist>(sz / 2)
        val great = ArrayList<UDist>(sz / 2 + 1)
        for (x in l) {
            if (x < med) less.add(x) else if (x > med) great.add(x)
        }
        great.add(med) // reference: the median element itself goes with the greater half
        batchPrependRec(great)
        batchPrependRec(less)
    }
}

/**
 * Expected-linear kth-smallest selection (quickselect; the reference uses Floyd-Rivest -
 * both are the expected O(n) selection the algorithm's analysis permits). Hoares partition
 * with median-of-three pivot.
 */
private fun selectKth(values: ArrayList<UDist>, k: Int): UDist {
    var lo = 0
    var hi = values.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        // median-of-three pivot to the far right
        if (values[mid] < values[lo]) swap(values, mid, lo)
        if (values[hi] < values[mid]) swap(values, mid, hi)
        if (values[mid] < values[lo]) swap(values, mid, lo)
        val pivot = values[mid]
        swap(values, mid, hi)
        var store = lo
        for (i in lo until hi) if (values[i] < pivot) { swap(values, store, i); store++ }
        swap(values, store, hi)
        when {
            store == k -> return values[store]
            store < k -> lo = store + 1
            else -> hi = store - 1
        }
    }
    return values[k]
}

private fun swap(values: ArrayList<UDist>, i: Int, j: Int) {
    val t = values[i]; values[i] = values[j]; values[j] = t
}
