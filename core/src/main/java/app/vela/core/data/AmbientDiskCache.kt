package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Slim on-disk form of the ambient-POI LRU, so the home area's dots paint instantly on a
 * COLD launch (the in-memory LRU already covers revisits within a session). Slim on purpose:
 * only the fields the dot layer, the prominence rank and a tappable place skeleton need -
 * names and positions age fine for days, the on-view refetch refreshes whatever the user
 * actually looks at, and the full [Place] (74 fields) would make the file megabytes.
 *
 * Lives in :core because the app module deliberately stays out of kotlinx.serialization
 * (the same boundary TransitParser keeps).
 */
@Serializable
data class AmbientCachedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: String? = null,
    val featureId: String? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val address: String? = null,
    // A live place-details fetch confirmed this business permanently closed. Persisted so the
    // verdict survives restarts: the dot stays hidden even when the row itself is still cached
    // (cache renders, network verifies - the tap's live answer is written back here).
    val closed: Boolean = false,
) {
    fun toPlace(): Place = Place(
        id = id, name = name, location = LatLng(lat, lng), category = category,
        featureId = featureId, rating = rating, reviewCount = reviewCount, address = address,
        permanentlyClosed = closed,
    )

    companion object {
        fun of(p: Place) = AmbientCachedPlace(
            p.id, p.name, p.location.lat, p.location.lng, p.category,
            p.featureId, p.rating, p.reviewCount, p.address,
            closed = p.permanentlyClosed,
        )
    }
}

@Serializable
data class AmbientCachedArea(
    val lat: Double,
    val lng: Double,
    val atWallMs: Long,
    val places: List<AmbientCachedPlace>,
    // Span (m) of the fetch this area came from - the cache-hit test is span-aware (a z14
    // fetch covers ~9 km; a fixed hit radius missed most legitimate revisits). Defaulted
    // so files written before the field existed still decode.
    val spanM: Double = 9000.0,
)

object AmbientDiskCache {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(areas: List<AmbientCachedArea>): String =
        json.encodeToString(ListSerializer(AmbientCachedArea.serializer()), areas)

    /** Null on any parse failure (corrupt/old format) - the caller just starts cold. */
    fun decode(raw: String): List<AmbientCachedArea>? =
        runCatching { json.decodeFromString(ListSerializer(AmbientCachedArea.serializer()), raw) }.getOrNull()
}
