package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turn a planned route's polyline into a synthetic GPS trace so navigation can be **driven anywhere**
 * without a real fix — a demo / screenshot / test mode. The output is the same [ReplayFix] list a
 * recorded trip produces, so [LocationProvider.replay] plays it through the *exact* nav loop live
 * driving uses (turn-by-turn, puck physics, camera, voice) — the phone can sit in one state while the
 * app "drives" a route in another.
 *
 * [fromRoute] with a [Route] drives it the way a car would: each step at the pace the route's own
 * times expect, slowing for turns and bends, pulling away from a stop and braking to one at the
 * end. The polyline form walks the line at a constant [cruiseKmh], for tests that only need the
 * points. Both emit one fix per second (each carrying its along-route heading, its speed as a
 * synthetic doppler reading, and a monotonic timestamp). Pure + unit-testable; no Android, no
 * side effects.
 */
object DemoTrace {
    /** Spacing of the speed profile along the route. */
    private const val GRID_M = 5.0
    /** A step's pace is kept inside this: a walking-speed crawl up to a fast motorway. */
    private const val MIN_CRUISE_MPS = 4.5
    private const val MAX_CRUISE_MPS = 38.0
    /** With no usable times on the route: 48 km/h (30 mph). */
    private const val DEFAULT_MPS = 13.4
    private const val ACCEL = 1.8  // m/s2, pulling away
    private const val BRAKE = 2.2  // m/s2, slowing for a turn
    /** The heading before and after a point is taken this far either side of it. */
    private const val TURN_WINDOW_M = 12.0
    /** The slowest the car is ever moved per second, so a profile that touches zero still ends. */
    private const val CRAWL_MPS = 1.0
    /** Seconds the car sits at the start before pulling away, as a driver does: the opening line
     *  is being said, and a first turn 70 m on would otherwise cut it off. */
    private const val START_HOLD_S = 3

    /** The speed a bend of [deg] degrees (over [TURN_WINDOW_M] either side) is taken at, m/s. */
    internal fun turnSpeed(deg: Double): Double = when {
        deg < 18.0 -> Double.MAX_VALUE
        deg < 40.0 -> 13.0  // a ramp's curve, a sweeping bend
        deg < 70.0 -> 8.0
        deg < 120.0 -> 5.0  // a turn at a junction
        else -> 3.0         // a hairpin or a U-turn
    }

