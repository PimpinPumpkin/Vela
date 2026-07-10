package app.vela.core.data.google.parse

import app.vela.core.model.Photo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Parses the `batchexecute` `hspqX` (/MapsPhotoService.ListEntityPhotos) response
 * into a flat list of photo URLs - the full place gallery (~40+) vs the ~10 the
 * search response carries.
 *
 * The response is the chunked `batchexecute` envelope: `)]}'` then length-prefixed
 * rows. The data row is `["wrb.fr","hspqX","<payload-json-string>",…]`; the payload
 * (a JSON string) holds the photo list at `[0]`, each entry's FIFE URL at `[6][0]`
 * (the same `[6][0]` leaf the search preview uses) and its **posted date** at
 * `[21][6][8]` = `[year, month, day, hour]` → "May 2026". Calibrated live 2026-06-17,
 * date added 2026-06-20. (The contributor's *name* is NOT in this response - only a
 * date + an upload-source tag - so there's no author here, see [Photo].)
 *
 * IMPORTANT - the full *user-contributed* gallery is **gated behind a Google
 * sign-in**: an anonymous (keyless) session - which is all Vela ever has - gets
 * back only **Street View thumbnails** (`streetviewpixels-pa.googleapis.com`) at
 * the same `[6][0]` leaf, not the `lh*.googleusercontent.com` user photos. So we
 * keep **only `googleusercontent` URLs**; on the anonymous session that's empty,
 * and the caller (best-effort) falls back to the search-response photo preview.
 * (Don't "fix" this by dropping the filter - you'll show non-loading Street View
 * tiles as photos, which was the "placeholders everywhere" regression.)
 */
object PhotosParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val SIZE_SUFFIX = Regex("=w\\d+-h\\d+.*$")

    fun parse(rawBody: String): List<Photo> {
        val start = rawBody.indexOf("[[\"wrb.fr\"")
        if (start < 0) return emptyList()
        val arrStr = extractArray(rawBody, start) ?: return emptyList()
        val outer = runCatching { json.parseToJsonElement(arrStr).jsonArray }.getOrNull() ?: return emptyList()
        val row = outer.firstOrNull { el ->
            (el as? JsonArray)?.getOrNull(0).let { it is JsonPrimitive && it.content == "wrb.fr" }
        } as? JsonArray ?: return emptyList()
        val payloadStr = (row.getOrNull(2) as? JsonPrimitive)?.content ?: return emptyList()
        val payload = runCatching { json.parseToJsonElement(payloadStr).jsonArray }.getOrNull() ?: return emptyList()
        val photos = payload.getOrNull(0) as? JsonArray ?: return emptyList()
        return photos.mapNotNull { entry ->
            val e = entry as? JsonArray ?: return@mapNotNull null
            val url = ((e.getOrNull(6) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.content
                ?.takeIf { it.startsWith("http") && it.contains("googleusercontent") }
                ?.replace(SIZE_SUFFIX, "=w1024-h768") ?: return@mapNotNull null
            Photo(url = url, postedText = postedDate(e))
        }.distinctBy { it.url }
    }

    /** "May 2026" from the entry's `[21][6][8]` = `[year, month, day, hour]`, or null
     * when absent / out of range. */
    private fun postedDate(entry: JsonArray): String? {
        val ymd = (((entry.getOrNull(21) as? JsonArray)?.getOrNull(6) as? JsonArray)
            ?.getOrNull(8)) as? JsonArray ?: return null
        val year = (ymd.getOrNull(0) as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val month = (ymd.getOrNull(1) as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        return "${MONTHS[month - 1]} $year"
    }

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Slice the balanced JSON array starting at [start] (the batchexecute rows
     * are length-prefixed, not a single top-level JSON document, so we can't just
     * `parse` the whole body). */
    private fun extractArray(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }
}
