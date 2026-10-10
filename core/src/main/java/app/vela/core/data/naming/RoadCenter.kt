package app.vela.core.data.naming

import app.vela.core.model.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Google's line nudged onto the middle of the map's own roads, for DRAWING only. Google draws
 * its line in the driving lane, a few meters to one side of the road's center, and the roads
 * under it are drawn from OpenStreetMap, so on a road-width stripe the route hangs off the
 * street. Where a stretch has no matched road shape to draw instead ([HybridRoute.drawLine]),
 * each point of the line moves sideways onto the nearest road that runs its way, when one is
 * within [MAX_OFF_M]. A point with no such road stays where Google put it: past the threshold
 * the map and the line disagree about more than a lane, and Google's line is the one that knows
 * where the route goes. Guidance never reads the result.
 */
object RoadCenter {
    /** The line is read every this many meters. */
    const val STEP_M = 6.0
    /** A point farther than this from every road running its way is not moved. Half the width
     *  of a wide street; a frontage road or a parking aisle beside the street is farther. */
    const val MAX_OFF_M = 9.0
    /** A road within this of the line's own heading runs its way (either direction). */
    const val ALIGN_DEG = 25.0
    /** Roads on both sides of the line, this close to equally near: neither is chosen. */
    const val AMBIGUOUS_M = 3.0
    /** A run of unmoved points shorter than this (a junction, where the line is turning and no
     *  road runs its way) is carried across from the moved points either side. */
    const val BRIDGE_M = 40.0
    /** The sideways moves are averaged over this far either way, so the line eases onto a road
     *  and off it and one bad point cannot kink it. */
    const val SMOOTH_M = 18.0
    private const val HEADING_SPAN_M = 12.0
    private const val CELL_M = 40.0

    /** [line] nudged onto [roads]; [line] itself when nothing moved. */
    fun nudge(line: List<LatLng>, roads: List<RoadNameTiles.RoadLine>): List<LatLng> = nudgeCounted(line, roads).first

