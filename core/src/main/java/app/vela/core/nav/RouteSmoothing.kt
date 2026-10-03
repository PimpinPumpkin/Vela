package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.bearingTo
import app.vela.core.model.distanceTo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Straightens the short sideways jogs in a drawn route (user 2026-10-03). Where OpenStreetMap splits
 * a road for a few dozen meters around a median or an island, a route follows one side and its line
 * steps a few meters over and back, so the arrow swerves around something the car drives straight
 * past. Google and Apple draw those stretches straight.
 *
 * A stretch from vertex i to vertex j is replaced by the straight chord between them when all of
 * these hold: it is at most [MAX_SPAN_M] long; every vertex between stays within [MAX_JOG_M] of the
 * chord and projects inside it (no doubling back); at least one strays [MIN_JOG_M] or more (else
 * there is nothing to fix); and the road runs straight into i and straight out of j along the chord
 * ([HEADING_TOL_DEG] over [HEADING_PROBE_M] each side), on the same heading in and out
 * ([PARALLEL_DEG]), and back on the line it left ([REJOIN_M]). Those last rules keep real turns,
 * forks, curves and a road that moves over for good: none of them leaves and rejoins one straight
 * line. Endpoints and
 * every vertex outside a replaced stretch are kept exactly. For drawing and for the arrow that rides
 * the line, not for guidance (the engine keeps the router's line).
 */
object RouteSmoothing {
    const val MAX_SPAN_M = 140.0
    const val MAX_JOG_M = 12.0
    const val MIN_JOG_M = 1.0
    const val HEADING_PROBE_M = 20.0
    const val HEADING_TOL_DEG = 7.0
    const val REJOIN_M = 2.0
    const val PARALLEL_DEG = 3.0

    /** Zigzag pass: a vertex is dropped when both its segments are short ([ZIG_SEG_M]), the line
     *  turns there by [ZIG_TURN_DEG] or more and turns back the OTHER way at a neighbor, and it sits
     *  within [ZIG_DEV_M] of the line joining its neighbors. A wobble like that is digitizing, not
     *  road; a bend that keeps turning one way (a curve, a roundabout, a corner) is never touched. */
    const val ZIG_SEG_M = 35.0
    const val ZIG_TURN_DEG = 15.0
    const val ZIG_DEV_M = 4.0

    fun straightenJogs(poly: List<LatLng>): List<LatLng> = removeZigzags(straightenMedianJogs(poly))

    /** Gentle bends only: a vertex turning less than this is rounded off, a sharper one (a real
     *  corner at a junction) is kept exactly. */
    const val ROUND_MAX_TURN_DEG = 45.0
    /** How far along each side a rounded vertex is cut, at most (and never more than a quarter of
     *  the shorter side). */
    const val ROUND_MAX_CUT_M = 8.0

    /**
     * Rounds the drawn line's gentle bends (2026-10-03, "too jagged"): OpenStreetMap draws a curve as
     * straight pieces 30 to 50 m long, which a road-width stripe shows as a row of corners. Two
     * passes of corner cutting (Chaikin's, bounded): each vertex under [ROUND_MAX_TURN_DEG] becomes
     * two points [ROUND_MAX_CUT_M] (or a quarter of the shorter side) along its two sides. A 15
     * degree bend between 40 m sides moves the line about a meter. Ends and sharp corners stay.
     */
    fun roundBends(poly: List<LatLng>, passes: Int = 2): List<LatLng> {
        var cur = poly
        repeat(passes) {
            if (cur.size < 3) return cur
            val out = ArrayList<LatLng>(cur.size * 2)
            out += cur[0]
            for (k in 1 until cur.size - 1) {
                val a = cur[k - 1]; val v = cur[k]; val b = cur[k + 1]
                val la = a.distanceTo(v); val lb = v.distanceTo(b)
                if (la < 0.5 || lb < 0.5 || abs(turn(a, v, b)) >= ROUND_MAX_TURN_DEG) { out += v; continue }
                val cut = minOf(ROUND_MAX_CUT_M, 0.25 * minOf(la, lb))
                out += lerp(v, a, cut / la)
                out += lerp(v, b, cut / lb)
            }
            out += cur.last()
            cur = out
        }
        return cur
    }

    private fun lerp(from: LatLng, to: LatLng, f: Double) =
        LatLng(from.lat + (to.lat - from.lat) * f, from.lng + (to.lng - from.lng) * f)

    fun removeZigzags(poly: List<LatLng>): List<LatLng> {
        if (poly.size < 4) return poly
        var cur = poly
        repeat(4) {
            val keep = BooleanArray(cur.size) { true }
            var dropped = false
            var k = 1
            while (k < cur.size - 1) {
                val prev = cur[k - 1]; val here = cur[k]; val next = cur[k + 1]
                val a = prev.distanceTo(here); val b = here.distanceTo(next)
                if (a <= ZIG_SEG_M && b <= ZIG_SEG_M && a > 0.0 && b > 0.0) {
                    val t = turn(prev, here, next)
                    val tPrev = if (k >= 2) turn(cur[k - 2], prev, here) else 0.0
                    val tNext = if (k + 2 < cur.size) turn(here, next, cur[k + 2]) else 0.0
                    val back = (tPrev * t < 0 && abs(tPrev) >= ZIG_TURN_DEG) || (tNext * t < 0 && abs(tNext) >= ZIG_TURN_DEG)
                    if (abs(t) >= ZIG_TURN_DEG && back && offset(here, prev, next) <= ZIG_DEV_M) {
                        keep[k] = false; dropped = true
                        k += 2 // never drop two neighbors in one pass
                        continue
                    }
                }
                k++
            }
            if (!dropped) return cur
            cur = cur.filterIndexed { i, _ -> keep[i] }
        }
        return cur
    }

    /** Signed turn at b going a -> b -> c, degrees (positive = right). */
    private fun turn(a: LatLng, b: LatLng, c: LatLng): Double =
        ((b.bearingTo(c) - a.bearingTo(b) + 540.0) % 360.0) - 180.0

    private fun offset(p: LatLng, a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * cos(Math.toRadians(a.lat)); val ky = 110_540.0
        val bx = (b.lng - a.lng) * kx; val by = (b.lat - a.lat) * ky
        val x = (p.lng - a.lng) * kx; val y = (p.lat - a.lat) * ky
        val l = sqrt(bx * bx + by * by)
        return if (l < 0.01) sqrt(x * x + y * y) else abs(x * by - y * bx) / l
    }

    fun straightenMedianJogs(poly: List<LatLng>): List<LatLng> {
        if (poly.size < 4) return poly
        val n = poly.size
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + poly[k - 1].distanceTo(poly[k])
        val out = ArrayList<LatLng>(n)
        out += poly[0]
        var i = 0
        while (i < n - 1) {
            var best = -1
            var j = i + 2
            while (j < n && cum[j] - cum[i] <= MAX_SPAN_M) {
                if (isJog(poly, cum, i, j)) best = j
                j++
            }
            if (best > 0) { out += poly[best]; i = best } else { out += poly[i + 1]; i++ }
        }
        return out
    }

    private fun isJog(p: List<LatLng>, cum: DoubleArray, i: Int, j: Int): Boolean {
        if (cum[i] < HEADING_PROBE_M || cum[cum.size - 1] - cum[j] < HEADING_PROBE_M) return false
        val a = p[i]; val b = p[j]
        // Local flat frame around a (meters): fine over a 140 m stretch.
        val kx = 111_320.0 * cos(Math.toRadians(a.lat)); val ky = 110_540.0
        val bx = (b.lng - a.lng) * kx; val by = (b.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        if (len2 < 1.0) return false
        var maxDev = 0.0
        for (k in i + 1 until j) {
            val x = (p[k].lng - a.lng) * kx; val y = (p[k].lat - a.lat) * ky
            val t = (x * bx + y * by) / len2
            if (t <= 0.0 || t >= 1.0) return false
            val dev = abs(x * by - y * bx) / sqrt(len2)
            if (dev > MAX_JOG_M) return false
            if (dev > maxDev) maxDev = dev
        }
        if (maxDev < MIN_JOG_M) return false
        val chord = a.bearingTo(b)
        val back = pointAt(p, cum, cum[i] - HEADING_PROBE_M)
        val inBrg = back.bearingTo(a)
        val outBrg = b.bearingTo(pointAt(p, cum, cum[j] + HEADING_PROBE_M))
        if (angle(inBrg, chord) > HEADING_TOL_DEG || angle(outBrg, chord) > HEADING_TOL_DEG) return false
        // In and out on the same heading: a curve turns between them, a jog does not.
        if (angle(inBrg, outBrg) > PARALLEL_DEG) return false
        // The stretch must come back to the line it left: j within REJOIN_M of the incoming line's
        // extension. A road that moves over and stays over (a divided road beginning) is not a jog.
        val ix = (a.lng - back.lng) * kx; val iy = (a.lat - back.lat) * ky
        val il = sqrt(ix * ix + iy * iy).takeIf { it > 0.5 } ?: return false
        return abs(bx * iy - by * ix) / il <= REJOIN_M
    }

    private fun angle(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun pointAt(p: List<LatLng>, cum: DoubleArray, m: Double): LatLng {
        if (m <= 0.0) return p[0]
        if (m >= cum[cum.size - 1]) return p[p.size - 1]
        var k = 1
        while (cum[k] < m) k++
        val seg = cum[k] - cum[k - 1]
        if (seg <= 0.0) return p[k]
        val t = (m - cum[k - 1]) / seg
        return LatLng(p[k - 1].lat + (p[k].lat - p[k - 1].lat) * t, p[k - 1].lng + (p[k].lng - p[k - 1].lng) * t)
    }
}
