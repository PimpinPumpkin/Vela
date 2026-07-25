package app.vela.core.data

import app.vela.core.model.LatLng

/**
 * Greedy centroid clustering for point declutter on the map. OSM maps street furniture per
 * APPROACH: a four-way stop is four `highway=stop` nodes and a signalized junction can carry
 * eight `traffic_signals` nodes, where Google draws ONE glyph per intersection - the same
 * per-carriageway reality `RouteGeometry.enrichWithLights` already clusters at 30 m before
 * counting "pass the light". Flock installs likewise mount several single-direction heads on
 * one corner. Collapsing them before upload is also a straight render win: these layers draw
 * with iconAllowOverlap, so every node becomes quads with no collision culling to save us.
 *
 * Greedy by input order: each point joins the first cluster whose running centroid lies within
 * [radiusM], else starts its own. Deterministic for a given input order; O(n * clusters), fine
 * for the few hundred points a viewport carries.
 */
object MapDeclutter {

    class Cluster<T>(first: T, firstLoc: LatLng) {
        val members = mutableListOf(first)
        var centroid: LatLng = firstLoc
            private set

        fun add(item: T, loc: LatLng) {
            members.add(item)
            val n = members.size
            centroid = LatLng(
                centroid.lat + (loc.lat - centroid.lat) / n,
                centroid.lng + (loc.lng - centroid.lng) / n,
            )
        }
    }

    fun <T> cluster(items: List<T>, radiusM: Double, loc: (T) -> LatLng): List<Cluster<T>> {
        val clusters = mutableListOf<Cluster<T>>()
        for (item in items) {
            val p = loc(item)
            val hit = clusters.firstOrNull { approxDistanceM(it.centroid, p) <= radiusM }
            if (hit != null) hit.add(item, p) else clusters.add(Cluster(item, p))
        }
        return clusters
    }

    /** Equirectangular approximation - exact enough at the tens-of-metres radii this is used at. */
    internal fun approxDistanceM(a: LatLng, b: LatLng): Double {
        val latM = (a.lat - b.lat) * 111_320.0
        val lngM = (a.lng - b.lng) * 111_320.0 * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return kotlin.math.sqrt(latM * latM + lngM * lngM)
    }
}
