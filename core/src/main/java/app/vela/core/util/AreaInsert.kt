package app.vela.core.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/**
 * Where a point tapped onto an area being drawn goes in its outline (issue #846). The outline
 * closes from its last point back to the first, so a point added at the end gets two new sides:
 * one from the last point and one back to the first. Tapped where those sides would cross the
 * outline, the area would fold over itself.
 *
 * The rule: the point goes where its two new sides cross the fewest sides of the outline. The
 * end (after the last point) wins every tie, so an outline traced in order, concave ones
 * included, keeps its order. Among the other sides, the one the point adds the least perimeter
 * to wins. A side the new sides only touch, or run along, counts as crossed.
 *
 * The math runs on Web Mercator meters. The map draws each side as a straight line on that plane,
 * so the answer matches the screen at any latitude.
 */
object AreaInsert {
    private const val R = 6_378_137.0
    private const val MAX_LAT = 85.05112878
    /** Below this sine of the angle between two vectors they are taken as one line. */
    private const val COLLINEAR_SIN = 1e-9

    /**
     * The index in the point list (lat, lng, lat, lng...) that a new point at [lat], [lng] takes:
     * the point count to add it at the end, k to put it between points k - 1 and k. Fewer than
     * three points always add at the end.
     */
    fun indexFor(pts: List<Double>, lat: Double, lng: Double): Int {
        val n = pts.size / 2
        if (n < 3) return n
        val qx = mx(lng); val qy = my(lat)
        // Relative to the new point: smaller numbers, steadier products.
        val xs = DoubleArray(n) { mx(pts[it * 2 + 1]) - qx }
        val ys = DoubleArray(n) { my(pts[it * 2]) - qy }
        return indexFor(xs, ys, 0.0, 0.0)
    }

    /** [indexFor] on plane coordinates: the outline is ([xs], [ys]), the new point ([qx], [qy]). */
    internal fun indexFor(xs: DoubleArray, ys: DoubleArray, qx: Double, qy: Double): Int {
        val n = xs.size
        if (n < 3) return n
        val ring = Ring(xs, ys, qx, qy)
        var bestCross = ring.crossings(n - 1, Int.MAX_VALUE)
        if (bestCross == 0) return n
        var best = n
        // Least added perimeter first, so the first side to cross fewer wins its tie with any later one.
        val added = DoubleArray(n - 1) { ring.added(it) }
        val sides = (0 until n - 1).sortedBy { added[it] }
        for (k in sides) {
            val c = ring.crossings(k, bestCross)
            if (c < bestCross) {
                best = k + 1; bestCross = c
                if (c == 0) break
            }
        }
        return best
    }

    private fun mx(lng: Double) = Math.toRadians(lng) * R
    private fun my(lat: Double): Double {
        val p = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return R * ln(tan(PI / 4 + p / 2))
    }

    /** An outline and a new point Q. Side k runs from point k to point k + 1 (mod n). */
    private class Ring(val xs: DoubleArray, val ys: DoubleArray, val qx: Double, val qy: Double) {
        val n = xs.size

        fun added(k: Int): Double {
            val j = (k + 1) % n
            return hypot(xs[k] - qx, ys[k] - qy) + hypot(xs[j] - qx, ys[j] - qy) - hypot(xs[j] - xs[k], ys[j] - ys[k])
        }

        /**
         * How many sides the two new sides (point k to Q, Q to point k + 1) cross when Q goes into
         * side k; counting stops at [limit].
         */
        fun crossings(k: Int, limit: Int): Int {
            val a = k; val b = (k + 1) % n
            var c = 0
            // The two new sides fold onto each other when Q is on the side's line but off the side.
            if (foldsBack(qx, qy, xs[a], ys[a], xs[b], ys[b])) c++
            for (i in 0 until n) {
                if (c >= limit) return c
                if (i == k) continue // the side Q splits
                val i2 = (i + 1) % n
                // Point a to Q. The side ending at a shares that end and crosses only by folding back.
                c += if (i2 == a) (if (foldsBack(xs[a], ys[a], qx, qy, xs[i], ys[i])) 1 else 0)
                else (if (touches(xs[a], ys[a], qx, qy, xs[i], ys[i], xs[i2], ys[i2])) 1 else 0)
                // Q to point b. The side starting at b shares that end.
                c += if (i == b) (if (foldsBack(xs[b], ys[b], qx, qy, xs[i2], ys[i2])) 1 else 0)
                else (if (touches(qx, qy, xs[b], ys[b], xs[i], ys[i], xs[i2], ys[i2])) 1 else 0)
            }
            return c
        }
    }

    /** Sign of the turn o, p, r: 1 left, -1 right, 0 on one line. */
    private fun orient(ox: Double, oy: Double, px: Double, py: Double, rx: Double, ry: Double): Int {
        val ux = px - ox; val uy = py - oy; val vx = rx - ox; val vy = ry - oy
        val cross = ux * vy - uy * vx
        if (abs(cross) <= COLLINEAR_SIN * hypot(ux, uy) * hypot(vx, vy)) return 0
        return if (cross > 0) 1 else -1
    }

    /** Two sides from the shared point o, to p and to r, lie along each other. */
    private fun foldsBack(ox: Double, oy: Double, px: Double, py: Double, rx: Double, ry: Double): Boolean =
        orient(ox, oy, px, py, rx, ry) == 0 && (px - ox) * (rx - ox) + (py - oy) * (ry - oy) > 0

    /** Point p on segment s-e, given the three are on one line. */
    private fun within(sx: Double, sy: Double, ex: Double, ey: Double, px: Double, py: Double): Boolean =
        (px - sx) * (px - ex) + (py - sy) * (py - ey) <= 0

    /** Segments a-b and c-d (no end shared by index) cross, touch or overlap. */
    private fun touches(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        val d1 = orient(cx, cy, dx, dy, ax, ay)
        val d2 = orient(cx, cy, dx, dy, bx, by)
        val d3 = orient(ax, ay, bx, by, cx, cy)
        val d4 = orient(ax, ay, bx, by, dx, dy)
        if (d1 * d2 < 0 && d3 * d4 < 0) return true
        return (d1 == 0 && within(cx, cy, dx, dy, ax, ay)) ||
            (d2 == 0 && within(cx, cy, dx, dy, bx, by)) ||
            (d3 == 0 && within(ax, ay, bx, by, cx, cy)) ||
            (d4 == 0 && within(ax, ay, bx, by, dx, dy))
    }
}
