package app.vela.core.model

/**
 * A point of interest. Fields are nullable because no single source fills all
 * of them — Overture/OSM give the geometry + category, the scraped detail page
 * adds rating/hours/phone. Vela merges whatever it can get.
 */
data class Place(
    val id: String,
    val name: String,
    val location: LatLng,
    val category: String? = null,
    val address: String? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val priceLevel: Int? = null,   // 0..4, Google-style ($ to $$$$)
    val priceText: String? = null, // Google's own label, e.g. "$1–10" / "$$"
    val phone: String? = null,
    val website: String? = null,
    val openNow: Boolean? = null,
    val statusText: String? = null, // Google's own status, e.g. "Open · Closes 9 PM"
    val permanentlyClosed: Boolean = false, // dead POI — still searchable, hidden from the map

    val hours: List<String> = emptyList(),
    val photoUrls: List<String> = emptyList(),
    val featuredReview: String? = null, // Google's single highlighted review snippet
    val featureId: String? = null,      // Google feature id "0x..:0x.." → reviews RPC
    val placeId: String? = null,        // "ChIJ..." place id (for deep links)
    val about: List<AboutSection> = emptyList(),
    val editorialSummary: String? = null,   // Google's one-line description ("Classic burger chain serving…")
    val ownerDescription: String? = null,   // "From the owner" — the business's own longer blurb
    val popularTimes: PopularTimes? = null, // Google's "popular times" histogram
    val distanceMeters: Double? = null, // filled when searched relative to a point
)

/** Google's "popular times": a typical-busyness histogram per day of the week. */
data class PopularTimes(val days: List<DayBusyness>)

/** The rich fields the keyless/list search trims out, fetched lazily through the
 *  hidden WebView (see app `WebPopularTimesFetcher`): popular times plus the
 *  editorial one-liner and the owner's "From the owner" blurb. */
data class PlaceDetails(
    val popularTimes: PopularTimes? = null,
    val editorialSummary: String? = null,
    val ownerDescription: String? = null,
) {
    val isEmpty: Boolean get() = popularTimes == null && editorialSummary == null && ownerDescription == null
}

/** One day: [dayOfWeek] is 1=Mon … 7=Sun; [hours] are the open-hour buckets. */
data class DayBusyness(val dayOfWeek: Int, val hours: List<HourBusyness>)

/** [hour] is 0..23; [occupancy] is the typical busyness 0..100. */
data class HourBusyness(val hour: Int, val occupancy: Int)

/** One section of Google's "About" panel, e.g. title="Service options",
 *  items=["Outdoor seating","Takeout","Dine-in"]. */
data class AboutSection(val title: String, val items: List<String>)

/** A single user review. [rating] is 1..5; [text] is null for rating-only reviews;
 *  [photos] are user-attached photo URLs (thumbnail-sized), empty when none. */
data class Review(
    val author: String,
    val authorPhoto: String?,
    val rating: Int,
    val relativeTime: String?,
    val text: String?,
    val photos: List<String> = emptyList(),
)

data class SearchResult(
    val query: String,
    val places: List<Place>,
)
