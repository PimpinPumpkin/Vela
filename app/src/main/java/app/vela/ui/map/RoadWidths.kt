package app.vela.ui.map

/**
 * Road line widths by class and zoom, in dp ([widenStreets] turns them into style expressions).
 *
 * Liberty grows a road's line 1.2x per zoom level while the map itself doubles, so the closer you
 * zoom the thinner every street gets next to the buildings beside it. Each class gets a real-world
 * width ([WIDTH_M]) drawn at whichever is wider, that or its base curve ([BASE], so the overview
 * zooms keep Liberty's look), capped at [CAP_DP]. Meters become dp at 40 degrees latitude
 * (MapLibre's 512 px tiles: 78271.517 x cos(lat) / 2^z meters per dp); a style expression cannot
 * read the latitude, and 40 is the middle of where most people live. A casing keeps the base
 * border (casing minus fill) around the wider fill, and at least the road-edge floor.
 *
 * Minor streets (residential, unclassified, living streets) stay at most about 0.6 of a secondary
 * or tertiary road at every zoom, so the main roads stand out from the grid (issue #862).
 * `RoadWidthsTest` holds the order of the classes and that ratio.
 */
internal object RoadWidths {
    /** Base curves (exponential 1.2), fill then casing, dp. Liberty's own, except minor.
     *  "minor" is "street" on bridges. */
    val BASE: Map<String, Pair<FloatArray, FloatArray>> = mapOf(
        "minor" to (floatArrayOf(12.5f, 0f, 13f, 0.8f, 14f, 1.6f, 15f, 2.6f, 16f, 4.2f, 20f, 12f) to floatArrayOf(12f, 0.5f, 13f, 1.4f, 14f, 2.5f, 15f, 3.6f, 16f, 5.2f, 20f, 13.5f)),
        "service_track" to (floatArrayOf(15.5f, 0f, 16f, 2f, 20f, 7.5f) to floatArrayOf(15f, 1f, 16f, 4f, 20f, 11f)),
        "link" to (floatArrayOf(12.5f, 0f, 13f, 1.5f, 14f, 2.5f, 20f, 11.5f) to floatArrayOf(12f, 1f, 13f, 3f, 14f, 4f, 20f, 15f)),
        "motorway_link" to (floatArrayOf(12.5f, 0f, 13f, 1.5f, 14f, 2.5f, 20f, 11.5f) to floatArrayOf(12f, 1f, 13f, 3f, 14f, 4f, 20f, 15f)),
        "secondary_tertiary" to (floatArrayOf(6.5f, 0f, 8f, 0.5f, 20f, 13f) to floatArrayOf(8f, 1.5f, 20f, 17f)),
        "trunk_primary" to (floatArrayOf(5f, 0f, 7f, 1f, 20f, 18f) to floatArrayOf(5f, 0.4f, 6f, 0.7f, 7f, 1.75f, 20f, 22f)),
        "motorway" to (floatArrayOf(5f, 0f, 7f, 1f, 20f, 18f) to floatArrayOf(5f, 0.4f, 6f, 0.7f, 7f, 1.75f, 20f, 22f)),
    )

    /** Typical carriageway width per class, meters. */
    val WIDTH_M: Map<String, Double> = mapOf(
        "minor" to 7.5, "service_track" to 3.5, "link" to 8.0, "motorway_link" to 8.0,
        "secondary_tertiary" to 13.0, "trunk_primary" to 17.0, "motorway" to 22.0,
    )

    /** One carriageway of a divided road (OMT `oneway` set), meters. Minor and service roads keep their
     *  width: a one-way street there is a whole street, not half a road. */
    val ONEWAY_WIDTH_M: Map<String, Double> = mapOf(
        "link" to 6.0, "motorway_link" to 6.0,
        "secondary_tertiary" to 9.0, "trunk_primary" to 11.0, "motorway" to 13.0,
    )

    val CAP_DP: Map<String, Double> = mapOf(
        "minor" to 26.0, "service_track" to 14.0, "link" to 28.0, "motorway_link" to 28.0,
        "secondary_tertiary" to 42.0, "trunk_primary" to 52.0, "motorway" to 62.0,
    )

    /** Each road edge's width from street zoom in, dp (Google's: 2 to 3 px on a 2.75x screen). */
    const val ROAD_EDGE_DP = 1.0

    /** Meters per dp at zoom 0 and 40 degrees latitude (MapLibre's 512 px tiles). */
    val M_PER_DP_Z0 = 78271.517 * Math.cos(Math.toRadians(40.0))

    fun expInterp(stops: FloatArray, z: Double, base: Double = 1.2): Double {
        if (z <= stops[0]) return stops[1].toDouble()
        var i = 0
        while (i + 2 < stops.size) {
            val z0 = stops[i].toDouble(); val v0 = stops[i + 1].toDouble()
            val z1 = stops[i + 2].toDouble(); val v1 = stops[i + 3].toDouble()
            if (z <= z1) {
                val t = (Math.pow(base, z - z0) - 1) / (Math.pow(base, z1 - z0) - 1)
                return v0 + (v1 - v0) * t
            }
            i += 2
        }
        return stops[stops.size - 1].toDouble()
    }

    /** [meters] as dp at zoom [z]. */
    fun dp(meters: Double, z: Double): Double = meters / (M_PER_DP_Z0 / Math.pow(2.0, z))

    /** Both road edges together, dp: none until z13.5, the full pair from z16. */
    fun edgeFloor(z: Double, edges: Boolean): Double =
        if (!edges) 0.0 else ((z - 13.5) / 2.5).coerceIn(0.0, 1.0) * ROAD_EDGE_DP * 2

    /** The fill of class [cls] at zoom [z], for a real width of [meters] (already scaled). */
    fun fill(cls: String, z: Double, meters: Double): Double {
        val base = expInterp(BASE.getValue(cls).first, z)
        if (base <= 0.0 || meters <= 0.0) return base
        return maxOf(base, minOf(CAP_DP.getValue(cls), dp(meters, z)))
    }

    /** The casing of class [cls] at zoom [z]: the fill plus the base border, never under the edge floor. */
    fun casing(cls: String, z: Double, meters: Double, edges: Boolean): Double {
        val (fillBase, casingBase) = BASE.getValue(cls)
        val baseFill = expInterp(fillBase, z)
        if (baseFill <= 0.0) return expInterp(casingBase, z)
        val border = maxOf((expInterp(casingBase, z) - baseFill).coerceAtLeast(0.0), edgeFloor(z, edges))
        return fill(cls, z, meters) + border
    }
}
