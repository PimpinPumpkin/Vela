package app.vela.core.data.google

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google Maps' own search-as-you-type answer, keyless (`/s?tbm=map&gs_ri=maps&suggest=p`,
 * the request the maps web page fires on every keystroke). It is the autocomplete the Google
 * app shows, and it honors the location bias for a partial address, which the calibrated
 * search endpoint never did: "a house number" near the phone lists the five houses numbered a house number in
 * the next few streets, where the search endpoint answered with a ZIP code two time zones
 * away (2026-09-22). A house address far from the phone ("459 Ralston" typed in another
 * state) still comes back, ranked after the nearby ones.
 *
 * The response shape is described above [parse]; the column index is searched, not assumed,
 * so a shifted column keeps parsing.
 */
object SuggestParser {
    /** What a suggestion row resolves to: a place with a location, or a bare query string. */
    data class Result(val places: List<Place>, val queries: List<String>)

    // Shape (positional, 2026-09-22): the body is {"c":0,"d":")]}'\n<json>"} followed by a
    // comment-style tail (slash-star, two quotes, star-slash); <json> is
    //   [[query, [suggestion...]], ...]
    // and every suggestion is a row of nulls with its content at index 22:
    //   [[title, ...], [primary, matches], [secondary], ..., 11: [_, _, lat, lng], ...,
    //    13: [[featureId, title, _, [_, _, lat, lng], ..., 10: "/g/..."]]]
    // A row without a location is a plain query ("Starbucks" + "See locations", "cvs pharmacy
    // hours"). The block is the first array element whose first child is an array starting
    // with a string.
    fun parse(body: String): Result {
        val payload = unwrap(body) ?: return Result(emptyList(), emptyList())
        val root = runCatching { GoogleResponse.parse(payload) }.getOrNull() ?: return Result(emptyList(), emptyList())
        val rows = root.at(0, 1) as? JsonArray ?: return Result(emptyList(), emptyList())
        val places = ArrayList<Place>()
        val queries = ArrayList<String>()
        for (row in rows) {
            val block = (row as? JsonArray)?.firstOrNull { el ->
                el is JsonArray && (el.firstOrNull() as? JsonArray)?.firstOrNull() is JsonPrimitive
            } as? JsonArray ?: continue
            val title = block.at(0, 0).str() ?: continue
            val primary = block.at(1, 0).str() ?: title
            val secondary = block.at(2, 0).str()
            val lat = block.at(11, 2).num() ?: block.at(13, 0, 3, 2).num()
            val lng = block.at(11, 3).num() ?: block.at(13, 0, 3, 3).num()
            if (lat == null || lng == null) {
                queries.add(title)
                continue
            }
            val fid = block.at(13, 0, 0).str()?.takeIf { FEATURE_ID.matches(it) }
            places.add(
                Place(
                    id = fid ?: "suggest:${"%.5f".format(lat)},${"%.5f".format(lng)}",
                    name = primary,
                    location = LatLng(lat, lng),
                    // "See locations" is a hint on a query row, never an address.
                    address = secondary?.takeIf { it != primary },
                    featureId = fid,
                ),
            )
        }
        return Result(places, queries)
    }

    /** The `d` string out of the `{"c":0,"d":"..."}` envelope, with the anti-hijack prefix
     *  still on it (GoogleResponse.parse strips that). Only the FIRST object is read: after the
     *  comment tail the app's request (not a browser's) gets a second object, `{"c":0,"d":"",
     *  "e":"<token>"}`, and the first one also echoes the request URL, so the last closing brace
     *  in the body is never the right one (device-found 2026-09-22). Null when the envelope is
     *  not there. */
    internal fun unwrap(body: String): String? {
        val end = firstObjectEnd(body) ?: return null
        val obj = runCatching { GoogleResponse.parse(body.substring(0, end + 1)).jsonObject }.getOrNull() ?: return null
        return obj["d"]?.jsonPrimitive?.contentOrNull
    }

    /** Index of the brace closing the first JSON object in [s], skipping braces inside strings. */
    private fun firstObjectEnd(s: String): Int? {
        val start = s.indexOf('{').takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            when {
                inString -> when (c) {
                    '\\' -> i++ // skip the escaped char
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private val FEATURE_ID = Regex("^0x[0-9a-f]+:0x[0-9a-f]+$")
}
