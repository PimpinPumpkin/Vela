package app.vela.core.data.google.parse

import app.vela.core.config.Calibration
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.int
import app.vela.core.data.google.str
import app.vela.core.model.AboutSection
import app.vela.core.model.DayBusyness
import app.vela.core.model.HourBusyness
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.PopularTimes
import app.vela.core.model.SearchResult
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Parses the `/search?tbm=map` response.
 *
 * The positional field-index paths are no longer hard-coded — they come from
 * [Calibration.paths] (remotely updatable, phase 2), defaulting to
 * [Calibration.DEFAULT_PATHS]. Paths are relative to a result *entry* (whose
 * place node is `[1]`), except `results`/`single` which are relative to the root.
 * Schema first calibrated 2026-06-15; `[64]` results, name `[1][11]`, full
 * address `[1][39]`, coords `[1][9][2/3]`, etc. (see DEFAULT_PATHS).
 */
object SearchParser {

    fun parse(
        query: String,
        root: JsonElement,
        near: LatLng? = null,
        paths: Map<String, List<Int>> = Calibration.DEFAULT_PATHS,
    ): SearchResult {
        // A fundamentally wrong envelope (consent wall, error page) is a real
        // calibration problem; an otherwise-valid response with no matches is just
        // "no results" and must NOT be reported as a calibration error.
        if (root !is JsonArray) throw CalibrationNeededException("search: response not a JSON array")
        val entries: List<JsonElement> =
            root.atPath(pathOf(paths, "results")).arr()?.takeIf { it.isNotEmpty() }
                ?: atThisPlaceEntries(root, paths) // an address → the business AT it
                ?: singleResultEntry(root, paths)
                ?: findResultsArray(root)
                ?: return SearchResult(query, emptyList())

        val places = entries.mapNotNull { entry -> toPlace(entry, near, paths) }
        return SearchResult(query, places.sortedBy { it.distanceMeters ?: Double.MAX_VALUE })
    }

    /** A specific/far address resolves to a *single* geocoded result rather than
     *  the `[64]` POI list — its place node sits at `single` (`[0][1][0][14]`, same
     *  internal schema as a list entry's `[1]`). Wrap it as `[null, node]` so
     *  [toPlace], which reads `entry[1]`, parses it unchanged. */
    private fun singleResultEntry(root: JsonElement, paths: Map<String, List<Int>>): List<JsonElement>? {
        val node = root.atPath(pathOf(paths, "single")) ?: return null
        if (node.at(11).str() == null) return null
        return listOf(JsonArray(listOf(JsonNull, node)))
    }

    /** Searching a bare ADDRESS that is a business ("1020 Olive Dr" → In-N-Out): Google
     *  lists the business(es) at that address under the geocoded node, at `atThisPlace`
     *  (`[0][1][0][14][68]`). We snap to them instead of showing the bare address — each
     *  entry's place node is at `[i][0]`, wrapped as `[null, node]` so [toPlace] (which
     *  reads `[1]`) parses it unchanged. Empty/absent → fall through to the address. */
    private fun atThisPlaceEntries(root: JsonElement, paths: Map<String, List<Int>>): List<JsonElement>? {
        val list = root.atPath(pathOf(paths, "atThisPlace")).arr() ?: return null
        val entries = list.mapNotNull { e ->
            val node = e.at(0) ?: return@mapNotNull null
            if (node.at(11).str() == null) return@mapNotNull null
            JsonArray(listOf(JsonNull, node))
        }
        return entries.ifEmpty { null }
    }

    private fun toPlace(entry: JsonElement, near: LatLng?, paths: Map<String, List<Int>>): Place? {
        fun field(key: String): JsonElement? = entry.atPath(pathOf(paths, key))
        val name = field("name").str() ?: return null
        val lat = field("lat").dbl() ?: return null
        val lng = field("lng").dbl() ?: return null
        val loc = LatLng(lat, lng)
        return Place(
            id = "g:" + name.hashCode() + ":" + (lat * 1e4).toInt(),
            name = name,
            location = loc,
            category = field("category").str(),
            // Full address incl. city/state/zip — clean one-liner, else join the
            // component array, else just the street line.
            address = field("address").str()
                ?: field("addressComponents").arr()?.mapNotNull { it.str() }?.joinToString(", ")?.ifBlank { null }
                ?: field("addressComponents").at(0).str(),
            rating = field("rating").dbl(),
            reviewCount = field("reviewCount").int(),
            priceText = field("priceText").str(),
            // Google's search response carries price as a dollar *range* ("$10–20" at
            // [1][4][2]), not the classic 1–4 level, so derive a comparable 1–4 from the
            // label (its lower bound, or the count of '$' for the "$$" symbol style) — the
            // price filter has nothing to compare against otherwise.
            priceLevel = priceLevelOf(field("priceText").str()),
            website = field("website").str(),
            phone = field("phone").str(),
            openNow = parseOpenNow(field("openStatus").str()),
            statusText = field("status118").str()
                ?: field("statusRich").str()
                ?: field("openStatus").str(),
            // Dead POI: the `[23]==1` flag is the reliable signal (Caffé Italia has no
            // status text at all, so the text check alone missed it); keep the text
            // check as a belt-and-suspenders for places that DO spell it out.
            permanentlyClosed = field("closedFlag").int() == 1 || isPermanentlyClosed(
                field("status118").str(), field("statusRich").str(), field("openStatus").str(),
            ),
            hours = parseHours(entry, paths),
            photoUrls = parsePhotos(entry, paths),
            featuredReview = field("featuredReview").str()
                ?.trim()?.trim('"', '“', '”')?.ifBlank { null },
            featureId = field("featureId").str(),  // "0x..:0x.." → reviews RPC
            placeId = field("placeId").str(),      // "ChIJ.." → deep links
            about = parseAbout(entry, paths),
            editorialSummary = field("editorialSummary").str()?.trim()?.ifBlank { null },
            ownerDescription = field("ownerDescription").str()?.trim()?.ifBlank { null },
            popularTimes = parsePopularTimes(entry, paths),
            distanceMeters = near?.distanceTo(loc),
        )
    }

