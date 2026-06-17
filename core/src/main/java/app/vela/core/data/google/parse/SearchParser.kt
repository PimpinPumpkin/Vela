package app.vela.core.data.google.parse

import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.int
import app.vela.core.data.google.str
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.SearchResult
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Parses the `/search?tbm=map` response.
 *
 * Schema calibrated against a live capture (2026-06-15): the results live at
 * `root[64]`, and within each result everything hangs off `[1]`:
 *   name `[1][11]`, full address `[1][39]` (street/city/state/zip), rating `[1][4][7]`,
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
            // Full address incl. city/state/zip. `[1][39]` is the clean one-line
            // form ("1410 Lombard St, San Francisco, CA 94123"); fall back to
            // joining the component array `[1][2]` (["street","City, ST ZIP"]),
            // then to just the street line. (Verified live 2026-06-16.)
            address = entry.at(1, 39).str()
                ?: entry.at(1, 2).arr()?.mapNotNull { it.str() }?.joinToString(", ")?.ifBlank { null }
                ?: entry.at(1, 2, 0).str(),
            rating = entry.at(1, 4, 7).dbl(),
            reviewCount = entry.at(1, 4, 8).int(),
            priceText = entry.at(1, 4, 2).str(),
            website = entry.at(1, 7, 0).str(),
            phone = entry.at(1, 178, 0, 0).str(), // formatted display number, e.g. "(530) 979-5888"
            openNow = parseOpenNow(entry.at(1, 203, 1, 8, 0).str()),
            // Rich status with closing time ("Open · Closes 9 PM") lives at
            // [1][118][0][3][1][4][0] (118-format) or [1][203][1][4][0] (203-format);
            // fall back to the short "Open"/"Closed" at [1][203][1][8][0].
            statusText = entry.at(1, 118, 0, 3, 1, 4, 0).str()
                ?: entry.at(1, 203, 1, 4, 0).str()
                ?: entry.at(1, 203, 1, 8, 0).str(),
            hours = parseHours(entry),
            distanceMeters = near?.distanceTo(loc),
        )
    }

    /** Live status text → open/closed. "Closes 6 PM" means open now; "Closed
     *  · Opens 7 AM" means closed. Calibrated path: `[1][203][1][8][0]`. */
    private fun parseOpenNow(status: String?): Boolean? = when {
        status == null -> null
        status.startsWith("Open") || status.startsWith("Closes") -> true
        status.startsWith("Closed") || status.startsWith("Temporarily") ||
            status.startsWith("Permanently") -> false
        else -> null
    }

    /** Weekly hours. Most places carry them at `[1][203][0]`; some only at the
     *  older `[1][118][0][3][0]`. Both are a 7-entry array (starting with today)
     *  where each day has its name at `[0]` and the hours text at `[3][0][0]`,
     *  e.g. "Tuesday: 6 AM–8 PM". Read `[203]` first, fall back to `[118]`.
     *  (Calibrated 2026-06-16 across 60 live results: `[118]`-only missed most.) */
    private fun parseHours(entry: JsonElement): List<String> =
        readHours(entry.at(1, 203, 0)).ifEmpty { readHours(entry.at(1, 118, 0, 3, 0)) }

    internal fun readHours(days: JsonElement?): List<String> {
        val arr = days.arr() ?: return emptyList()
        return arr.mapNotNull { day ->
            val name = day.at(0).str() ?: return@mapNotNull null
            val hrs = day.at(3, 0, 0).str() ?: return@mapNotNull null
            "$name: $hrs"
        }
    }

    /** Fallback: the largest array whose first entry has a name + coordinate. */
    private fun findResultsArray(root: JsonElement): JsonArray? {
        if (root !is JsonArray) return null
        return root.filterIsInstance<JsonArray>()
            .filter { it.size >= 1 && it.firstOrNull()?.at(1, 11).str() != null }
            .maxByOrNull { it.size }
    }
}
