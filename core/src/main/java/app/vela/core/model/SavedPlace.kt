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
    /** The street has already been moved into [address] by a rename or a label, so a later
     *  rename does not put the name in front of it again. */
    val streetKept: Boolean = false,
) {
    val location: LatLng get() = LatLng(lat, lng)

    /** A pin or an address rather than a listing: marked [bare], a dropped pin's id, or a name
     *  that is the first line of its own address (how one saved before the mark existed reads). */
    val isPoint: Boolean get() = bare || id.startsWith("pin:") ||
        (address != null && name.isNotBlank() && address.trim().startsWith(name.trim(), ignoreCase = true))

    /** The address with the street kept when [name] is about to stop being the street: an address
     *  saved from a search carries its street as the name and only the locality as the address, so
     *  a rename or a Home label would leave "09126 Chemnitz" (issue 736). A listing, or an address
     *  that already starts with the name, is returned as it is. */
    fun addressKeepingStreet(): String? {
        val street = name.trim()
        val rest = address?.trim()?.ifBlank { null } ?: return address
        // An address Google files under a category is saved as a listing, not a point; its street
        // is still only in the name while the address has none.
        val addressNamed = isPoint || (isStreetLine(street) && !hasStreetLine(rest))
        if (!addressNamed || streetKept || street.isBlank() || rest.startsWith(street, ignoreCase = true)) return address
        return "$street, $rest"
    }

    /** This record after a rename to [newName]: the street kept in the address once, then marked
     *  kept so the next rename leaves the address alone. */
    fun renamed(newName: String): SavedPlace =
        copy(name = newName, address = addressKeepingStreet(), streetKept = streetKept || isPoint)

    /** A Home or Work label kept before the street was moved into the address (issue 736), mended:
     *  a name that reads as a street line, with a house number first ("1451 W Covell Blvd") or
     *  last ("Elm Street 5"), which the address lacks goes in front of it, once. Null when there
     *  is nothing to mend. A stored label says nothing of whether it was a listing, so this goes
     *  by the name alone; a business whose name ends in a number ("Studio 54") gets its name
     *  before the address, which a label's line can carry, and one with a number inside its name
     *  ("7-Eleven") is left alone. */
    fun labelMended(): SavedPlace? {
        if (streetKept) return null
        val street = name.trim()
        val rest = address?.trim()?.ifBlank { null } ?: return null
        if (!isStreetLine(street) || rest.contains(street, ignoreCase = true)) return null
        return copy(address = "$street, $rest", streetKept = true)
    }

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
        private val NUMBER_FIRST = Regex("""^\d+[A-Za-z]?(-\d+)?\s+\p{L}""")
        private val NUMBER_LAST = Regex("""^\p{L}.*\s\d+[A-Za-z]?$""")

        /** A house number at the start or the end of [s], with letters on the other side. */
        fun isStreetLine(s: String): Boolean = NUMBER_FIRST.containsMatchIn(s) || NUMBER_LAST.containsMatchIn(s)

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
