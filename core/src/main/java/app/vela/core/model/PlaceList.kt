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
    // The place's own map icon (issue #629), overriding its list's; same keys as [PlaceList.icon].
    val icon: String? = null,
    // From a custom map (issue #669): the marker's own color, its layer, and its photos.
    val color: Long? = null,
    val layer: String? = null,
    val photos: List<String> = emptyList(),
) {
    val location: LatLng get() = LatLng(lat, lng)

    /** Same real-world place? The volatile [id] ("g:" + name hash + coarse lat) can differ
     *  between visits for a multi-listing chain (the tap-resolve may pick a different
     *  co-located listing next time), so membership/note matching prefers the STABLE
     *  Google [featureId] and keeps [id] as the fallback for places that lack one. */
    fun matches(id: String, featureId: String?): Boolean =
        this.id == id || (featureId != null && this.featureId == featureId)

    fun toPlace(): Place = Place(
        id = id,
        name = name,
        location = LatLng(lat, lng),
        address = address,
        featureId = featureId,
        savedNote = note,
        pinColor = color, mapLayer = layer, photoUrls = photos,
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
            color = p.pinColor, layer = p.mapLayer,
            // Only a custom map's own photos are kept; a Google place's are fetched when opened.
            photos = if (p.mapLayer != null || p.id.startsWith("mymap:")) p.photoUrls.take(12) else emptyList(),
        )
    }
}

/** A user-created (or imported) list of places — Google-Maps "saved lists" (issue #1).
 *  [icon] is a stable key into the app's icon set; [color] an ARGB int the UI tints with. */
/** A line or an area drawn on the map: part of a Google My Maps custom map (issue #669). [pts]
 *  is lat, lng, lat, lng...; [closed] = an area, filled with [fill]. Colors are ARGB. */
@Serializable
data class MapShape(
    val name: String = "",
    val description: String? = null,
    val pts: List<Double> = emptyList(),
    val closed: Boolean = false,
    val color: Long = 0xFF1A73E8,
    val width: Float = 3f,
    val fill: Long? = null,
    val layer: String? = null,
)

@Serializable
data class PlaceList(
    val id: String,
    val name: String,
    val icon: String = "bookmark",
    val color: Long = 0xFF1A73E8, // Google blue by default
    val description: String? = null,
    val places: List<ListPlace> = emptyList(),
    /** The lines and areas of an imported custom map; drawn while the list is on the map. */
    val shapes: List<MapShape> = emptyList(),
    /** Layers of a custom map switched off: their pins and shapes are not drawn or listed. */
    val hiddenLayers: List<String> = emptyList(),
) {
    /** The custom map's layers, in the order they first appear. */
    val layers: List<String> get() = (places.mapNotNull { it.layer } + shapes.mapNotNull { it.layer }).distinct()
}
