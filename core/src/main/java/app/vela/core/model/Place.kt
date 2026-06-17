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
    val hours: List<String> = emptyList(),
    val photoUrls: List<String> = emptyList(),
    val featuredReview: String? = null, // Google's single highlighted review snippet
    val featureId: String? = null,      // Google feature id "0x..:0x.." → reviews RPC
    val placeId: String? = null,        // "ChIJ..." place id (for deep links)
    val about: List<AboutSection> = emptyList(),
    val distanceMeters: Double? = null, // filled when searched relative to a point
)

/** One section of Google's "About" panel, e.g. title="Service options",
 *  items=["Outdoor seating","Takeout","Dine-in"]. */
data class AboutSection(val title: String, val items: List<String>)

/** A single user review. [rating] is 1..5; [text] is null for rating-only reviews. */
data class Review(
    val author: String,
    val authorPhoto: String?,
    val rating: Int,
    val relativeTime: String?,
    val text: String?,
)

data class SearchResult(
    val query: String,
    val places: List<Place>,
)
