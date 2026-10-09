package app.vela.core.nav

import app.vela.core.data.naming.RoadNameTiles
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Whether the last turn before a stop turns into the stop's parking lot, so the voice can say
 * "Turn left into the parking lot, then <stop> is on your right" where it would say a bare "Turn
 * left" onto a road with no name. No router says so: a parking aisle comes back as a nameless
 * road, the same as a driveway, an alley or an unnamed lane. The map's own vector tiles do, as
 * OpenStreetMap's `service=parking_aisle` (OpenMapTiles `transportation`, `class=service`), so
 * the stretch the route takes past the turn is checked against them. Anything the data cannot
 * settle is a no, and the turn keeps its plain wording.
 */
object ParkingLotTurn {
    /** The check starts this far past the turn, clear of the road the turn leaves. */
    const val FROM_M = 10.0
    /** ...and reads this far on, or to the stop when that comes first. */
    const val SPAN_M = 60.0
    const val STEP_M = 8.0
    /** A sample farther than this from every mapped road is not on one the map knows. */
    const val ON_ROAD_M = 12.0

    /** True when the tiles show [route] past the turn at [turnM] running into a parking lot
     *  before the stop at [stopM]. False when they show anything else or cannot be read. */
    suspend fun entersLot(route: Route, turnM: Double, stopM: Double): Boolean {
        val pts = samples(route, turnM, stopM)
        if (pts.isEmpty()) return false
        val roads = RoadNameTiles.roadsAlong(pts) ?: return false
        return onLotAisles(pts, roads)
    }

    /** Points every [STEP_M] along [route] from [FROM_M] past the turn, up to [SPAN_M] on or the stop. */
    fun samples(route: Route, turnM: Double, stopM: Double): List<LatLng> {
        val poly = route.polyline
        if (poly.size < 2) return emptyList()
        val cum = RouteProjection.cumulative(poly)
        val end = minOf(stopM, turnM + FROM_M + SPAN_M, cum.last())
        val out = ArrayList<LatLng>()
        var m = turnM + FROM_M
        while (m <= end) { out += RouteProjection.pointAt(poly, cum, m); m += STEP_M }
        return out
    }

    /**
     * True when every sample's nearest mapped road is a service road that is not an alley, and at
     * least one is a parking aisle. A lot is often entered by a short untagged service road or a
     * driveway before the aisles start, which is why those count on the way in; an alley, a
     * street, or a sample on no mapped road at all says this is not a lot.
     */
    fun onLotAisles(samples: List<LatLng>, roads: List<RoadNameTiles.RoadLine>): Boolean {
        if (samples.isEmpty()) return false
        var aisle = false
        for (p in samples) {
            var best: RoadNameTiles.RoadLine? = null
            var bestD = Double.MAX_VALUE
            for (r in roads) {
                val d = distance(p, r.points)
                if (d < bestD) { bestD = d; best = r }
            }
            if (best == null || bestD > ON_ROAD_M) return false
            if (best.cls != "service" || best.service == "alley") return false
            if (best.service == "parking_aisle") aisle = true
        }
        return aisle
    }

    /** Meters from [p] to the nearest point of the line [pts]. */
    private fun distance(p: LatLng, pts: List<LatLng>): Double {
        val k = 111_320.0 * cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val ax = (pts[i].lng - p.lng) * k; val ay = (pts[i].lat - p.lat) * 111_320.0
            val bx = (pts[i + 1].lng - p.lng) * k; val by = (pts[i + 1].lat - p.lat) * 111_320.0
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) best = d
        }
        return best
    }
}
