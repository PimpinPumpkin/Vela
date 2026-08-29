package app.vela.core.data

/**
 * What actually happened when a user tried to import a file (issue #287).
 *
 * The stores used to answer with a bare `Int`, which collapsed four very different outcomes into
 * "0": the file could not be read, it was not Vela's format at all, it was Vela's format but held
 * nothing new, and it held nothing. The UI could then only say "Nothing to import", which is
 * actively misleading when the real problem is that you exported the file from a different app.
 */
sealed interface ImportResult {
    /** [count] entries were new and have been merged in. */
    data class Added(val count: Int) : ImportResult

    /** Valid Vela data, but every entry was already saved - importing a backup twice. */
    data object NothingNew : ImportResult

    /** Readable, but not Vela's own export. [format] names it when recognisable, so the message
     *  can say what the file actually is instead of shrugging. */
    data class WrongFormat(val format: String?) : ImportResult

    /** The file could not be opened or read at all. */
    data object Unreadable : ImportResult
}

/**
 * Best-effort naming of a file the importer cannot use, so the app can say "that looks like a
 * Google Takeout file" rather than "nothing to import".
 *
 * Deliberately shallow: it matches on distinctive markers rather than parsing, because the point
 * is only to write a better sentence, never to decide whether an import succeeds.
 */
object ImportFormats {
    fun describe(json: String): String? {
        val head = json.take(4000)
        return when {
            // Google Takeout saved places: a GeoJSON FeatureCollection whose properties carry
            // Google's own place fields.
            // Takeout's distinctive markers: its own property name, and the two URL spellings
            // Google actually emits (maps.google.com/?cid=... and google.com/maps/...).
            head.contains("\"FeatureCollection\"") && (
                head.contains("google_maps_url") ||
                    head.contains("maps.google.com") ||
                    head.contains("google.com/maps")
                ) -> "Google Takeout"
            head.contains("\"FeatureCollection\"") || head.contains("\"features\"") -> "GeoJSON"
            head.trimStart().startsWith("<?xml") || head.contains("<gpx") -> "GPX"
            head.contains("<kml") || head.contains("<Placemark") -> "KML"
            head.contains("\"bookmarks\"") || head.contains("\"organicmaps\"") -> "Organic Maps"
            else -> null
        }
    }
}
