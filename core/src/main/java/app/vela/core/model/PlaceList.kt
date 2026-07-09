package app.vela.core.model

import kotlinx.serialization.Serializable

/** One place inside a user list — enough to render, route, and carry the owner's note. */
@Serializable
data class ListPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val note: String? = null,
    val featureId: String? = null,
) {
    val location: LatLng get() = LatLng(lat, lng)

    fun toPlace(): Place = Place(
        id = id,
        name = name,
        location = LatLng(lat, lng),
        address = address,
        featureId = featureId,
        savedNote = note,
    )

    companion object {
        fun of(p: Place) = ListPlace(
            id = p.id,
            name = p.name,
            lat = p.location.lat,
            lng = p.location.lng,
            address = p.address,
            note = p.savedNote,
            featureId = p.featureId,
        )
    }
}

/** A user-created (or imported) list of places — Google-Maps "saved lists" (issue #1).
 *  [icon] is a stable key into the app's icon set; [color] an ARGB int the UI tints with. */
@Serializable
data class PlaceList(
    val id: String,
    val name: String,
    val icon: String = "bookmark",
    val color: Long = 0xFF1A73E8, // Google blue by default
    val description: String? = null,
    val places: List<ListPlace> = emptyList(),
)
