package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import kotlin.math.cos
import kotlin.math.hypot

/**
 * "Search along route": filters search results down to the ones that sit near the
 * planned route's polyline, ordered start → destination, so a "gas" / "coffee"
 * search while a trip is planned surfaces stops actually on the way.
 */
object RouteCorridor {

    /** Keep [places] within [maxMeters] of the [route] line, ordered by how far
     * along the route each one sits (so the list reads in travel order). */
    fun alongRoute(places: List<Place>, route: List<LatLng>, maxMeters: Double = 3000.0): List<Place> {
        if (route.size < 2) return places
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + route[i - 1].distanceTo(route[i])
        return places
            .mapNotNull { p ->
                var best = Double.MAX_VALUE
                var bestAlong = 0.0
                for (i in 0 until route.size - 1) {
                    val (d, t) = segmentDistance(p.location, route[i], route[i + 1])
                    if (d < best) {
                        best = d
                        bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
                    }
                }
                if (best <= maxMeters) p to bestAlong else null
            }
            .sortedBy { it.second }
            // Rewrite each place's distance to its ALONG-ROUTE distance (metres from the route
            // start to its projection). The raw value was the crow-flies distance from wherever
            // the search was centred (the route midpoint), which read as nonsense in the list --
            // two stations at opposite ends of the trip both showed "5.9 mi". Along-route
            // distance is monotonic with the list order: how far into the drive the stop sits.
            .map { (p, along) -> p.copy(distanceMeters = along) }
    }

    /** Distance (m) from [p] to segment [a]–[b] plus the fraction t∈[0,1] along it,
     * via a local equirectangular projection (accurate at route scale). */
    private fun segmentDistance(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2.0))
        val bx = (b.lng - a.lng) * mPerDegLng
        val by = (b.lat - a.lat) * mPerDegLat
        val px = (p.lng - a.lng) * mPerDegLng
        val py = (p.lat - a.lat) * mPerDegLat
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by) to t
    }
}
