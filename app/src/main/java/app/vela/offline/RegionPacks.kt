package app.vela.offline

/**
 * Which place pack serves a routing region. Most regions have a pack of their own id, but a
 * country or state split into routing pieces has ONE pack for the whole: "Northern California
 * (California)" is served by the "california" pack, "Bayern (Germany)" by "germany". Looking only
 * for the region's own id, 288 of 447 regions had no pack, so "Get places" did nothing on them and
 * offline search and the offline address lookup had nothing there.
 *
 * The parent is named in the region's name, in parentheses, and must also cover the region's
 * center: a box test alone would hand Andorra the Spain pack, which holds none of Andorra.
 */
object RegionPacks {
    /** A shared parent pack at most this big comes with a region download on its own; a bigger
     *  one (Germany's is 1.9 GB) waits for "Get places", which shows its size first. */
    const val AUTO_PARENT_MAX_MB = 600

    private val PARENT = Regex("\\(([^()]+)\\)\\s*$")

    /** [installed]: pack ids on the phone. A piece that has its own pack now but whose parent's
     *  pack is the one installed keeps using the parent (it covers the piece) instead of asking
     *  for a second download of the same places. */
    fun packFor(region: RoutingRegion, packs: List<RoutingRegion>, installed: Set<String> = emptySet()): RoutingRegion? {
        val own = packs.firstOrNull { it.id == region.id }
        if (own != null && (own.id in installed || installed.isEmpty())) return own
        val parent = PARENT.find(region.name)?.groupValues?.get(1)?.trim()?.let { name ->
            val lat = (region.s + region.n) / 2
            val lng = (region.w + region.e) / 2
            packs.filter { p ->
                p.name.substringBefore(" (").trim().equals(name, ignoreCase = true) &&
                    lat in p.s..p.n && lng in p.w..p.e
            }.minByOrNull { it.boxArea() }
        }
        return if (own != null && parent?.id !in installed) own else parent ?: own
    }

    /** The pack is the whole parent's, shared with the region's siblings. */
    fun isShared(region: RoutingRegion, pack: RoutingRegion) = pack.id != region.id

    /** Whether a region download should bring [pack] along without asking. */
    fun autoWith(region: RoutingRegion, pack: RoutingRegion) = !isShared(region, pack) || pack.sizeMb <= AUTO_PARENT_MAX_MB
}
