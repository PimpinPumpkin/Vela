package app.carto.core.data.google.parse

import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.google.arr
import app.carto.core.data.google.at
import app.carto.core.data.google.dbl
import app.carto.core.data.google.int
import app.carto.core.data.google.str
import app.carto.core.model.LatLng
import app.carto.core.model.Place
import app.carto.core.model.SearchResult
import app.carto.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Parses the `/search?tbm=map` response.
 *
 * Schema calibrated against a live capture (2026-06-15): the results live at
 * `root[64]`, and within each result everything hangs off `[1]`:
 *   name `[1][11]`, address line `[1][2][0]`, rating `[1][4][7]`,
 *   reviewCount `[1][4][8]`, lat `[1][9][2]`, lng `[1][9][3]`, category `[1][13][0]`.
 * If `[64]` ever moves, [findResultsArray] falls back to the largest array whose
 * entries carry both a name and a coordinate, so a reshuffle degrades instead of
 * crashing.
 */
object SearchParser {

    private const val RESULTS_INDEX = 64

    fun parse(query: String, root: JsonElement, near: LatLng? = null): SearchResult {
        val results = root.at(RESULTS_INDEX).arr()
            ?: findResultsArray(root)
            ?: throw CalibrationNeededException("search results array (root[$RESULTS_INDEX])")

        val places = results.mapNotNull { entry -> toPlace(entry, near) }
        if (places.isEmpty()) throw CalibrationNeededException("search: 0 results parsed")
        return SearchResult(query, places.sortedBy { it.distanceMeters ?: Double.MAX_VALUE })
    }

    private fun toPlace(entry: JsonElement, near: LatLng?): Place? {
        val name = entry.at(1, 11).str() ?: return null
        val lat = entry.at(1, 9, 2).dbl() ?: return null
        val lng = entry.at(1, 9, 3).dbl() ?: return null
        val loc = LatLng(lat, lng)
        return Place(
            id = "g:" + name.hashCode() + ":" + (lat * 1e4).toInt(),
            name = name,
            location = loc,
            category = entry.at(1, 13, 0).str(),
            address = entry.at(1, 2, 0).str(),
            rating = entry.at(1, 4, 7).dbl(),
            reviewCount = entry.at(1, 4, 8).int(),
            distanceMeters = near?.distanceTo(loc),
        )
    }

    /** Fallback: the largest array whose first entry has a name + coordinate. */
    private fun findResultsArray(root: JsonElement): JsonArray? {
        if (root !is JsonArray) return null
        return root.filterIsInstance<JsonArray>()
            .filter { it.size >= 1 && it.firstOrNull()?.at(1, 11).str() != null }
            .maxByOrNull { it.size }
    }
}
