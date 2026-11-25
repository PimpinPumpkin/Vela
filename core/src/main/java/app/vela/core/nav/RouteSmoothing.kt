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

    /**
     * For each vertex of [smoothed], how far along [original] it sits. Every rule here only drops
     * vertices, so a smoothed line's vertices are the original's own, in order. Null when one is
     * not found (a line that was not made from [original]).
     */
    fun alongOriginal(original: List<LatLng>, smoothed: List<LatLng>): DoubleArray? {
        if (original.isEmpty() || smoothed.isEmpty()) return null
        val out = DoubleArray(smoothed.size)
        var k = 0
        var run = 0.0
        for (j in smoothed.indices) {
            while (k < original.size && original[k] !== smoothed[j]) {
                if (k + 1 < original.size) run += original[k].distanceTo(original[k + 1])
                k++
            }
            if (k >= original.size) return null
            out[j] = run
        }
        return out
    }

    /**
     * The distance along the smoothed line that matches [m] along the original: [at] is
     * [alongOriginal] and [cum] the smoothed line's running length. A stretch that was straightened
     * is shorter than the stretch it replaced, so one ratio for the whole route puts everything
     * after it ahead of where it is.
     */
    fun mapAlong(at: DoubleArray, cum: DoubleArray, m: Double): Double {
        if (at.size != cum.size || at.size < 2) return m
        if (m <= at[0]) return cum[0]
        if (m >= at[at.size - 1]) return cum[cum.size - 1]
        val idx = java.util.Arrays.binarySearch(at, m)
        val j = (if (idx >= 0) idx else -idx - 1).coerceIn(1, at.size - 1)
        val span = at[j] - at[j - 1]
        val t = if (span <= 0.0) 0.0 else (m - at[j - 1]) / span
        return cum[j - 1] + (cum[j] - cum[j - 1]) * t
    }

    /** Gentle bends: a vertex turning less than this is cut by [roundBends]' passes; a sharper one
     *  is a corner, rounded by [roundCorners]. */
    const val ROUND_MAX_TURN_DEG = 45.0
    /** How far along each side a rounded vertex is cut, at most (and never more than a quarter of
     *  the shorter side). */
    const val ROUND_MAX_CUT_M = 8.0

    /** A corner turns at least [ROUND_MAX_TURN_DEG] between chords this long either side of it. */
    const val CORNER_LEG_M = 12.0
    /** Corner vertices this close together are one corner, rounded at the sharpest. */
    const val CORNER_MERGE_M = 15.0
    /** A corner's curve starts and ends this far along the line either side of it, at most. */
    const val CORNER_CUT_M = 10.0
    /** ...and no further than this share of the way to the next corner, or to the line's end. */
    const val CORNER_CUT_SHARE = 0.45
    /** A corner that leaves less than this either side to curve over keeps its point. */
    const val CORNER_MIN_CUT_M = 2.0
    /** A turn sharper than this (the line doubling back on itself) keeps its point. */
    const val CORNER_MAX_TURN_DEG = 160.0
    /** Points on a corner's curve, at most this far apart. */
    const val CORNER_STEP_M = 1.5

    /**
     * Rounds the drawn line's bends. Junction corners first ([roundCorners]), then the gentle bends
     * (2026-10-03, "too jagged"): OpenStreetMap draws a curve as straight pieces 30 to 50 m long,
     * which a road-width stripe shows as a row of corners. Two passes of corner cutting (Chaikin's,
     * bounded): each vertex under [ROUND_MAX_TURN_DEG] becomes two points [ROUND_MAX_CUT_M] (or a
     * quarter of the shorter side) along its two sides. A 15 degree bend between 40 m sides moves
     * the line about a meter. The ends stay.
     */
    fun roundBends(poly: List<LatLng>, passes: Int = 2): List<LatLng> {
        var cur = roundCorners(poly)
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

    /**
     * Rounds each junction corner of the drawn line into a curve, the path a car takes through
     * the turn, where the line has the two roads' centers meeting at a point. A corner is a vertex
     * whose chords [CORNER_LEG_M] long either side turn at least [ROUND_MAX_TURN_DEG] and at most
     * [CORNER_MAX_TURN_DEG], one per [CORNER_MERGE_M] (the sharpest). The line from [CORNER_CUT_M]
     * before it to [CORNER_CUT_M] after it (less near another corner or an end) becomes a
     * quadratic Bezier with the corner as its control point. A right angle's curve passes 0.35
     * times the cut, 3.5 m, inside the corner, inside the 8.5 m stripe's half width.
     */
    fun roundCorners(poly: List<LatLng>): List<LatLng> {
        if (poly.size < 3) return poly
        val cum = RouteProjection.cumulative(poly)
        val total = cum.last()
        // The corner vertices, a run turning one way merged to its sharpest. A jog (one way, then
        // back) is two corners.
        val corners = ArrayList<Pair<Int, Double>>() // vertex, signed turn
        for (i in 1 until poly.size - 1) {
            val m = cum[i]
            if (m < CORNER_LEG_M || total - m < CORNER_LEG_M) continue
            val a = RouteProjection.pointAt(poly, cum, m - CORNER_LEG_M)
            val c = RouteProjection.pointAt(poly, cum, m + CORNER_LEG_M)
            if (a.distanceTo(poly[i]) < 1.0 || poly[i].distanceTo(c) < 1.0) continue
            val t = turn(a, poly[i], c)
            if (abs(t) < ROUND_MAX_TURN_DEG) continue
            val prev = corners.lastOrNull()
            if (prev != null && m - cum[prev.first] <= CORNER_MERGE_M && t * prev.second > 0) {
                if (abs(t) > abs(prev.second)) corners[corners.size - 1] = i to t
            } else corners += i to t
        }
        if (corners.isEmpty()) return poly
        val out = ArrayList<LatLng>(poly.size + corners.size * 16)
        var k = 0 // next vertex of poly to copy
        for ((ci, pair) in corners.withIndex()) {
            val i = pair.first
            val t = abs(pair.second)
            val m = cum[i]
            val before = if (ci == 0) m else m - cum[corners[ci - 1].first]
            val after = if (ci == corners.size - 1) total - m else cum[corners[ci + 1].first] - m
            val cut = minOf(CORNER_CUT_M, CORNER_CUT_SHARE * before, CORNER_CUT_SHARE * after)
            if (t > CORNER_MAX_TURN_DEG || cut < CORNER_MIN_CUT_M) continue
            val m0 = m - cut
            val m1 = m + cut
            // Copy the line up to the curve's start (the previous curve ended before it).
            while (k < poly.size && cum[k] < m0) { if (out.isEmpty() || out.last() != poly[k]) out += poly[k]; k++ }
            val p0 = RouteProjection.pointAt(poly, cum, m0)
            val p2 = RouteProjection.pointAt(poly, cum, m1)
            val v = poly[i]
            val steps = maxOf(4, kotlin.math.ceil(2 * cut / CORNER_STEP_M).toInt())
            for (s in 0..steps) {
                val u = s.toDouble() / steps
                val w0 = (1 - u) * (1 - u); val w1 = 2 * u * (1 - u); val w2 = u * u
                out += LatLng(w0 * p0.lat + w1 * v.lat + w2 * p2.lat, w0 * p0.lng + w1 * v.lng + w2 * p2.lng)
            }
            while (k < poly.size && cum[k] <= m1) k++
        }
        while (k < poly.size) { out += poly[k]; k++ }
        return out
    }

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

    /** A traffic circle on a straight street: the route follows half of a ring about 10 m across,
     *  a bulge 3 to 6 m out over 25 to 35 m, which the road-width line showed as a kink where the
     *  drawn street runs straight (2026-10-03). Short spans only: a median split is long, and
     *  straightening one put the line on the median. */
    const val CIRCLE_MAX_SPAN_M = 45.0
    const val CIRCLE_MAX_JOG_M = 8.0

    /** The drawn line's jog rule: [straightenMedianJogs] limited to traffic-circle-sized bulges. */
    fun straightenCircles(poly: List<LatLng>): List<LatLng> = straightenMedianJogs(poly, CIRCLE_MAX_SPAN_M, CIRCLE_MAX_JOG_M)

    fun straightenMedianJogs(poly: List<LatLng>, maxSpanM: Double = MAX_SPAN_M, maxJogM: Double = MAX_JOG_M): List<LatLng> {
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
            while (j < n && cum[j] - cum[i] <= maxSpanM) {
                if (isJog(poly, cum, i, j, maxJogM)) best = j
                j++
            }
            if (best > 0) { out += poly[best]; i = best } else { out += poly[i + 1]; i++ }
        }
        return out
    }

    private fun isJog(p: List<LatLng>, cum: DoubleArray, i: Int, j: Int, maxJogM: Double = MAX_JOG_M): Boolean {
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
            if (dev > maxJogM) return false
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
        val k = RouteProjection.segmentEnd(cum, m)
        val seg = cum[k] - cum[k - 1]
        if (seg <= 0.0) return p[k]
        val t = (m - cum[k - 1]) / seg
        return LatLng(p[k - 1].lat + (p[k].lat - p[k - 1].lat) * t, p[k - 1].lng + (p[k].lng - p[k - 1].lng) * t)
    }
}
