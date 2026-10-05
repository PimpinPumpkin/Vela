package app.vela.ui.map

import app.vela.core.model.LatLng

/**
 * Where each route's time bubble goes in the route picker: on a stretch that is the route's OWN,
 * as far from the other routes as it gets, and clear of the bubbles already placed.
 *
 * Two alternates that share their first half used to end up with one bubble on the shared part:
 * the best spots of the second were inside the first one's keep-clear circle, and the search then
 * walked down the list into points both routes run through, which says nothing about which route
 * the time belongs to. Now a point on a shared stretch is never taken while the route has a
 * stretch of its own; the keep-clear distance gives way first.
 */
internal object RouteBubblePoints {
    fun of(polylines: List<List<LatLng>>): List<LatLng?> {
        fun sample(p: List<LatLng>, n: Int): List<LatLng> =
            if (p.size <= n) p else List(n) { p[(it.toLong() * (p.size - 1) / (n - 1)).toInt()] }
        val coarse = polylines.map { sample(it, 240) }
        val all = coarse.flatten()
        val diag = if (all.isEmpty()) 0.0 else distM(
            LatLng(all.minOf { it.lat }, all.minOf { it.lng }),
            LatLng(all.maxOf { it.lat }, all.maxOf { it.lng }),
        )
        val minGap = maxOf(300.0, diag * 0.25) // a bubble is about an eighth of the fitted route wide
        val ownM = maxOf(OWN_MIN_M, diag * OWN_FRACTION)
        val placed = ArrayList<LatLng>()
        return polylines.mapIndexed { i, poly ->
            if (poly.size < 2) return@mapIndexed null
            val cand = sample(poly, 60).let { c -> if (c.size > 10) c.subList(c.size / 10, c.size - c.size / 10) else c }
            val others = coarse.filterIndexed { j, _ -> j != i }.flatten()
            if (others.isEmpty()) return@mapIndexed poly[poly.size / 2].also { placed += it }
            val scored = cand.map { p -> p to others.minOf { distM(p, it) } }.sortedByDescending { it.second }
            val own = scored.filter { it.second >= ownM }.map { it.first }
            val pool = own.ifEmpty { scored.map { it.first } }
            val at = GAP_STEPS.firstNotNullOfOrNull { f -> pool.firstOrNull { p -> placed.none { distM(p, it) < minGap * f } } } ?: pool.first()
            placed += at
            at
        }
    }

    internal fun distM(a: LatLng, b: LatLng): Double {
        val dy = (a.lat - b.lat) * 111_320.0
        val dx = (a.lng - b.lng) * 111_320.0 * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** A point this far from every other route is on the route's own stretch. */
    private const val OWN_MIN_M = 150.0
    private const val OWN_FRACTION = 0.02
    /** The keep-clear circle shrinks in these steps before a shared point is ever accepted. */
    private val GAP_STEPS = listOf(1.0, 0.5, 0.25, 0.0)
}
