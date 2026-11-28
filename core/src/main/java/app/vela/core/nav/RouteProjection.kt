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
 * `nav/RouteBar` exposes the same pair by name and delegates here.
 */
object RouteProjection {

    /** The route's heading, in degrees 0-359, [m] meters along [poly]. */
    fun bearingAt(poly: List<LatLng>, cum: DoubleArray, m: Double): Double {
        if (poly.size < 2) return 0.0
        var i = 1
        while (i < cum.size - 1 && cum[i] < m) i++
        val a = poly[i - 1]; val b = poly[i]
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
    }

    /** True when an undirected road orientation ([roadDeg], 0-179) lines up with a heading. */
    fun alignedWithRoad(headingDeg: Double, roadDeg: Int, toleranceDeg: Double = 40.0): Boolean {
        val d = kotlin.math.abs(((headingDeg % 180.0) + 180.0) % 180.0 - roadDeg)
        return minOf(d, 180.0 - d) <= toleranceDeg
    }

    /** How close a stop sign's own node has to be to the route to be on it. A sign you stop at is
     *  a node OF the road you are on: 0 to 8 m from the line on a measured Davis route, where the
     *  signs facing the cross streets sat 12 to 25 m off and the next street over 77 m or more. */
    const val STOP_ON_ROUTE_M = 20.0

    /** How close a stop sign whose road is unknown has to be: between the route's own signs (0 to
     *  8 m) and the cross streets' (12 to 25 m) on the same Davis route. Distance is all there is
     *  to judge it by: the live Overpass path, an all-way stop, or a junction node of two equal
     *  streets. */
    const val STOP_NO_BEARING_M = 10.0

    /**
     * True when the stop sign at [loc], on a road running [roadDeg] (0-179, null when unknown),
     * is one the route stops at: its node is ON the route, and its road runs the way the route
     * does there. Direction alone kept every sign on the parallel street a block over (same
     * bearing, inside the 120 m corridor); distance alone would keep the sign holding the cross
     * street, a few meters from the corner. A sign with no known road must sit within
     * [STOP_NO_BEARING_M]. Call it on the RAW nodes, before any clustering: a cluster's centroid
     * sits in the middle of the junction, not on the line.
     */
    fun stopIsOnRoute(poly: List<LatLng>, cum: DoubleArray, loc: LatLng, roadDeg: Int?): Boolean {
        if (roadDeg == null) return alongMeters(poly, cum, loc, STOP_NO_BEARING_M) != null
        val at = alongMeters(poly, cum, loc, STOP_ON_ROUTE_M) ?: return false
        return alignedWithRoad(bearingAt(poly, cum, at), roadDeg)
    }

    /** A stop sign this far past a junction the route goes through, and no closer than
     *  [STOP_JUNCTION_NODE_M], is the far side's: OSM maps one sign per approach a few meters
     *  before the junction, so leaving it the route runs over the sign for the traffic coming the
     *  other way, or out of the street it turned onto. */
    const val STOP_FAR_SIDE_M = 20.0

    /** A sign this close to a junction is on the junction's own node and holds the route too. */
    const val STOP_JUNCTION_NODE_M = 5.0

    /** Signs this close together stand at one junction, whose middle they mark. */
    const val STOP_JUNCTION_M = 30.0

    /** The turns whose corner is a junction for [stopsNotFarSide]. */
    private val CORNER_TURNS = setOf(
        app.vela.core.model.ManeuverType.TURN_LEFT, app.vela.core.model.ManeuverType.TURN_RIGHT,
        app.vela.core.model.ManeuverType.SLIGHT_LEFT, app.vela.core.model.ManeuverType.SLIGHT_RIGHT,
        app.vela.core.model.ManeuverType.SHARP_LEFT, app.vela.core.model.ManeuverType.SHARP_RIGHT,
        app.vela.core.model.ManeuverType.UTURN,
    )

    /** Meters along [poly] of each corner [route] turns at, for [stopsNotFarSide]. */
    fun cornersAlong(route: app.vela.core.model.Route, poly: List<LatLng>, cum: DoubleArray): List<Double> =
        route.maneuvers.filter { it.type in CORNER_TURNS }.mapNotNull { alongMeters(poly, cum, it.location, STOP_JUNCTION_M) }

