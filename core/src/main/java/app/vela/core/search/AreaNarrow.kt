package app.vela.core.search

import app.vela.core.model.Place

/**
 * "Search this area" means the area (issue #670): Google treats the search window as a hint and
 * answers with the same far-flung thirty however small the view, so zooming in and searching
 * again narrowed nothing. The results inside the view, with a little margin, are what is shown;
 * when none are, the whole answer stands, because an empty list would say there is nothing.
 */
object AreaNarrow {
    const val PAD = 0.10

    /** [box] is south, west, north, east. */
    fun inView(places: List<Place>, box: DoubleArray?): List<Place> {
        if (box == null || box.size < 4) return places
        val dLat = (box[2] - box[0]) * PAD
        val dLng = (box[3] - box[1]) * PAD
        if (dLat <= 0.0 || dLng <= 0.0) return places
        val inside = places.filter {
            it.location.lat in (box[0] - dLat)..(box[2] + dLat) && it.location.lng in (box[1] - dLng)..(box[3] + dLng)
        }
        return inside.ifEmpty { places }
    }
}