    /**
     * [route] driven at a realistic pace. A constant 72 km/h through town blocks put a new turn
     * every five seconds, and the "turn now" lines cut off whatever was still being said: half of
     * the lines on 110 m blocks. At the route's own pace none are (DemoTraceTest).
     *
     * Each step runs at its length over its time, between [MIN_CRUISE_MPS] and [MAX_CRUISE_MPS];
     * a route whose steps carry no times runs at its overall average, else [DEFAULT_MPS]. Bends
     * cap the speed ([turnSpeed]), and the car accelerates at [ACCEL] and brakes at [BRAKE], from
     * a standstill at the start to one at the end.
     *
     * [startMps] above zero is a drive already under way that has been handed a new route (a stop
     * added mid-drive): it carries on from that speed with no wait.
     */
    fun fromRoute(route: app.vela.core.model.Route, startMps: Double = 0.0): List<ReplayFix> {
        val poly = route.polyline
        if (poly.size < 2) return emptyList()
        val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
        val total = cum.last()
        if (total <= 0.0) return emptyList()
        fun at(m: Double) = app.vela.core.nav.RouteProjection.pointAt(poly, cum, m)
        fun pace(meters: Double, seconds: Double): Double? =
            if (meters > 5.0 && seconds > 1.0) (meters / seconds).coerceIn(MIN_CRUISE_MPS, MAX_CRUISE_MPS) else null

        val n = Math.ceil(total / GRID_M).toInt()
        val ds = total / n
        val limit = DoubleArray(n + 1) { pace(route.distanceMeters, route.durationSeconds) ?: DEFAULT_MPS }
        // Each step at its own pace, where both of its ends are found on the line.
        val ms = route.maneuvers
        val marks = app.vela.core.nav.NavEngine.stopMarks(route, ms.map { it.location })
        for (k in 0 until ms.size - 1) {
            val a = marks[k] ?: continue
            val b = marks[k + 1] ?: continue
            val v = pace(b - a, ms[k].durationSeconds) ?: continue
            for (i in Math.ceil(a / ds).toInt()..minOf(n, Math.floor(b / ds).toInt())) limit[i] = v
        }
        // Bends and turns, from the line's own shape.
        for (i in 1 until n) {
            val m = i * ds
            if (m < TURN_WINDOW_M || m > total - TURN_WINDOW_M) continue
            val p = at(m)
            val turn = Math.abs(((bearing(p, at(m + TURN_WINDOW_M)) - bearing(at(m - TURN_WINDOW_M), p) + 540.0) % 360.0) - 180.0)
            limit[i] = minOf(limit[i], turnSpeed(turn))
        }
        // Brake in time for each slow point and for the end, then pull away from each.
        limit[n] = 0.0
        for (i in n - 1 downTo 0) limit[i] = minOf(limit[i], Math.sqrt(limit[i + 1] * limit[i + 1] + 2 * BRAKE * ds))
        limit[0] = minOf(limit[0], maxOf(CRAWL_MPS, startMps))
        for (i in 1..n) limit[i] = minOf(limit[i], Math.sqrt(limit[i - 1] * limit[i - 1] + 2 * ACCEL * ds))

        val out = ArrayList<ReplayFix>()
        var m = 0.0
        var t = 0L
        var heading = bearing(poly[0], at(minOf(total, GRID_M))).toFloat()
        if (startMps <= 0.0) repeat(START_HOLD_S) {
            out.add(ReplayFix(poly[0].lat, poly[0].lng, t, heading, 0f))
            t += 1000L
        }
        while (m < total) {
            val f = m / ds
            val i = f.toInt().coerceAtMost(n - 1)
            val v = maxOf(CRAWL_MPS, limit[i] + (limit[i + 1] - limit[i]) * (f - i))
            val p = at(m)
            val ahead = at(minOf(total, m + GRID_M))
            if (p.distanceTo(ahead) > 0.5) heading = bearing(p, ahead).toFloat()
            out.add(ReplayFix(p.lat, p.lng, t, heading, v.toFloat()))
            m += v
            t += 1000L
        }
        out.add(ReplayFix(poly.last().lat, poly.last().lng, t, heading, 0f))
        return out
    }

    fun fromRoute(polyline: List<LatLng>, cruiseKmh: Double = 72.0): List<ReplayFix> {
        if (polyline.size < 2) return emptyList()
        val speedMs = cruiseKmh / 3.6
        val stepM = speedMs // one fix per second → advance `speed` meters per fix
        val speedF = speedMs.toFloat()
        val out = ArrayList<ReplayFix>()
        var t = 0L
        var seg = 0
        var pos = polyline[0]
        fun headingHere(): Float = bearing(pos, polyline[minOf(seg + 1, polyline.size - 1)]).toFloat()
        out.add(ReplayFix(pos.lat, pos.lng, t, headingHere(), speedF))
        while (seg < polyline.size - 1) {
            var remaining = stepM
            // Advance `stepM` meters along the polyline from the current position.
            while (remaining > 0.0 && seg < polyline.size - 1) {
                val next = polyline[seg + 1]
                val d = pos.distanceTo(next)
                if (d <= remaining || d == 0.0) {
                    remaining -= d
                    pos = next
                    seg++
                } else {
                    val f = remaining / d
                    pos = LatLng(pos.lat + (next.lat - pos.lat) * f, pos.lng + (next.lng - pos.lng) * f)
                    remaining = 0.0
                }
            }
            t += 1000L
            out.add(ReplayFix(pos.lat, pos.lng, t, headingHere(), speedF))
        }
        return out
    }

    /** Initial great-circle bearing a→b, degrees 0–360. */
    private fun bearing(a: LatLng, b: LatLng): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