    /**
     * [stops] without the signs on the far side of a junction the route goes through (see
     * [STOP_FAR_SIDE_M]): from [STOP_JUNCTION_NODE_M] to [STOP_FAR_SIDE_M] past a corner the route
     * turns at ([corners], meters along [poly]), or past the middle of two or more signs within
     * [STOP_JUNCTION_M] of each other (any signs, the cross street's too), unless another such
     * junction lies ahead of it within [STOP_FAR_SIDE_M]. Other kinds pass through. Call it on
     * the raw nodes, like [stopIsOnRoute].
     */
    fun stopsNotFarSide(
        poly: List<LatLng>, cum: DoubleArray,
        stops: List<app.vela.core.data.TrafficControl>, corners: List<Double>,
    ): List<app.vela.core.data.TrafficControl> {
        val signs = stops.filter { it.kind == app.vela.core.data.TrafficControl.Kind.STOP }
        if (signs.isEmpty() || poly.size < 2) return stops
        val middles = app.vela.core.data.MapDeclutter.cluster(signs, STOP_JUNCTION_M) { it.loc }
            .filter { it.members.size >= 2 }
            .mapNotNull { alongMeters(poly, cum, it.centroid, STOP_ON_ROUTE_M) }
        val junctions = (corners + middles).sorted()
        if (junctions.isEmpty()) return stops
        return stops.filter { c ->
            if (c.kind != app.vela.core.data.TrafficControl.Kind.STOP) return@filter true
            val at = alongMeters(poly, cum, c.loc, STOP_ON_ROUTE_M) ?: return@filter true
            val past = junctions.any { j -> at - j > STOP_JUNCTION_NODE_M && at - j <= STOP_FAR_SIDE_M }
            val before = junctions.any { j -> j - at >= 1.0 && j - at <= STOP_FAR_SIDE_M }
            !past || before
        }
    }

    /** How close a traffic light's node has to be to the route: ON it. OSM maps a light per
     *  approach at the stop line, or one in the junction's middle; the route's own approach and a
     *  middle node sit on the line, while the cross street's approach (10 to 25 m up it), the other
     *  carriageway of a divided road and the parallel street a block over do not. */
    const val SIGNAL_ON_ROUTE_M = 12.0

    /**
     * True when the route drives THROUGH the traffic light at [loc] (user 2026-10-02: "only if we
     * literally cross through them"). The road's direction is not asked: a junction's middle node
     * is on two roads at once. Call it on the RAW nodes, before clustering, like [stopIsOnRoute].
     */
    fun signalIsOnRoute(poly: List<LatLng>, cum: DoubleArray, loc: LatLng): Boolean =
        alongMeters(poly, cum, loc, SIGNAL_ON_ROUTE_M) != null

    /** The first index from 1 whose cumulative length reaches [m], or the last index: the end of
     *  the segment [m] falls on. A binary search, because callers ask for points all along a
     *  line and a scan from the start made that quadratic in the line's length. */
    fun segmentEnd(cum: DoubleArray, m: Double): Int {
        var lo = 1
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < m) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The point [m] meters along [poly] (clamped to its ends). */
    fun pointAt(poly: List<LatLng>, cum: DoubleArray, m: Double): LatLng {
        if (poly.isEmpty()) return LatLng(0.0, 0.0)
        if (poly.size == 1 || m <= 0.0) return poly.first()
        val total = cum.last()
        if (m >= total) return poly.last()
        val i = segmentEnd(cum, m)
        val seg = cum[i] - cum[i - 1]
        val f = if (seg <= 0.0) 0.0 else (m - cum[i - 1]) / seg
        val a = poly[i - 1]; val b = poly[i]
        return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

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
        // One cosine per POINT, not per segment: the route bar projects thousands of corridor
        // marks over thousands of vertices, and cos(lat) changes by 0.02% over a degree of
        // latitude, far below the 40 m question this answers (review 2026-09-12).
        val latScale = Math.cos(Math.toRadians(p.lat))
        for (i in 0 until poly.size - 1) {
            val a = poly[i]
            val b = poly[i + 1]
            val segLen = cum[i + 1] - cum[i]
            if (segLen <= 0.0) continue
            // Local flat frame per segment: exact enough over one segment.
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
