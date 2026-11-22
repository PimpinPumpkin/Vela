package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo

/**
 * Off the route with no signal and nothing on the phone to plan a way back with: how far the
 * route is and which way, for the banner ("The route is 300 m to your left") instead of a
 * "Rerouting..." that never ends.
 *
 * [side] is null when the car's course is not known.
 */
data class BackToRoute(val meters: Double, val side: Side?) {
    enum class Side { AHEAD, RIGHT, BEHIND, LEFT }

    companion object {
        /**
         * The nearest point of [line] ([cum] its cumulative meters) at or past [fromM] meters
         * along, from [car] heading [headingDeg]. Past [fromM] so a car that left the route
         * long ago is not sent back to its start.
         */
        fun of(line: List<LatLng>, cum: DoubleArray, car: LatLng, headingDeg: Double?, fromM: Double): BackToRoute? {
            if (line.size < 2) return null
            val latScale = Math.cos(Math.toRadians(car.lat))
            var bestD = Double.MAX_VALUE
            var best: LatLng? = null
            for (i in 0 until line.size - 1) {
                if (cum[i + 1] < fromM) continue
                val a = line[i]
                val b = line[i + 1]
                val bx = (b.lng - a.lng) * latScale
                val by = b.lat - a.lat
                val px = (car.lng - a.lng) * latScale
                val py = car.lat - a.lat
                val len2 = bx * bx + by * by
                val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
                val d = Math.hypot(px - bx * t, py - by * t)
                if (d < bestD) {
                    bestD = d
                    best = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
                }
            }
            val to = best ?: return null
            val side = headingDeg?.let { h ->
                val dy = to.lat - car.lat
                val dx = (to.lng - car.lng) * latScale
                val bearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
                val rel = (bearing - h + 360.0) % 360.0
                when {
                    rel < 45.0 || rel >= 315.0 -> Side.AHEAD
                    rel < 135.0 -> Side.RIGHT
                    rel < 225.0 -> Side.BEHIND
                    else -> Side.LEFT
                }
            }
            return BackToRoute(car.distanceTo(to), side)
        }
    }
}
