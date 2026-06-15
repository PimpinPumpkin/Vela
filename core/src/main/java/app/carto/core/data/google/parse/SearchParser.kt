package app.carto.core.data.google.parse

import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.google.arr
import app.carto.core.data.google.at
import app.carto.core.data.google.dbl
import app.carto.core.data.google.findString
import app.carto.core.model.LatLng
import app.carto.core.model.Place
import app.carto.core.model.SearchResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Parses the `tbm=map` search response (positional arrays). The index paths are
 * the part that rots when Google reshuffles, so they live as named CALIBRATE
 * constants with a structural fallback: an entry that carries both a name and a
 * plausible lat/lng is treated as a place. A minor reshuffle then degrades to
 * "fewer fields populated" instead of a crash.
 */
object SearchParser {

    // CALIBRATE: top-level path to the array of result entries.
    private val RESULTS_PATH = intArrayOf(0, 1)

    fun parse(query: String, root: JsonElement): SearchResult {
        val results = root.at(*RESULTS_PATH).arr()
            ?: firstResultsArrayOrNull(root)
            ?: throw CalibrationNeededException("search results array")
        val places = results.mapNotNull { toPlaceOrNull(it) }
        if (places.isEmpty()) throw CalibrationNeededException("search entries (0 parsed)")
        return SearchResult(query, places)
    }

    private fun toPlaceOrNull(entry: JsonElement): Place? {
        // CALIBRATE: exact per-entry offsets for name/address/rating/etc.
        val name = entry.findString { it.length in 1..120 && it.any(Char::isLetter) } ?: return null
        val loc = findLatLng(entry) ?: return null
        return Place(id = "g:" + name.hashCode(), name = name, location = loc)
    }

    /** A lat/lng usually appears as two adjacent doubles in valid ranges. */
    private fun findLatLng(node: JsonElement): LatLng? {
        if (node is JsonArray) {
            for (i in 0 until node.size - 1) {
                val a = node[i].dbl()
                val b = node[i + 1].dbl()
                if (a != null && b != null && a in -90.0..90.0 && b in -180.0..180.0 &&
                    (a != 0.0 || b != 0.0)
                ) {
                    return LatLng(a, b) // CALIBRATE: confirm lat,lng vs lng,lat ordering
                }
            }
            for (e in node) findLatLng(e)?.let { return it }
        }
        return null
    }

    private fun firstResultsArrayOrNull(root: JsonElement): JsonArray? =
        (root as? JsonArray)?.firstOrNull { it is JsonArray && it.size > 1 } as? JsonArray
}
