package app.vela.offline

/**
 * Which catalog region answers for a point when a download is about to be offered or started.
 *
 * The smallest covering region is the specific one: boxes overlap at borders, and a state can be
 * listed whole and in parts. A bigger covering region that is already on the phone holds the same
 * ground, so it answers instead: a phone with all of Texas is not offered North Texas on top.
 */
object RegionPick {
    data class Pick<T>(val region: T, val installed: Boolean)

    /** [covering]: every region that holds the point. Null when none does. */
    fun <T> of(covering: List<T>, area: (T) -> Double, installed: (T) -> Boolean): Pick<T>? {
        val smallest = covering.minByOrNull(area) ?: return null
        if (installed(smallest)) return Pick(smallest, true)
        val have = covering.filter(installed).minByOrNull(area)
        return if (have != null) Pick(have, true) else Pick(smallest, false)
    }

    fun routing(regions: List<RoutingRegion>, lat: Double, lng: Double, installedIds: Set<String>): Pick<RoutingRegion>? =
        of(regions.filter { it.covers(lat, lng) }, { it.boxArea() }) { it.id in installedIds }

    /** A places or basemap archive. [skip]: ids that never stand in for a region (the low-zoom
     *  world basemap covers every point and holds no streets). */
    fun archive(
        regions: List<PmtilesRegionStore.Region>,
        lat: Double,
        lng: Double,
        installedIds: Set<String>,
        skip: Set<String> = emptySet(),
    ): Pick<PmtilesRegionStore.Region>? =
        of(regions.filter { it.id !in skip && it.covers(lat, lng) }, { it.area() }) { it.id in installedIds }
}