    /** [nudge], with the meters of the line it moved by more than half a meter (for the trip note). */
    fun nudgeCounted(line: List<LatLng>, roads: List<RoadNameTiles.RoadLine>): Pair<List<LatLng>, Int> {
        val none = line to 0
        if (line.size < 2 || roads.isEmpty()) return none
        val lat0 = line.first().lat
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 111_320.0
        fun x(p: LatLng) = (p.lng - line.first().lng) * kx
        fun y(p: LatLng) = (p.lat - lat0) * ky

        // The roads' segments in meters, on a grid.
        class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double) { val brg = Math.toDegrees(atan2(bx - ax, by - ay)) }
        val grid = HashMap<Long, MutableList<Seg>>()
        fun key(gx: Int, gy: Int) = (gx.toLong() shl 32) xor (gy.toLong() and 0xffffffffL)
        // Each segment goes into the cells it runs through, sampled every half cell along it:
        // filling its bounding box put a long diagonal segment (a straight rural road) into
        // hundreds of cells, or none at all past a cap.
        for (r in roads) for (i in 0 until r.points.size - 1) {
            val s = Seg(x(r.points[i]), y(r.points[i]), x(r.points[i + 1]), y(r.points[i + 1]))
            val steps = (hypot(s.bx - s.ax, s.by - s.ay) / (CELL_M / 2)).toInt().coerceAtLeast(1)
            var last = Long.MIN_VALUE
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                val cell = key(Math.floor((s.ax + (s.bx - s.ax) * t) / CELL_M).toInt(), Math.floor((s.ay + (s.by - s.ay) * t) / CELL_M).toInt())
                if (cell != last) { grid.getOrPut(cell) { ArrayList() } += s; last = cell }
            }
        }

        // The line, every STEP_M, in meters.
        val px = ArrayList<Double>(); val py = ArrayList<Double>(); val pm = ArrayList<Double>()
        run {
            var acc = 0.0; var next = 0.0
            for (i in 0 until line.size - 1) {
                val ax = x(line[i]); val ay = y(line[i]); val bx = x(line[i + 1]); val by = y(line[i + 1])
                val d = hypot(bx - ax, by - ay)
                if (d <= 0.0) continue
                while (next <= acc + d) {
                    val t = (next - acc) / d
                    px += ax + (bx - ax) * t; py += ay + (by - ay) * t; pm += next
                    next += STEP_M
                }
                acc += d
            }
            if (pm.isEmpty() || acc - pm.last() > 0.5) { px += x(line.last()); py += y(line.last()); pm += acc }
        }
        val n = pm.size
        if (n < 2) return none
        val span = (HEADING_SPAN_M / STEP_M).toInt().coerceAtLeast(1)

        // Each point's move onto the nearest road running its way, or none.
        val ox = DoubleArray(n); val oy = DoubleArray(n); val moved = BooleanArray(n)
        for (i in 0 until n) {
            val a = (i - span).coerceAtLeast(0); val b = (i + span).coerceAtMost(n - 1)
            val hx = px[b] - px[a]; val hy = py[b] - py[a]
            val hl = hypot(hx, hy)
            if (hl < 1.0) continue
            val brg = Math.toDegrees(atan2(hx, hy))
            var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0; var bestSide = 0.0
            var otherSide = Double.MAX_VALUE // the nearest road on the far side of the line from the best
            val gx = Math.floor(px[i] / CELL_M).toInt(); val gy = Math.floor(py[i] / CELL_M).toInt()
            for (dx in -1..1) for (dy in -1..1) for (s in grid[key(gx + dx, gy + dy)].orEmpty()) {
                var diff = abs(brg - s.brg) % 180.0
                if (diff > 90.0) diff = 180.0 - diff
                if (diff > ALIGN_DEG) continue
                val sx = s.bx - s.ax; val sy = s.by - s.ay
                val l2 = sx * sx + sy * sy
                val t = if (l2 == 0.0) 0.0 else (((px[i] - s.ax) * sx + (py[i] - s.ay) * sy) / l2).coerceIn(0.0, 1.0)
                val qx = s.ax + sx * t - px[i]; val qy = s.ay + sy * t - py[i]
                val d = hypot(qx, qy)
                if (d > MAX_OFF_M) continue
                val side = hx * qy - hy * qx // positive: the road is to the line's left
                if (d < best) {
                    if (best < Double.MAX_VALUE && bestSide * side < 0) otherSide = minOf(otherSide, best)
                    best = d; bx = qx; by = qy; bestSide = side
                } else if (bestSide * side < 0) otherSide = minOf(otherSide, d)
            }
            if (best == Double.MAX_VALUE) continue
            // On the road already (under a meter): nothing to choose between.
            if (best > 1.0 && otherSide - best < AMBIGUOUS_M) continue
            ox[i] = bx; oy[i] = by; moved[i] = true
        }
        if (moved.none { it }) return none

        // Short unmoved runs between moved points take the moves either side, blended.
        var i = 0
        while (i < n) {
            if (moved[i]) { i++; continue }
            var j = i
            while (j < n && !moved[j]) j++
            if (i > 0 && j < n && pm[j] - pm[i - 1] <= BRIDGE_M) {
                for (k in i until j) {
                    val t = (pm[k] - pm[i - 1]) / (pm[j] - pm[i - 1])
                    ox[k] = ox[i - 1] + (ox[j] - ox[i - 1]) * t; oy[k] = oy[i - 1] + (oy[j] - oy[i - 1]) * t
                }
            }
            i = j
        }

        // Ease on and off: each move becomes the average over SMOOTH_M either way.
        val w = (SMOOTH_M / STEP_M).toInt().coerceAtLeast(1)
        val out = ArrayList<LatLng>(n)
        var movedM = 0.0
        for (k in 0 until n) {
            var sx = 0.0; var sy = 0.0; var c = 0
            for (m in (k - w).coerceAtLeast(0)..(k + w).coerceAtMost(n - 1)) { sx += ox[m]; sy += oy[m]; c++ }
            if (hypot(sx / c, sy / c) > 0.5) movedM += STEP_M
            val nx = px[k] + sx / c; val ny = py[k] + sy / c
            out += LatLng(lat0 + ny / ky, line.first().lng + nx / kx)
        }
        return out to movedM.toInt()
    }
}
