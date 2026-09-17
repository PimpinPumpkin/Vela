package app.vela.core.nav

import app.vela.core.model.LatLng

/**
 * Whether a license-plate camera beside a route can actually read the cars driving it.
 *
 * A plate reader looks along the road it is mounted on: it catches front plates coming toward it
 * or rear plates going away, but a camera aimed across the road sees cross traffic, not you. So a
 * camera near the route only counts when its facing lines up with the route's travel axis there.
 *
 * The rule: find the route segment nearest the camera (it must be within the caller's distance),
 * take that segment's bearing, and compare it with the camera's facing as LINES, not arrows
 * (modulo 180, so a camera pointing against traffic is as good as one pointing with it). Within
 * [MAX_AXIS_DIFF_DEG] counts. A camera with no known facing counts too: unknown means assume it
 * can see you.
 */
object CameraFacing {

    /** How far off the route's axis a facing may point and still count. */
    const val MAX_AXIS_DIFF_DEG = 50.0

    /** A camera's facing from its string form ("165", "165.0"), or null when untagged or unreadable. */
    fun parse(direction: String?): Double? {
        val d = direction?.trim()?.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return ((d % 360.0) + 360.0) % 360.0
    }

    /** The angle between two bearings treated as lines, 0..90. */
    fun axisDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 180.0
        return if (d > 90.0) 180.0 - d else d
    }

    /** True when a camera facing [facingDeg] can see traffic on a road running [roadBearingDeg]. */
    fun seesRoad(facingDeg: Double?, roadBearingDeg: Double): Boolean =
        facingDeg == null || axisDiff(facingDeg, roadBearingDeg) <= MAX_AXIS_DIFF_DEG

    /** The route segment nearest [p]: its distance in metres and its bearing (degrees from north). */
    data class Nearest(val distanceM: Double, val bearingDeg: Double)

    /** The nearest non-degenerate segment of [poly] to [p], or null when there is none. */
    fun nearestSegment(poly: List<LatLng>, p: LatLng): Nearest? {
        if (poly.size < 2) return null
        val latScale = Math.cos(Math.toRadians(p.lat))
        var bestD = Double.MAX_VALUE
        var bestBx = 0.0
        var bestBy = 0.0
        for (i in 0 until poly.size - 1) {
            val a = poly[i]
            val b = poly[i + 1]
            // Local flat frame in degrees of latitude: exact enough over one segment.
            val bx = (b.lng - a.lng) * latScale
            val by = b.lat - a.lat
            val len2 = bx * bx + by * by
            if (len2 <= 0.0) continue
            val px = (p.lng - a.lng) * latScale
            val py = p.lat - a.lat
            val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val d = Math.hypot(px - bx * t, py - by * t)
            if (d < bestD) {
                bestD = d
                bestBx = bx
                bestBy = by
            }
        }
        if (bestD == Double.MAX_VALUE) return null
        val bearing = (Math.toDegrees(Math.atan2(bestBx, bestBy)) + 360.0) % 360.0
        return Nearest(bestD * 111_320.0, bearing)
    }

    /**
     * True when a camera at [p] facing [facingDeg] (null = unknown) is within [maxOffRouteM] of
     * [poly] and, if its facing is known, looks along the nearest segment's axis.
     */
    fun onRoute(poly: List<LatLng>, p: LatLng, facingDeg: Double?, maxOffRouteM: Double): Boolean {
        val n = nearestSegment(poly, p) ?: return false
        return n.distanceM <= maxOffRouteM && seesRoad(facingDeg, n.bearingDeg)
    }
}
