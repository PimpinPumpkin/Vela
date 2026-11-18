package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import app.vela.core.nav.RouteProjection
import kotlin.math.cos
import kotlin.math.hypot

/**
 * "Search along route": filters search results down to the ones that sit near the
 * planned route's polyline, ordered start → destination, so a "gas" / "coffee"
 * search while a trip is planned surfaces stops actually on the way.
 */
object RouteCorridor {

    /** Keep [places] within [maxMeters] of the [route] line, ordered by how far
     *  along the route each one sits (so the list reads in travel order). */
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
                if (best <= maxMeters) Triple(p, bestAlong, best) else null
            }
            .sortedBy { it.second }
            // Rewrite each place's distance to roughly how far you drive to reach it: meters along
            // the route to its projection plus its distance off the line. The raw value was the
            // crow-flies distance from wherever the search was centered (the route midpoint), which
            // read as nonsense in the list -- two stations at opposite ends of the trip both showed
            // "5.9 mi"; along-route alone showed a place 1 km off to the side, level with the car,
            // as "10 ft". The list stays in travel order.
            .map { (p, along, off) -> p.copy(distanceMeters = along + off) }
    }

    /** The part of [route] still ahead after [traveledM] meters along it (the drive's own
     *  monotonic progress, so a route that doubles back is cut at the right pass). During a drive
     *  the along-route search runs on this, so places already passed drop out and each distance
     *  counts from the car, not from where the trip started. */
    fun ahead(route: List<LatLng>, traveledM: Double): List<LatLng> {
        if (route.size < 2 || traveledM <= 0.0) return route
        var done = 0.0
        for (i in 0 until route.size - 1) {
            val seg = route[i].distanceTo(route[i + 1])
            if (done + seg > traveledM) {
                val t = if (seg == 0.0) 0.0 else (traveledM - done) / seg
                val a = route[i]; val b = route[i + 1]
                return listOf(LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))) + route.subList(i + 1, route.size)
            }
            done += seg
        }
        return route.takeLast(2)
    }

    /** A route this long or shorter is searched from its middle, in one window. */
    const val ONE_WINDOW_MAX_M = 25_000.0

    /** On a longer route the windows sit this far apart: a little under the template's own
     *  25 km, so neighbors overlap and a place on the seam is not lost. */
    const val WINDOW_SPACING_M = 20_000.0

    /** At most this many windows, so a search ahead reaches about 120 km. */
    const val MAX_WINDOWS = 6

    /** Where to search for something along [route]. One point (the middle vertex) for a short
     *  route. A long route gets a window every [WINDOW_SPACING_M] from the start, since one
     *  search at the middle never saw the places near either end. */
    fun searchCenters(route: List<LatLng>): List<LatLng> {
        if (route.size < 2) return route
        val cum = RouteProjection.cumulative(route)
        val total = cum.last()
        if (total <= ONE_WINDOW_MAX_M) return listOf(route[route.size / 2])
        val windows = minOf(MAX_WINDOWS, kotlin.math.ceil(total / WINDOW_SPACING_M).toInt())
        return (0 until windows).map { k -> RouteProjection.pointAt(route, cum, minOf((k + 0.5) * WINDOW_SPACING_M, total)) }
    }

    /** Distance (m) from [p] to segment [a]–[b] plus the fraction t∈[0,1] along it,
     *  via a local equirectangular projection (accurate at route scale). */
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
