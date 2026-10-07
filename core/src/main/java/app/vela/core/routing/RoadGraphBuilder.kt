package app.vela.core.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns decoded road polylines (from any map data source) into a [SsspGraph] by snapping
 * polyline endpoints. Map data encodes intersection points as IDENTICAL integer coordinates
 * in every road that meets there, so snapping at 1e-7 degrees is exact equality, not fuzzing:
 * two roads that truly cross share a vertex; two roads that merely look close do not.
 *
 * Coordinates are carried as E7 integers (degrees * 10^7, the OsmAnd/Google microdegree
 * convention) packed into one Long key: (latE7 << 32) | (lonE7 as unsigned 32).
 */
class RoadGraphBuilder {
    /** One decoded way: >= 2 points, per-hop travel times, direction flags. */
    class RoadPolyline(
        /** Packed E7 coordinate keys, in geometry order. */
        val pointKeys: LongArray,
        /** weightMs[i] = time from pointKeys[i] to pointKeys[i+1]; size == pointKeys.size-1. */
        val weightMs: LongArray,
        /** Per-hop times for the reverse direction when they differ (directional speed tags);
         *  null = reuse [weightMs]. */
        val backwardWeightMs: LongArray? = null,
        val forward: Boolean = true,
        val backward: Boolean = true,
    )

    /** The finished network plus the coordinate lookup needed for snapping and drawing. */
    class RoadNetwork(val graph: SsspGraph, val nodeKeys: LongArray) {
        fun latE7(node: Int): Int = (nodeKeys[node] shr 32).toInt()
        fun lonE7(node: Int): Int = nodeKeys[node].toInt()

        /**
         * Closest node within [maxRadiusE7] (1e-7 degrees; ~111 units ~= 10 m), or null.
         * Linear scan: the router runs it twice per route on a region-sized network.
         */
        fun nearestNode(latQueryE7: Int, lonQueryE7: Int, maxRadiusE7: Int, accept: (Int) -> Boolean = { true }): Int? {
            var best = -1
            var bestDist = Long.MAX_VALUE
            for (i in nodeKeys.indices) {
                val dy = latE7(i).toLong() - latQueryE7
                if (dy * dy > bestDist) continue
                val dx = lonE7(i).toLong() - lonQueryE7
                val d = dy * dy + dx * dx
                if (d < bestDist && accept(i)) { bestDist = d; best = i }
            }
            return if (best >= 0 && bestDist <= 1L * maxRadiusE7 * maxRadiusE7) best else null
        }
    }

    private val nodeId = HashMap<Long, Int>()
    private val nodeKeys = ArrayList<Long>()
    private val edges = ArrayList<Triple<Int, Int, Long>>()

    val nodeCount: Int get() = nodeKeys.size
    val addedEdges: Int get() = edges.size

    fun nodeOf(latE7: Int, lonE7: Int): Int {
        val key = packE7(latE7, lonE7)
        nodeId[key]?.let { return it }
        val id = nodeKeys.size
        nodeId[key] = id
        nodeKeys.add(key)
        return id
    }

    /** Adds one way. Consecutive identical points are skipped (degenerate hops). */
    fun addPolyline(p: RoadPolyline) {
        require(p.pointKeys.size >= 2) { "polyline needs >= 2 points" }
        require(p.weightMs.size == p.pointKeys.size - 1) { "weight count must match hops" }
        var prev = nodeOf(pointLat(p.pointKeys[0]), pointLon(p.pointKeys[0]))
        for (i in 1 until p.pointKeys.size) {
            val cur = nodeOf(pointLat(p.pointKeys[i]), pointLon(p.pointKeys[i]))
            if (cur != prev) {
                if (p.forward) edges.add(Triple(prev, cur, p.weightMs[i - 1]))
                if (p.backward) {
                    val bw = p.backwardWeightMs?.get(i - 1) ?: p.weightMs[i - 1]
                    edges.add(Triple(cur, prev, bw))
                }
            }
            prev = cur
        }
    }

    fun build(): RoadNetwork {
        val g = SsspGraph(nodeKeys.size)
        for ((from, to, w) in edges) g.addEdge(from, to, w)
        return RoadNetwork(g, nodeKeys.toLongArray())
    }

    companion object {
        fun packE7(latE7: Int, lonE7: Int): Long =
            (latE7.toLong() shl 32) or (lonE7.toLong() and 0xffffffffL)

        fun pointLat(key: Long): Int = (key shr 32).toInt()
        fun pointLon(key: Long): Int = key.toInt()

        /** Degrees to E7 integer. Deterministic both ways, so snap equality is exact. */
        fun toE7(degrees: Double): Int = (degrees * 1e7).roundToInt()
        fun toDegrees(e7: Int): Double = e7 / 1e7

        /** Great-circle meters between two E7 coordinates (pure Kotlin, no map-library dep). */
        fun distanceMeters(latE7A: Int, lonE7A: Int, latE7B: Int, lonE7B: Int): Double {
            val lat1 = Math.toRadians(toDegrees(latE7A))
            val lat2 = Math.toRadians(toDegrees(latE7B))
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(toDegrees(lonE7B - lonE7A))
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * 6_371_000.0 * atan2(sqrt(h), sqrt(1.0 - h))
        }

        /** Travel time for one hop at [speedKmh]; >= 1 ms so edges never weight-zero by speed. */
        fun weightMs(distanceMeters: Double, speedKmh: Double): Long {
            val mps = if (speedKmh > 0.5) speedKmh / 3.6 else 40.0 / 3.6 // 40 km/h street fallback
            return maxOf(1L, (distanceMeters / mps * 1000.0).roundToInt().toLong())
        }
    }
}
