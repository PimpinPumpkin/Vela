package app.vela.core.nav

import app.vela.core.model.LatLng

/**
 * The sharp corners of a route line: where the line's bearing over the [LEG_M] before a vertex
 * and the [LEG_M] after it differ by [CORNER_DEG] or more. A street corner, a hairpin or a
 * U-turn is one; a freeway ramp or fork bends too gently to be. Vertices of one corner within
 * [MERGE_M] of each other are one corner, at the sharpest of them, with the bearing in from the
 * first and out from the last. The bearings in and out are read on the [SIDE_M] of line just
 * beyond [LEG_M] either side, the roads themselves: the drawn line rounds a corner into a curve
 * ([RouteSmoothing.roundCorners]), and a chord to its middle leaned into the turn.
 *
 * The drive arrow waits at such a corner while the gyroscope says the car is not turning
 * (SPEC 4.7, Corner hold).
 */
object RouteCorners {
    /** A corner at [pt], [atM] meters along the line, the bearing into it [inDeg] and out of it
     *  [outDeg]; [turnDeg] is signed, positive to the right. */
    data class Corner(val atM: Double, val inDeg: Double, val outDeg: Double, val turnDeg: Double, val pt: LatLng)

    fun find(poly: List<LatLng>, cum: DoubleArray): List<Corner> {
        if (poly.size < 3) return emptyList()
        val out = ArrayList<Corner>()
        var sharpest = 0.0 // how sharply the last corner's own vertex turns
        for (i in 1 until poly.size - 1) {
            val m = cum[i]
            if (m < LEG_M || cum.last() - m < LEG_M) continue
            val a = RouteProjection.pointAt(poly, cum, m - LEG_M)
            val c = RouteProjection.pointAt(poly, cum, m + LEG_M)
            val here = kotlin.math.abs(signedDelta(bearing(a, poly[i]), bearing(poly[i], c)))
            if (here < CORNER_DEG) continue
            val inDeg = bearing(RouteProjection.pointAt(poly, cum, m - LEG_M - SIDE_M), a)
            val outDeg = bearing(c, RouteProjection.pointAt(poly, cum, m + LEG_M + SIDE_M))
            val prev = out.lastOrNull()
            if (prev != null && m - prev.atM <= MERGE_M) {
                val turnAll = signedDelta(prev.inDeg, outDeg)
                out[out.size - 1] = if (here > sharpest) Corner(m, prev.inDeg, outDeg, turnAll, poly[i])
                    else prev.copy(outDeg = outDeg, turnDeg = turnAll)
                if (here > sharpest) sharpest = here
            } else {
                out.add(Corner(m, inDeg, outDeg, signedDelta(inDeg, outDeg), poly[i]))
                sharpest = here
            }
        }
        return out
    }

    /**
     * Whether the car is past [c]: the engine puts it more than [PASSED_FAR_M] beyond it, or more
     * than [PASSED_M] beyond it with the last fix [fix] at least [PASSED_M] out along the road the
     * corner turns onto. The engine's own position keeps moving along the route for a few meters
     * while a car goes straight on, so it alone does not say the car turned.
     */
    fun passed(c: Corner, alongM: Double, fix: LatLng?): Boolean =
        alongM > c.atM + PASSED_FAR_M ||
            (alongM > c.atM + PASSED_M && fix != null && outAlong(c, fix) >= PASSED_M)

    /** How far [p] is past [c] along the bearing out of it, meters (negative before it). */
    fun outAlong(c: Corner, p: LatLng): Double {
        val dy = (p.lat - c.pt.lat) * M_PER_DEG
        val dx = (p.lng - c.pt.lng) * M_PER_DEG * kotlin.math.cos(Math.toRadians(c.pt.lat))
        val r = Math.toRadians(c.outDeg)
        return dx * kotlin.math.sin(r) + dy * kotlin.math.cos(r)
    }

    /** Bearing from [a] to [b], degrees 0-359, on a local flat frame. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
    }

    /** [to] minus [from], -180 to 180, positive clockwise. */
    fun signedDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    const val CORNER_DEG = 35.0
    const val LEG_M = 12.0
    const val SIDE_M = 6.0
    const val MERGE_M = 15.0
    const val PASSED_M = 4.0
    const val PASSED_FAR_M = 60.0
    private const val M_PER_DEG = 111_320.0
}
