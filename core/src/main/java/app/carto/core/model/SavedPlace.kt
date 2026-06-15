package app.carto.core.model

import kotlinx.serialization.Serializable

/** A lightweight, persistable favourite — enough to recenter + re-route to it. */
@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
) {
    val location: LatLng get() = LatLng(lat, lng)

    companion object {
        fun of(p: Place) = SavedPlace(p.id, p.name, p.location.lat, p.location.lng)
    }
}
