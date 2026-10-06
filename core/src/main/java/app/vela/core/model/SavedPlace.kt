package app.vela.core.model

import kotlinx.serialization.Serializable

/** A lightweight, persistable favorite — enough to recenter + re-route to it. */
@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    // Defaulted so payloads saved before it existed still decode (every store's Json also sets
    // ignoreUnknownKeys, so an older build reading a newer payload survives too).
    val address: String? = null,
    // The place's own map icon (issue #629), same keys as PlaceList.icon ("emoji:X" or a glyph
    // key); null draws the default. Defaulted, so older payloads decode.
    val icon: String? = null,
    /** Shown on the search page (issue #622 follow-up); unpinned ones live in the Saved sheet. */
    val pinned: Boolean = false,
    /** A label on a point, not a listing: a contact's address under the contact's name. Opening it
     *  again shows exactly this name and address and never searches the name, which would open
     *  whatever business near that address the name happens to resemble. Defaulted, so older payloads decode. */
    val bare: Boolean = false,
) {
    val location: LatLng get() = LatLng(lat, lng)

    /** A pin or an address rather than a listing: marked [bare], a dropped pin's id, or a name
     *  that is the first line of its own address (how one saved before the mark existed reads). */
    val isPoint: Boolean get() = bare || id.startsWith("pin:") ||
        (address != null && name.isNotBlank() && address.trim().startsWith(name.trim(), ignoreCase = true))

    fun toPlace(): Place = Place(id = id, name = name, location = location, address = address)

    companion object {
        fun of(p: Place) = SavedPlace(p.id, p.name, p.location.lat, p.location.lng, p.address)
    }
}
