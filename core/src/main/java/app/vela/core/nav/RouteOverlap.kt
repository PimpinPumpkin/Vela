package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Whether a line on the map lies on a route, and where.
 *
 * A line held near a route (a tolled bridge, a work zone) can be the road the route drives, the road
 * beside it, or a road that crosses it. Only the first is on the route. [runsAlong] walks the line
 * in steps and asks two things: do enough of the steps sit on the route, and does the line run the
 * way the route does there. A road that crosses the route touches it at one or two steps and runs
 * across it, so it fails both.
 */
object RouteOverlap {
    /** How far from the route's line a step may be and still count as on the same road. */
    const val MAX_OFF_M = 40.0

    /** A line is walked in steps this long. It counts when at least [MIN_SAMPLES] steps lie on the
     *  route, or all of them when it has fewer. */
    const val SAMPLE_M = 25.0
    const val MIN_SAMPLES = 3

    /**
     * Where [line] lies on [route]: the meters along the route where it starts and where it ends, or
     * null when it does not run along it. [cum] is [RouteProjection.cumulative] of the route. A
     * single point counts when it is within [MAX_OFF_M] of the route, and starts and ends there.
     */
    fun runsAlong(route: List<LatLng>, cum: DoubleArray, line: List<LatLng>): Pair<Double, Double>? {
        val samples = densify(line)
        val on = samples.indices.mapNotNull { i -> RouteProjection.alongMeters(route, cum, samples[i], MAX_OFF_M)?.let { i to it } }
        if (on.isEmpty()) return null
        val isLine = line.size > 1
        if (isLine && on.size < MIN_SAMPLES && on.size < samples.size) return null
        val at = on.minOf { it.second }
        if (isLine) {
            val first = on.first().first
            val a = samples[first]; val b = samples[if (first + 1 < samples.size) first + 1 else first - 1]
            val roadDeg = (headingDeg(a, b) % 180.0).toInt()
            if (!RouteProjection.alignedWithRoad(RouteProjection.bearingAt(route, cum, at), roadDeg)) return null
        }
        return at to on.maxOf { it.second }
    }

    /** [line] with a point at least every [SAMPLE_M]. */
    fun densify(line: List<LatLng>): List<LatLng> {
        if (line.size < 2) return line
        val out = ArrayList<LatLng>()
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val steps = ceil(a.distanceTo(b) / SAMPLE_M).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                out += LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
            }
        }
        out += line.last()
        return out
    }

    private fun headingDeg(a: LatLng, b: LatLng): Double {
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }
}
