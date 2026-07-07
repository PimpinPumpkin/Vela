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
 * Walks the polyline at a constant [cruiseKmh] emitting one fix per second (each carrying its
 * along-route heading, the cruising speed as a synthetic doppler reading, and a monotonic timestamp).
 * Pure + unit-testable; no Android, no side effects.
 */
object DemoTrace {

    fun fromRoute(polyline: List<LatLng>, cruiseKmh: Double = 72.0): List<ReplayFix> {
        if (polyline.size < 2) return emptyList()
        val speedMs = cruiseKmh / 3.6
        val stepM = speedMs // one fix per second → advance `speed` metres per fix
        val speedF = speedMs.toFloat()
        val out = ArrayList<ReplayFix>()
        var t = 0L
        var seg = 0
        var pos = polyline[0]
        fun headingHere(): Float = bearing(pos, polyline[minOf(seg + 1, polyline.size - 1)]).toFloat()
        out.add(ReplayFix(pos.lat, pos.lng, t, headingHere(), speedF))
        while (seg < polyline.size - 1) {
            var remaining = stepM
            // Advance `stepM` metres along the polyline from the current position.
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
