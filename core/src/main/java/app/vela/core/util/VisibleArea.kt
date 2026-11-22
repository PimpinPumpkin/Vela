package app.vela.core.util

/**
 * The part of the map that panels leave uncovered, as one rectangle. Each panel is cut away from
 * one side: a sheet across the bottom keeps the strip above it, a landscape side panel keeps the
 * part beside it, a card across the top keeps the part below it. Every choice of side for every
 * panel is tried and the largest rectangle left wins, so a card stacked on a side panel keeps the
 * full-height column beside both. A panel that leaves nothing on any side (a full-screen page)
 * leaves nothing visible.
 *
 * Rectangles are `[left, top, right, bottom]` in one coordinate space, pixels in practice.
 */
object VisibleArea {
    /** Panels past this many are cut one at a time, each from its best side; the full search is 4^n. */
    private const val SEARCH_MAX = 6

    /** The uncovered part of [map], or null when nothing of it is visible. */
    fun of(map: FloatArray, covers: List<FloatArray>): FloatArray? {
        if (size(map) <= 0f) return null
        val over = covers.filter { size(overlap(map, it)) > 0f }
        val best = if (over.size <= SEARCH_MAX) search(map.copyOf(), over, 0) else greedy(map.copyOf(), over)
        return best?.takeIf { size(it) > 0f }
    }

    /** The center of [of], or null when nothing is visible. */
    fun center(map: FloatArray, covers: List<FloatArray>): Pair<Float, Float>? =
        of(map, covers)?.let { (it[0] + it[2]) / 2f to (it[1] + it[3]) / 2f }

    private fun search(area: FloatArray, covers: List<FloatArray>, i: Int): FloatArray? {
        if (i == covers.size) return area
        val c = covers[i]
        if (size(overlap(area, c)) <= 0f) return search(area, covers, i + 1)
        var best: FloatArray? = null
        for (side in sides(area, c)) {
            if (size(side) <= (best?.let { size(it) } ?: 0f)) continue
            val r = search(side, covers, i + 1) ?: continue
            if (size(r) > (best?.let { size(it) } ?: 0f)) best = r
        }
        return best
    }

    private fun greedy(start: FloatArray, covers: List<FloatArray>): FloatArray? {
        var area = start
        for (c in covers.sortedByDescending { size(overlap(start, it)) }) {
            if (size(overlap(area, c)) <= 0f) continue
            area = sides(area, c).maxBy { size(it) }
            if (size(area) <= 0f) return null
        }
        return area
    }

    /** What is left of [area] above, below, left of and right of [c]. */
    private fun sides(area: FloatArray, c: FloatArray) = listOf(
        floatArrayOf(area[0], area[1], area[2], minOf(area[3], c[1])),
        floatArrayOf(area[0], maxOf(area[1], c[3]), area[2], area[3]),
        floatArrayOf(area[0], area[1], minOf(area[2], c[0]), area[3]),
        floatArrayOf(maxOf(area[0], c[2]), area[1], area[2], area[3]),
    )

    private fun overlap(a: FloatArray, b: FloatArray) = floatArrayOf(
        maxOf(a[0], b[0]), maxOf(a[1], b[1]), minOf(a[2], b[2]), minOf(a[3], b[3]),
    )

    private fun size(r: FloatArray): Float {
        val w = r[2] - r[0]
        val h = r[3] - r[1]
        return if (w <= 0f || h <= 0f) 0f else w * h
    }
}
