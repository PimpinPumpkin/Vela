package app.vela.ui.place

/**
 * Where a tapped map place came from, read off its placeholder id, for the source line under
 * the name while the place is not a Google listing. The places bake keeps each row's origin in
 * its id (tools/build-places-region.sh): `atp:<spider>:<ref>` for AllThePlaces, `osm:n123` for
 * an OpenStreetMap node, and Overture's own hex id otherwise; the tap prefixes it with
 * `overture:`. A basemap label tap is `poi:<hash>`, and the basemap is OpenStreetMap.
 * A Google listing's id matches none of these, so the line disappears once it resolves.
 */
data class PlaceOrigin(val kind: Kind, val detail: String? = null, val osmUrl: String? = null) {
    enum class Kind { OVERTURE, ATP, OSM }

    companion object {
        fun of(id: String): PlaceOrigin? {
            if (id.startsWith("poi:")) return PlaceOrigin(Kind.OSM)
            val row = id.removePrefix("overture:").takeIf { it != id } ?: return null
            return when {
                row.startsWith("atp:") -> PlaceOrigin(Kind.ATP, detail = row.split(':').getOrNull(1)?.takeIf { it.isNotBlank() })
                row.startsWith("osm:") -> {
                    val ref = row.removePrefix("osm:")
                    val type = when (ref.firstOrNull()) { 'n' -> "node"; 'w' -> "way"; 'r' -> "relation"; else -> null }
                    val num = ref.drop(1).takeIf { n -> n.isNotEmpty() && n.all { it.isDigit() } }
                    PlaceOrigin(Kind.OSM, detail = ref, osmUrl = if (type != null && num != null) "https://www.openstreetmap.org/$type/$num" else null)
                }
                else -> PlaceOrigin(Kind.OVERTURE)
            }
        }
    }
}
