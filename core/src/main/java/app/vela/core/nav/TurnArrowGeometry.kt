package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.bearingTo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The arrows drawn on the route at its turns while navigating (issue #804): a short stretch of the
 * route from [BEFORE_M] before the maneuver point through the corner to [AFTER_M] past it, with the
 * bearing the stretch ends on for the arrowhead. One arrow per maneuver that bends the course,
 * from the step in hand onward; a continue, a merge or a roundabout's entry gets none.
 *
 * The corner is found on the line rather than taken from the maneuver point: a fork's or a
 * ramp's split sits past the point the route puts the maneuver at, and an arrow cut at a fixed
 * distance there ran straight on. Each arm is simplified within [SIMPLIFY_M], so the shaft follows
 * a wavy road, and every bend is rounded into an arc of up to [ROUND_M] radius ([round]), so the
 * shaft curves through a corner the way the car does instead of folding at a point.
 *
 * Each arrow knows where it lies along the line ([Arrow.fromM], [Arrow.toM]) and which share of
 * its own length a point along the line is ([Arrow.fraction]): the map cuts the shaft at the
 * car every frame, so the part already driven over is never drawn.
 *
 * The arrows are cut from the line the map draws, passed as `line`: a Google route is guided on
 * Google's own line, which runs in a lane, while the map draws a line moved onto the road's
 * center and smoothed, up to about 9 m away, and arrows cut from the guidance line sat beside
 * the blue line in places.
 */
object TurnArrowGeometry {
    class Arrow(
        val points: List<LatLng>,
        val endBearingDeg: Double,
        /** Where the shaft starts and ends, in meters along the line it was cut from. */
        val fromM: Double,
        val toM: Double,
        /** For each of [points], its meters along that line and along the shaft itself. */
        private val alongM: DoubleArray,
        private val shaftM: DoubleArray,
    ) {
        /** The shaft's length as drawn, which a rounded corner makes a little shorter than [toM] - [fromM]. */
        val lengthM: Double get() = shaftM.last()

        /** The share of the shaft, 0 to 1, that lies before [m] meters along the line. */
        fun fraction(m: Double): Double {
            if (m <= alongM.first()) return 0.0
            if (m >= alongM.last()) return 1.0
            var lo = 0
            var hi = alongM.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (alongM[mid] <= m) lo = mid else hi = mid
            }
            val span = alongM[hi] - alongM[lo]
            val t = if (span <= 0.0) 0.0 else (m - alongM[lo]) / span
            val len = shaftM.last()
            return if (len <= 0.0) 1.0 else (shaftM[lo] + t * (shaftM[hi] - shaftM[lo])) / len
        }
    }

    const val BEFORE_M = 30.0
    const val AFTER_M = 30.0

    /** How far a maneuver point may sit off the line and still be placed along it. */
    const val MATCH_M = 40.0

    /** An arrow shorter than this (two maneuvers on top of each other at the route's ends) is skipped. */
    const val MIN_LENGTH_M = 15.0

    /** The corner is looked for from [CORNER_BACK_M] before the maneuver point to [SEEK_M] past it. */
    const val CORNER_BACK_M = 10.0
    const val SEEK_M = 150.0

    /** A vertex is a corner when the chords [HOLD_M] long on either side of it differ by [BEND_DEG]
     *  or more: a lane change is a jog that the chord after it does not hold. */
    const val HOLD_M = 25.0
    const val BEND_DEG = 12.0

    /** A sharper vertex within this of a corner candidate is the corner instead. */
    const val CORNER_NEAR_M = 15.0

    /** The arrowhead aims along the arrow's own last this many meters, as drawn. */
    const val HEAD_CHORD_M = 6.0

    /** A vertex nearer than this to the chord between its arm's ends is dropped. Small, so the
     *  shaft follows a wavy road: at 1.5 m it ran straight over a line weaving under it. */
    const val SIMPLIFY_M = 0.5

    /** A bend is rounded into an arc of at most this radius, less where the segments beside it are
     *  short. At 5 m the arc stays inside the 8.5 m blue line at any corner up to a U-turn: the
     *  shaft's dark edge (72% of the line's width) reaches no further in than the line's own edge. */
    const val ROUND_M = 5.0

    /** A bend under this is left as it is; an arc is cut into steps of at most [ARC_STEP_DEG]. */
    const val ROUND_MIN_DEG = 2.0
    const val ARC_STEP_DEG = 10.0

    /** On a drawn line, a maneuver is looked for within this of where the guidance line puts it,
     *  scaled by the two lines' lengths: the other carriageway of a U-turn is never taken for it. */
    const val DRAWN_WINDOW_M = 120.0

    val TYPES: Set<ManeuverType> = setOf(
        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
        ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.UTURN, ManeuverType.FORK_LEFT,
        ManeuverType.FORK_RIGHT, ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.EXIT_ROUNDABOUT,
        ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
    )

    /** The arrows for [route] from [fromStep] on, cut from [line], the line the map draws for it. */
    fun arrows(route: Route, fromStep: Int, line: List<LatLng> = route.polyline): List<Arrow> {
        val poly = line
        if (poly.size < 2) return emptyList()
        val cum = RouteProjection.cumulative(poly)
        val total = cum.last()
        val guide = route.polyline
        val guideCum = if (guide === poly) cum else RouteProjection.cumulative(guide)
        val scale = if (guide === poly || guideCum.isEmpty() || guideCum.last() <= 0.0) 1.0 else total / guideCum.last()
        val out = ArrayList<Arrow>()
        val maneuvers = route.maneuvers
        for (i in maxOf(fromStep, 0) until maneuvers.size) {
            val m = maneuvers[i]
            if (m.type !in TYPES) continue
            val at = if (guide === poly) RouteProjection.alongMeters(poly, cum, m.location, MATCH_M) ?: continue else {
                val g = RouteProjection.alongMeters(guide, guideCum, m.location, MATCH_M) ?: continue
                val (a, off) = NavEngine.projectAlong(poly, cum, m.location, g * scale - DRAWN_WINDOW_M, g * scale + DRAWN_WINDOW_M)
                if (off > MATCH_M) continue
                a
            }
            val s0 = maxOf(0.0, at - BEFORE_M)
            val corner = cornerIndex(poly, cum, at)
            val cornerM = if (corner >= 0) cum[corner] else at
            val s1 = minOf(total, cornerM + AFTER_M)
            if (s1 - s0 < MIN_LENGTH_M) continue
            val raw = if (corner >= 0 && cornerM > s0 && cornerM < s1)
                simplify(stretch(poly, cum, s0, cornerM)) + simplify(stretch(poly, cum, cornerM, s1)).drop(1)
            else simplify(stretch(poly, cum, s0, s1))
            val (pts, along) = round(raw.map { it.first }, DoubleArray(raw.size) { raw[it].second })
            // The head lines up with the shaft's end as drawn, not with the line under it.
            val pc = RouteProjection.cumulative(pts)
            out += Arrow(pts, chord(pts, pc, maxOf(0.0, pc.last() - HEAD_CHORD_M), pc.last()), s0, s1, along, pc)
        }
        return out
    }

    /** The vertex the course turns at, from [CORNER_BACK_M] before [at] to [SEEK_M] past it: the
     *  first whose hinge ([hinge]) reaches [BEND_DEG], moved on to a sharper vertex within
     *  [CORNER_NEAR_M] of it. -1 when the line does not turn there. */
    private fun cornerIndex(poly: List<LatLng>, cum: DoubleArray, at: Double): Int {
        var k = RouteProjection.segmentEnd(cum, maxOf(0.0, at - CORNER_BACK_M))
        if (cum[k] < at - CORNER_BACK_M) k++
        val end = at + SEEK_M
        while (k < poly.size - 1 && cum[k] <= end) {
            var best = hinge(poly, cum, k)
            if (best >= BEND_DEG) {
                var j = k + 1
                while (j < poly.size - 1 && cum[j] - cum[k] <= CORNER_NEAR_M) {
                    val h = hinge(poly, cum, j)
                    if (h > best) { best = h; k = j }
                    j++
                }
                return k
            }
            k++
        }
        return -1
    }

    /** How far the course turns at vertex [k]: the chord [HOLD_M] before it against the chord [HOLD_M] after. */
    private fun hinge(poly: List<LatLng>, cum: DoubleArray, k: Int): Double {
        val before = chord(poly, cum, maxOf(0.0, cum[k] - HOLD_M), cum[k])
        val after = chord(poly, cum, cum[k], minOf(cum.last(), cum[k] + HOLD_M))
        return abs(((after - before + 540.0) % 360.0) - 180.0)
    }

    /** The bearing from [a] to [b] meters along the line; the line's own heading when they coincide. */
    private fun chord(poly: List<LatLng>, cum: DoubleArray, a: Double, b: Double): Double {
        if (b - a < 0.5) return RouteProjection.bearingAt(poly, cum, b)
        return RouteProjection.pointAt(poly, cum, a).bearingTo(RouteProjection.pointAt(poly, cum, b))
    }

    /** The line's points from [a] to [b] meters along it, the ends interpolated, each with its meters. */
    private fun stretch(poly: List<LatLng>, cum: DoubleArray, a: Double, b: Double): List<Pair<LatLng, Double>> {
        val pts = ArrayList<Pair<LatLng, Double>>()
        pts += RouteProjection.pointAt(poly, cum, a) to a
        var k = RouteProjection.segmentEnd(cum, a)
        while (k < cum.size && cum[k] < b) {
            if (cum[k] > a) pts += poly[k] to cum[k]
            k++
        }
        pts += RouteProjection.pointAt(poly, cum, b) to b
        return pts
    }

    /**
     * Rounds every bend of [pts] over [ROUND_MIN_DEG] into an arc of radius up to [ROUND_M], its
     * tangent points no further from the bend than half of either segment beside it, so two arcs
     * never overlap. [along] holds each point's meters along the line; an arc's points get meters
     * spread evenly between its tangent points'. The ends stay.
     */
    private fun round(pts: List<LatLng>, along: DoubleArray): Pair<List<LatLng>, DoubleArray> {
        if (pts.size < 3) return pts to along
        val lat0 = pts[0].lat
        val lng0 = pts[0].lng
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val xs = DoubleArray(pts.size) { (pts[it].lng - lng0) * kx }
        val ys = DoubleArray(pts.size) { (pts[it].lat - lat0) * 111_320.0 }
        val outX = ArrayList<Double>(); val outY = ArrayList<Double>(); val outA = ArrayList<Double>()
        fun add(x: Double, y: Double, a: Double) { outX += x; outY += y; outA += a }
        add(xs[0], ys[0], along[0])
        for (i in 1 until pts.size - 1) {
            val ax = xs[i] - xs[i - 1]; val ay = ys[i] - ys[i - 1]
            val bx = xs[i + 1] - xs[i]; val by = ys[i + 1] - ys[i]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-6 || lb < 1e-6) { add(xs[i], ys[i], along[i]); continue }
            val cross = ax * by - ay * bx
            val turn = kotlin.math.atan2(cross, ax * bx + ay * by) // signed, radians
            val deg = Math.toDegrees(abs(turn))
            if (deg < ROUND_MIN_DEG || deg > 179.0) { add(xs[i], ys[i], along[i]); continue }
            val tanHalf = kotlin.math.tan(abs(turn) / 2)
            val t = minOf(ROUND_M * tanHalf, la / 2, lb / 2)
            val r = t / tanHalf
            val ux = ax / la; val uy = ay / la; val vx = bx / lb; val vy = by / lb
            val p1x = xs[i] - ux * t; val p1y = ys[i] - uy * t
            // The center sits r to the inside of the first tangent point.
            val sgn = if (cross > 0) 1.0 else -1.0
            val cx = p1x - uy * r * sgn; val cy = p1y + ux * r * sgn
            val a1 = along[i] - t * (along[i] - along[i - 1]) / la
            val a2 = along[i] + t * (along[i + 1] - along[i]) / lb
            val steps = maxOf(1, kotlin.math.ceil(deg / ARC_STEP_DEG).toInt())
            val start = kotlin.math.atan2(p1y - cy, p1x - cx)
            for (k in 0..steps) {
                val ang = start + turn * k / steps
                add(cx + r * kotlin.math.cos(ang), cy + r * kotlin.math.sin(ang), a1 + (a2 - a1) * k / steps)
            }
        }
        add(xs.last(), ys.last(), along.last())
        val out = List(outX.size) { LatLng(lat0 + outY[it] / 111_320.0, lng0 + outX[it] / kx) }
        return out to outA.toDoubleArray()
    }

    /** Drops the vertices within [SIMPLIFY_M] of the chord between the ones kept (Douglas-Peucker in
     *  a local meter frame); the ends stay. */
    private fun simplify(raw: List<Pair<LatLng, Double>>): List<Pair<LatLng, Double>> {
        if (raw.size <= 2) return raw
        val pts = raw.map { it.first }
        val lat0 = pts[0].lat
        val lng0 = pts[0].lng
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val xs = DoubleArray(pts.size) { (pts[it].lng - lng0) * kx }
        val ys = DoubleArray(pts.size) { (pts[it].lat - lat0) * 111_320.0 }
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        fun run(a: Int, b: Int) {
            if (b - a < 2) return
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val len = hypot(dx, dy)
            var worst = -1
            var worstD = SIMPLIFY_M
            for (i in a + 1 until b) {
                val d = if (len < 1e-9) hypot(xs[i] - xs[a], ys[i] - ys[a]) else abs(dx * (ys[i] - ys[a]) - dy * (xs[i] - xs[a])) / len
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worst < 0) return
            keep[worst] = true
            run(a, worst)
            run(worst, b)
        }
        run(0, pts.size - 1)
        return raw.filterIndexed { i, _ -> keep[i] }
    }
}
