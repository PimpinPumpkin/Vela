package app.vela.core.model

import kotlinx.serialization.Serializable

/** A lightweight, persistable favourite — enough to recenter + re-route to it. */
@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    // Defaulted so payloads saved before it existed still decode (every store's Json also sets
    // ignoreUnknownKeys, so an older build reading a newer payload survives too).
    val address: String? = null,
) {
    val location: LatLng get() = LatLng(lat, lng)

    companion object {
        fun of(p: Place) = SavedPlace(p.id, p.name, p.location.lat, p.location.lng, p.address)
    }
}
