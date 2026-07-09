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
    val actionLabel: String? = null,  // Google's action button text ("Book online", "Reserve a table", "Order online")
    val actionUrl: String? = null,    // the link that button opens
    val openNow: Boolean? = null,
    val statusText: String? = null, // Google's own status, e.g. "Open · Closes 9 PM"
    val permanentlyClosed: Boolean = false, // dead POI — still searchable, hidden from the map
    val temporarilyClosed: Boolean = false, // owner-set temporary closure — STAYS on the map, but the UI
                                            // banners it and suppresses the (now-misleading) weekly hours,
                                            // Google-style ("resilient when the owner updates the notice")

    val hours: List<String> = emptyList(),
    // In-store departments with their OWN schedules (a grocery store's pharmacy, fuel
    // station, liquor counter, delivery/pickup windows) — Google's [118] list. Names have
    // the redundant store prefix stripped ("Safeway Pharmacy" → "Pharmacy").
    val departments: List<Department> = emptyList(),
    // The owner's personal note on a place imported from a Google Maps shared list
    // ("this restaurant's fish is better than its chicken") — shown on the sheet.
    val savedNote: String? = null,
    val photoUrls: List<String> = emptyList(),
    // "Posted" label per gallery photo ("May 2026"), index-aligned with [photoUrls].
    // Empty for the search-response preview; the WebView gallery fills it in (the dates
    // live in the gallery RPC, not the search response). Read by the full-screen viewer.
    val photoDates: List<String?> = emptyList(),
    // Gallery category per photo ("Menu" / "Food & drink" / "Vibe" / "By owner" / null = All),
    // index-aligned with [photoUrls]. Filled by the WebView gallery scrape; drives the gallery's
    // category filter chips.
    val photoCategories: List<String?> = emptyList(),
    val featuredReview: String? = null, // Google's single highlighted review snippet
    val featureId: String? = null,      // Google feature id "0x..:0x.." → reviews RPC
    val placeId: String? = null,        // "ChIJ..." place id (for deep links)
    val about: List<AboutSection> = emptyList(),
    val editorialSummary: String? = null,   // Google's one-line description ("Classic burger chain serving…")
    val ownerDescription: String? = null,   // "From the owner" — the business's own longer blurb
    val popularTimes: PopularTimes? = null, // Google's "popular times" histogram
    val similarPlaces: List<SimilarPlace> = emptyList(), // "People also search for"
    val distanceMeters: Double? = null, // filled when searched relative to a point
)

/** A "People also search for" entry — a related place, enough to show a card and open it. */
data class SimilarPlace(
    val name: String,
    val location: LatLng,
    val rating: Double? = null,
    val featureId: String? = null,
)

/** One gallery photo: its FIFE image [url], a human "posted" label ("May 2026") when known,
 *  and the gallery-tab [category] it was scraped under ("Menu" / "Food & drink" / "Vibe" /
 *  "By owner"; null = uncategorized/All). The contributor's *name* isn't in the keyless
 *  gallery (only a date + source), so there's no author field here. */
data class Photo(val url: String, val postedText: String? = null, val category: String? = null)

/** Google's "popular times": a typical-busyness histogram per day of the week. */
data class PopularTimes(val days: List<DayBusyness>)

/** The rich fields the keyless/list search trims out, fetched lazily through the
 *  hidden WebView (see app `WebPopularTimesFetcher`): popular times plus the
 *  editorial one-liner and the owner's "From the owner" blurb. */
data class PlaceDetails(
    val popularTimes: PopularTimes? = null,
    val editorialSummary: String? = null,
    val ownerDescription: String? = null,
    // Backfill fields. The address→business snap (a suite/multi-tenant address) returns a
    // lightweight *summary* node that omits the review count, the full weekly hours, the
    // address, etc. The focused name+address re-fetch (this same WebView call) carries the
    // FULL node, so lift these off too and merge into any field the summary left blank.
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val hours: List<String> = emptyList(),
    val address: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val statusText: String? = null,
    val openNow: Boolean? = null,
    val priceText: String? = null,
    val priceLevel: Int? = null,
    val about: List<AboutSection> = emptyList(),
    val featuredReview: String? = null,
) {
    val isEmpty: Boolean get() = popularTimes == null && editorialSummary == null && ownerDescription == null &&
        rating == null && reviewCount == null && hours.isEmpty() && address == null && phone == null &&
        website == null && statusText == null && openNow == null && priceText == null && priceLevel == null &&
        about.isEmpty() && featuredReview == null
}

/** One in-store department's schedule: [hours] shaped like [Place.hours] (7 day strings
 *  starting today), [statusText] Google's own line ("Closed · Opens 9 AM Thu"),
 *  [openNow] parsed from that text (null when it is ambiguous). */
/** A Google Maps shared list pulled through the keyless getlist RPC (issue #1):
 *  the list's [title]/[description]/[author] plus its [places], each carrying the
 *  owner's personal note in [Place.savedNote]. */
data class ImportedList(
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val places: List<Place> = emptyList(),
)

data class Department(
    val name: String,
    val hours: List<String> = emptyList(),
    val statusText: String? = null,
    val openNow: Boolean? = null,
)

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
