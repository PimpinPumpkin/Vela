package app.vela.core.nav

import app.vela.core.model.LatLng

/**
 * Where to try a side street around the plate cameras on a route (issue #600).
 *
 * The reporter's own workaround, automated: for each cluster of cameras the leading route passes,
 * try a point a little way to either side of the road at the cluster and route THROUGH it. The
 * router's own snap does the graph work: a point that lands on the same road folds back into the
 * same route (the camera count does not drop, so the caller rejects it), a point that lands on a
 * parallel street is a real detour. Nothing here knows about roads; it only says where to ask.
 *
 * Bounded on purpose: at most [MAX_CLUSTERS] clusters, nearest first, and the caller sends at
 * most [MAX_REQUESTS] route requests for a trip, because each one is a full route request to a
 * fair-use community router plus Google.
 */
object CameraDetour {

    /** How far off the road a candidate point sits. Far enough to reach the next street over in a
     *  city block, near enough that a rural road with nothing beside it snaps straight back. */
    const val OFFSET_M = 150.0
    const val MAX_CLUSTERS = 3
    const val MAX_REQUESTS = 6

    /** A camera cluster on the route ([atM] along it, [count] heads) and the two points to try. */
    data class Candidate(val atM: Double, val count: Int, val left: LatLng, val right: LatLng)

    /** Candidates for the clusters of [cameraAlongM] (distances along [poly], any order), nearest
     *  first, at most [maxClusters]. Left is the driver's left at the route's bearing there. */
    fun candidates(
        poly: List<LatLng>,
        cameraAlongM: List<Double>,
        offsetM: Double = OFFSET_M,
        maxClusters: Int = MAX_CLUSTERS,
    ): List<Candidate> {
        if (poly.size < 2 || cameraAlongM.isEmpty()) return emptyList()
        val cum = RouteProjection.cumulative(poly)
        return CameraAlerts.group(cameraAlongM.sorted()).take(maxClusters).map { g ->
            val p = RouteProjection.pointAt(poly, cum, g.atM)
            val brg = RouteProjection.bearingAt(poly, cum, g.atM)
            Candidate(g.atM, g.count, offset(p, brg - 90.0, offsetM), offset(p, brg + 90.0, offsetM))
        }
    }

    /** [p] moved [meters] along compass [bearingDeg]. Planar; exact to centimeters at this range. */
    internal fun offset(p: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val b = Math.toRadians(bearingDeg)
        val dLat = meters * Math.cos(b) / 111_320.0
        val dLng = meters * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(p.lat)))
        return LatLng(p.lat + dLat, p.lng + dLng)
    }

    /** The user's stops and the detour points merged into ONE travel-ordered list by their
     *  position along the route. A stop with no position (not on this line) keeps its place by
     *  index between the ones that have one. */
    fun mergePlan(stops: List<Pair<Double?, LatLng>>, vias: List<Pair<Double, LatLng>>): List<LatLng> {
        // Fill a stop's missing position from its neighbors so the sort is stable and in order.
        val filled = ArrayList<Pair<Double, LatLng>>(stops.size)
        var last = 0.0
        for ((i, s) in stops.withIndex()) {
            val at = s.first ?: (stops.drop(i + 1).firstNotNullOfOrNull { it.first }?.let { (last + it) / 2 } ?: (last + 1.0))
            filled += at to s.second
            last = at
        }
        return (filled + vias).sortedBy { it.first }.map { it.second }
    }
}
