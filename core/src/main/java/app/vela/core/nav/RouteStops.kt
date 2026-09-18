package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route

/**
 * Where each intermediate stop falls in a route's maneuver list (issue #519). Every route source
 * builds ONE leg and the via boundaries carry no DEPART/ARRIVE, so a stop is invisible in the
 * turn list unless it is located by geometry: project the stop onto the polyline for its
 * along-route distance, project each maneuver the same way, and the first maneuver at or past
 * the stop (less a little slack, the stop's own pin sits a few meters off the road) starts the
 * next leg. A stop that projects nowhere near the route (a stale list) is skipped.
 */
object RouteStops {
    /** (maneuver index where the leg after this stop begins, stop label), in route order. */
    fun legStarts(route: Route, stops: List<Pair<LatLng, String>>, slackM: Double = 40.0, tolM: Double = 250.0): List<Pair<Int, String>> {
        val poly = route.polyline
        val maneuvers = route.maneuvers
        if (poly.size < 2 || stops.isEmpty() || maneuvers.isEmpty()) return emptyList()
        val cum = RouteProjection.cumulative(poly)
        val manAlong = maneuvers.map { project(poly, cum, it.location).first }
        val out = ArrayList<Pair<Int, String>>()
        var from = 0
        for ((loc, label) in stops) {
            val (along, off) = project(poly, cum, loc)
            if (off > tolM) continue
            var i = from
            while (i < maneuvers.size && manAlong[i] < along - slackM) i++
            if (i <= 0 || i >= maneuvers.size - 1) continue // before the first turn, or the stop IS the arrival: nothing to divide
            out += i to label
            from = i
        }
        return out
    }

    /** Along-route meters and off-route meters of [p]'s nearest point on the polyline. */
    private fun project(poly: List<LatLng>, cum: DoubleArray, p: LatLng): Pair<Double, Double> {
        val latScale = Math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
        var bestAlong = 0.0
        var bestOff = Double.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val a = poly[i]; val b = poly[i + 1]
            val ax = 0.0; val ay = 0.0
            val bx = (b.lng - a.lng) * latScale * 111_320.0; val by = (b.lat - a.lat) * 111_320.0
            val px = (p.lng - a.lng) * latScale * 111_320.0; val py = (p.lat - a.lat) * 111_320.0
            val len2 = bx * bx + by * by
            val t = if (len2 <= 0.0) 0.0 else (((px - ax) * bx + (py - ay) * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - bx * t; val dy = py - by * t
            val off = Math.sqrt(dx * dx + dy * dy)
            if (off < bestOff) { bestOff = off; bestAlong = cum[i] + Math.sqrt(len2) * t }
        }
        return bestAlong to bestOff
    }
}
