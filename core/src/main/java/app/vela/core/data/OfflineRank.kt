package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo

/**
 * Matching and ranking for offline search, shared by the place packs ([OfflinePoiStore], OSM) and
 * the downloaded places archives the map draws (Overture, AllThePlaces, OSM), so a result from
 * either source is judged the same way.
 */
object OfflineRank {
    /** A category query (the map's chips, "restaurants", "gas") keeps to results this close to the
     *  search point: with no place pack for the area, "Restaurants" used to list a downloaded state
     *  on the far side of the country. A name search is never cut. */
    const val CATEGORY_MAX_M = 100_000.0

    /** Two rows this close with the same name are one place seen by two sources. */
    private const val SAME_PLACE_M = 120.0

    fun isCategoryQuery(query: String): Boolean = OfflinePoiStore.categoryKeywords(query.trim()).isNotEmpty()

    /** The same test the pack SQL runs: the query (or, multi-word, any word of 3+ letters) in the
     *  name or category, a category keyword in the category, or the whole query in the address. */
    fun matches(query: String, name: String, category: String?, address: String?, brand: String? = null): Boolean {
        val term = query.trim().lowercase()
        if (term.isEmpty()) return false
        val n = name.lowercase()
        val c = category?.lowercase().orEmpty()
        val words = term.split(Regex("\\s+")).filter { it.length >= 3 }
        val targets = if (words.size > 1) listOf(term) + words else listOf(term)
        if (targets.any { n.contains(it) || c.contains(it) || brand?.lowercase()?.contains(it) == true }) return true
        val cats = OfflinePoiStore.categoryKeywords(term) + words.flatMap { OfflinePoiStore.categoryKeywords(it) }
        if (cats.any { c.contains(it) }) return true
        return address?.lowercase()?.contains(term) == true
    }

    /** Transit stops last (unless the query asks for transit), then the rows that answer more of
     *  the query, then the nearest. A category row answers its word too: "restaurants" is not a
     *  substring of the category "Restaurant", so before this every chip result tied and far
     *  name matches ("... Restaurants") could lead. */
    fun rank(query: String, near: LatLng?, rows: List<Place>, limit: Int): List<Place> {
        val term = query.trim()
        val words = term.split(Regex("\\s+")).filter { it.length >= 3 }
        val qWords = (if (words.size > 1) words else listOf(term)).map { it.lowercase() }
        val transitQuery = OfflinePoiStore.TRANSIT_QUERY_WORDS.any { term.lowercase().contains(it) }
        val categoryQuery = isCategoryQuery(term)
        val withDist = rows.map { p -> if (near != null) p.copy(distanceMeters = near.distanceTo(p.location)) else p }
            .filter { p -> !categoryQuery || near == null || (p.distanceMeters ?: 0.0) <= CATEGORY_MAX_M }
        val kept = ArrayList<Place>()
        val seenIds = HashSet<String>()
        for (p in withDist) {
            if (!seenIds.add(p.id)) continue
            val key = p.name.trim().lowercase()
            if (kept.any { it.name.trim().lowercase() == key && it.location.distanceTo(p.location) < SAME_PLACE_M }) continue
            kept.add(p)
        }
        return kept.sortedWith(
            compareBy<Place> { p -> if (!transitQuery && (p.category ?: "").lowercase() in OfflinePoiStore.TRANSIT_STOP_CATS) 1 else 0 }
                .thenByDescending { p ->
                    val hay = (p.name + " " + (p.category ?: "") + " " + (p.address ?: "")).lowercase()
                    val cat = (p.category ?: "").lowercase()
                    qWords.count { w -> hay.contains(w) || OfflinePoiStore.categoryKeywords(w).any { cat.contains(it) } }
                }.thenBy { it.distanceMeters ?: Double.MAX_VALUE },
        ).take(limit)
    }
}