    /** A 1–4 price level from Google's price label: its lower dollar bound, bucketed
     *  ("$1–10"→1, "$10–20"→2, "$20–30"→3, "$35+"→4), or the count of '$' for the
     *  symbol style ("$$"→2). Null when there's no price. Powers the price filter,
     *  since the response only ships a dollar *range*, never the classic 1–4 level. */
    internal fun priceLevelOf(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        Regex("\\d+").find(text)?.value?.toIntOrNull()?.let { low ->
            return when {
                low < 10 -> 1
                low < 20 -> 2
                low < 35 -> 3
                else -> 4
            }
        }
        return text.count { it == '$' }.takeIf { it in 1..4 }
    }

    /** Popular-times histogram: `popularTimes` (`[1][84]`) → `[0]` is 7 days, each
     *  `[d][0]`=day-of-week (1=Mon…7=Sun), `[d][1]`=hourly `[hour, occupancy%, …]`.
     *
     *  The keyless OkHttp search **strips `[84]`** (bot-degraded, TLS-fingerprint),
     *  and a bare-name search returns a 20-result list trimmed of it too — so this
     *  stays null on a plain keyless response and the UI section just doesn't show.
     *  It's populated by fetching through a real Chromium WebView with a *specific*
     *  query (name + address), which comes back as the single focused result that
     *  keeps `[84]` (corrected 2026-06-19 — the earlier "login-gated" read was wrong;
     *  it's the same bot-degradation as photos/transit). See
     *  [app.vela.web.WebPopularTimesFetcher] and [PopularTimesParser]. */
    internal fun parsePopularTimes(entry: JsonElement, paths: Map<String, List<Int>>): PopularTimes? {
        val days = entry.atPath(pathOf(paths, "popularTimes")).at(0).arr() ?: return null
        val parsed = days.mapNotNull { d ->
            val dow = d.at(0).int() ?: return@mapNotNull null
            val hours = d.at(1).arr().orEmpty().mapNotNull { h ->
                val hour = h.at(0).int() ?: return@mapNotNull null
                val occ = h.at(1).int() ?: return@mapNotNull null
                HourBusyness(hour, occ)
            }
            if (hours.isEmpty()) null else DayBusyness(dow, hours)
        }
        return if (parsed.isEmpty()) null else PopularTimes(parsed)
    }

    /** "About" sections: `about` → a list, each with title `[s][1]` + items
     *  `[s][2][j][1]` (the leaf indices are stable, so kept in code). */
    private fun parseAbout(entry: JsonElement, paths: Map<String, List<Int>>): List<AboutSection> {
        val sections = entry.atPath(pathOf(paths, "about")).arr() ?: return emptyList()
        return sections.mapNotNull { s ->
            val title = s.at(1).str() ?: return@mapNotNull null
            val items = s.at(2).arr()?.mapNotNull { it.at(1).str()?.ifBlank { null } }.orEmpty()
            if (items.isEmpty()) null else AboutSection(title, items)
        }
    }

    /** Business photos: `photos` → an array of photo objects, URL at `[6][0]`
     *  (leaf stable). Re-size the FIFE URL up for the sheet's photo strip. */
    private fun parsePhotos(entry: JsonElement, paths: Map<String, List<Int>>): List<String> {
        val arr = entry.atPath(pathOf(paths, "photos")).arr() ?: return emptyList()
        return arr.take(12).mapNotNull { photo ->
            photo.at(6, 0).str()?.replace(Regex("=w\\d+-h\\d+.*$"), "=w500-h350")
        }
    }

    /** "Permanently closed" (and the rarer "Permanently closed" rich-status variant)
     *  → a dead POI. Kept in search results but hidden from the map and labelled. */
    private fun isPermanentlyClosed(vararg status: String?): Boolean =
        status.any { it != null && it.contains("Permanently", ignoreCase = true) }

    /** Live status text → open/closed. "Closes 6 PM" means open now; "Closed
     *  · Opens 7 AM" means closed. */
    private fun parseOpenNow(status: String?): Boolean? = when {
        status == null -> null
        status.startsWith("Open") || status.startsWith("Closes") -> true
        status.startsWith("Closed") || status.startsWith("Temporarily") ||
            status.startsWith("Permanently") -> false
        else -> null
    }

    /** Weekly hours — `hours203` first, falling back to `hours118`. Both are a
     *  7-entry array starting today; each day = name `[0]` + text `[3][0][0]`
     *  (leaf indices stable). */
    private fun parseHours(entry: JsonElement, paths: Map<String, List<Int>>): List<String> =
        readHours(entry.atPath(pathOf(paths, "hours203")))
            .ifEmpty { readHours(entry.atPath(pathOf(paths, "hours118"))) }

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

    private fun pathOf(paths: Map<String, List<Int>>, key: String): List<Int>? =
        paths[key] ?: Calibration.DEFAULT_PATHS[key]

    private fun JsonElement?.atPath(path: List<Int>?): JsonElement? =
        if (path == null) null else this.at(*path.toIntArray())
}
