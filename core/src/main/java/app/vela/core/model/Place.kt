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
    val distanceMeters: Double? = null, // filled when searched relative to a point
)

data class SearchResult(
    val query: String,
    val places: List<Place>,
)
