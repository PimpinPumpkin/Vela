package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo

/**
 * Placing a point on a route: how far along the line it sits, and whether it is genuinely on it.
 *
 * Anything fetched in a CORRIDOR around a route comes back with things near the road but not on
 * it - the parallel street, the service road, the far carriageway. Turning those into "coming up
 * ahead of you" would be wrong, so every consumer needs the same two operations: cumulative
 * distances once per route, then a bounded projection per point.
 *
 * NB `nav/RouteBar` (issue #228, open in parallel) carries an identical pair; whichever merges
 * second should delegate here rather than keep a second copy of this maths.
 */
object RouteProjection {

    /** Cumulative distance to each vertex, computed once per route so a lookup never re-walks it. */
    fun cumulative(poly: List<LatLng>): DoubleArray {
        val cum = DoubleArray(poly.size)
        for (i in 1 until poly.size) cum[i] = cum[i - 1] + poly[i - 1].distanceTo(poly[i])
        return cum
    }

    /**
     * How far along the route [p] sits, or null when it is further than [maxOffRouteM] from the
     * line - i.e. near the road but not on it.
     */
    fun alongMeters(
        poly: List<LatLng>,
        cum: DoubleArray,
        p: LatLng,
        maxOffRouteM: Double = 40.0,
    ): Double? {
        if (poly.size < 2) return null
        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until poly.size - 1) {
            val a = poly[i]
            val b = poly[i + 1]
            val segLen = cum[i + 1] - cum[i]
            if (segLen <= 0.0) continue
            // Local flat frame per segment: exact enough over one segment, and avoids trigonometry
            // per vertex on a polyline with thousands of points.
            val latScale = Math.cos(Math.toRadians(a.lat))
            val bx = (b.lng - a.lng) * latScale
            val by = b.lat - a.lat
            val px = (p.lng - a.lng) * latScale
            val py = p.lat - a.lat
            val len2 = bx * bx + by * by
            val t = if (len2 <= 0.0) 0.0 else (((px * bx) + (py * by)) / len2).coerceIn(0.0, 1.0)
            val dM = Math.hypot(px - bx * t, py - by * t) * 111_320.0
            if (dM < bestD) {
                bestD = dM
                bestAlong = cum[i] + segLen * t
            }
        }
        return if (bestD <= maxOffRouteM) bestAlong else null
    }
}
