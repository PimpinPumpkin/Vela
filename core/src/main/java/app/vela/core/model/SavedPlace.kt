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

    /** Starred from a basemap label, under the label's own name, before its listing was known:
     *  marked [bare] like a pin, but a place to look up when it is opened online. One renamed
     *  since no longer matches the label's id and stays a label of the user's own. */
    val awaitsListing: Boolean get() = bare && id == LabelPlace.basemapId(name)

    /** This place once [listing] is known for it: the listing's point and address, and no longer
     *  a point. The name, icon and pin stay as saved. */
    fun linked(listing: Place): SavedPlace = copy(
        lat = listing.location.lat, lng = listing.location.lng,
        address = listing.address?.ifBlank { null } ?: address, bare = false,
    )

    /** Whether this is the star put on [p]'s own sheet (same id, same name) while it was kept as
     *  a point. A contact's label on a business's address has another name and is not. */
    fun isStarOf(p: Place): Boolean = bare && id == p.id && name == p.name

    fun toPlace(): Place = Place(id = id, name = name, location = location, address = address)

    companion object {
        fun of(p: Place) = SavedPlace(p.id, p.name, p.location.lat, p.location.lng, p.address)
    }
}

/**
 * The ids of places opened from the map's own labels. A star or a list entry kept under one,
 * with no listing behind it, was kept before the listing loaded or with no connection.
 */
object LabelPlace {
    /** A basemap label. The id is the name's hash, so every branch of a chain shares it. */
    const val BASEMAP = "poi:"

    /** An open-places feature. The id is the feature's own. */
    const val OPEN = "overture:"

    /** A kept basemap label is the opened one only within this distance, the id being shared. */
    const val SAME_LABEL_M = 30.0

    fun basemapId(name: String): String = BASEMAP + name.hashCode()

    fun isLabel(id: String): Boolean = id.startsWith(BASEMAP) || id.startsWith(OPEN)

    /** Whether a copy kept as [id] at [at] is the label opened as [heldId] at [heldAt]. */
    fun same(id: String, at: LatLng, heldId: String, heldAt: LatLng): Boolean =
        id == heldId && (!id.startsWith(BASEMAP) || at.distanceTo(heldAt) <= SAME_LABEL_M)
}
