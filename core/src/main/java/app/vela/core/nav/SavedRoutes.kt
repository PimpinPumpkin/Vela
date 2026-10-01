package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.SavedRoute
import app.vela.core.model.TravelMode
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Turning a saved route (issue #622) back into a live one. A saved route is offered when a trip
 * starts near where it started and ends near where it ended, in the same mode. It is rebuilt by
 * asking the router for the trip THROUGH a few via points on its line, placed only where the line
 * leaves the router's own answer: one in the middle of each stretch that differs (more on a long
 * one). Few vias, each on the saved road away from where the two routes meet, give the router the
 * least room to snap one onto the wrong road.
 */
object SavedRoutes {
    /** A saved route's end may sit this far from the trip's (its line starts on the road, the
     *  trip at a door). */
    const val DEST_M = 250.0
    /** The start can be farther: the same commute starts from the driveway or the corner. */
    const val ORIGIN_M = 1_000.0
    /** A sample of the saved line this far from the router's line is on a different road. */
    const val OFF_M = 60.0
    /** A stretch shorter than this is the two lines disagreeing about one junction, not a
     *  different way. */
    const val MIN_RUN_M = 150.0
    /** One extra via per this much of a long differing stretch, so the router cannot cut its
     *  middle short. */
    const val VIA_EVERY_M = 2_500.0
    const val MAX_VIAS = 8
    /** Stops on one trip: a run saves at most this many, and the chooser adds no more. */
    const val MAX_STOPS = 10
    private const val STEP_M = 25.0
    private const val END_SKIP_M = 150.0

    /** A run is started from the list, never offered as an alternate: that would add its stops to
     *  a plain trip. */
    fun matches(r: SavedRoute, origin: LatLng, dest: LatLng, mode: TravelMode): Boolean =
        !r.isRun && r.mode == mode.name && dist(r.dest, dest) <= DEST_M && dist(r.origin, origin) <= ORIGIN_M

    /** Via points on [saved] where it leaves [reference]; empty when the two are the same way. */
    fun viasAgainst(saved: List<LatLng>, reference: List<LatLng>): List<LatLng> {
        if (saved.size < 2 || reference.size < 2) return emptyList()
        val samples = densify(saved)
        val total = samples.last().second
        // Runs of consecutive samples off the reference line, as [startM, endM] along the saved line.
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for ((i, s) in samples.withIndex()) {
            val inside = s.second > END_SKIP_M && s.second < total - END_SKIP_M
            val off = inside && distToLine(s.first, reference) > OFF_M
            if (off && start < 0) start = i
            if (!off && start >= 0) { runs += start to i - 1; start = -1 }
        }
        if (start >= 0) runs += start to samples.size - 1
        val vias = mutableListOf<LatLng>()
        for ((a, b) in runs) {
            val len = samples[b].second - samples[a].second
            if (len < MIN_RUN_M) continue
            val n = 1 + (len / VIA_EVERY_M).toInt()
            for (k in 1..n) {
                val at = samples[a].second + len * k / (n + 1)
                vias += samples.minByOrNull { kotlin.math.abs(it.second - at) }!!.first
            }
        }
        if (vias.size <= MAX_VIAS) return vias
        // Too many: keep evenly spaced ones (first and last included).
        return (0 until MAX_VIAS).map { vias[it * (vias.size - 1) / (MAX_VIAS - 1)] }
    }

    /** A drive at least this long can be offered for saving. */
    const val MIN_DRIVE_M = 500.0

    /** "Save the way you drove": the [trace] of a finished drive went its own way, leaving the
     *  route planned at the start ([planned]) for a real stretch (the same test that places vias,
     *  so a one-junction wobble or GPS scatter does not count), and is long enough to keep. */
    fun droveOwnWay(trace: List<LatLng>, planned: List<LatLng>): Boolean {
        if (trace.size < 2 || planned.size < 2) return false
        var len = 0.0
        for (i in 1 until trace.size) len += dist(trace[i - 1], trace[i])
        return len >= MIN_DRIVE_M && viasAgainst(trace, planned).isNotEmpty()
    }

    /** True when every sample of [a] lies within [OFF_M] of [b]: the same way. */
    fun sameWay(a: List<LatLng>, b: List<LatLng>): Boolean =
        a.size >= 2 && b.size >= 2 && densify(a).all { distToLine(it.first, b) <= OFF_M }

    /** The line resampled every [STEP_M], each point with its distance along the line. */
    private fun densify(line: List<LatLng>): List<Pair<LatLng, Double>> {
        val out = mutableListOf(line[0] to 0.0)
        var along = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val d = dist(a, b)
            var t = STEP_M - (along % STEP_M)
            while (t < d) {
                val f = t / d
                out += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f) to along + t
                t += STEP_M
            }
            along += d
        }
        out += line.last() to along
        return out
    }

    private fun distToLine(p: LatLng, line: List<LatLng>): Double {
        var best = Double.MAX_VALUE
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_540.0
        for (i in 1 until line.size) {
            val ax = (line[i - 1].lng - p.lng) * kx; val ay = (line[i - 1].lat - p.lat) * ky
            val bx = (line[i].lng - p.lng) * kx; val by = (line[i].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + dx * t; val y = ay + dy * t
            val d = x * x + y * y
            if (d < best) best = d
        }
        return sqrt(best)
    }

    private fun dist(a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (a.lng - b.lng) * kx
        val dy = (a.lat - b.lat) * 110_540.0
        return sqrt(dx * dx + dy * dy)
    }
}
