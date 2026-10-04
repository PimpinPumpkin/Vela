package app.vela.core.util

import kotlin.math.abs
import kotlin.math.cos

/** Length and area of a drawn shape (a custom map's line or area, issue #669). [pts] is
 *  lat, lng, lat, lng... A local flat projection: exact enough for anything a person draws on a
 *  map, from a yard to a county. */
object ShapeMeasure {
    private const val M_PER_DEG = 111_320.0

    private fun xy(pts: List<Double>): List<Pair<Double, Double>> {
        if (pts.size < 2) return emptyList()
        val lat0 = pts[0]
        val k = cos(Math.toRadians(lat0))
        return pts.chunked(2).mapNotNull { if (it.size == 2) ((it[1] - pts[1]) * M_PER_DEG * k) to ((it[0] - lat0) * M_PER_DEG) else null }
    }

    /** Along the line, in meters; for an area, its perimeter (the ring is closed). */
    fun lengthM(pts: List<Double>, closed: Boolean = false): Double {
        val p = xy(pts)
        val ring = if (closed && p.size > 2 && p.first() != p.last()) p + p.first() else p
        return ring.zipWithNext { a, b -> Math.hypot(b.first - a.first, b.second - a.second) }.sum()
    }

    /** Enclosed area in square meters (shoelace). */
    fun areaM2(pts: List<Double>): Double {
        val p = xy(pts)
        if (p.size < 3) return 0.0
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.first * b.second - b.first * a.second }
        return abs(s) / 2
    }
}
