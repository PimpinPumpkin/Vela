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
import app.vela.core.model.SimilarPlace
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
        // "People also search for" is a sibling of a FOCUSED result (root [2][11][0]) — absent
        // from multi-result lists. When present, attach it to the primary (first) place so its
        // sheet can show the related-places row.
        val similar = runCatching { parseSimilarPlaces(root, paths) }.getOrDefault(emptyList())
        val withSimilar = if (similar.isNotEmpty() && places.isNotEmpty())
            places.mapIndexed { i, p -> if (i == 0) p.copy(similarPlaces = similar) else p } else places
        // Order nearest-first, BUT within the same ~120 m (a shopping centre / one address) rank by
        // prominence (review count) so the MAIN store beats its florist/pharmacy departments sitting at the
        // same spot. Distance still wins across genuinely different locations. (Was pure distance, which let
        // a nearby low-review department outrank the big store it's a part of — the "Safeway Floral" bug.)
        // ONLY when a bias point exists: with near==null every place lands in the same null-distance
        // bucket and the whole list would re-sort by review count — but callers without a bias point
        // (PopularTimesParser's focused name+address lookup) rely on Google's RESPONSE ORDER, where the
        // FIRST entry is the focused result; re-ranking grafted a busier neighbour's data onto it.
        val ranked = if (near == null) withSimilar else withSimilar.sortedWith(
            compareBy<Place>(
                { it.distanceMeters?.let { d -> (d / 120.0).toLong() } ?: Long.MAX_VALUE }, // 120 m distance buckets
                { -(it.reviewCount ?: 0) },                                                 // within a bucket: more reviews first
                { it.distanceMeters ?: Double.MAX_VALUE },                                   // then exact distance
            ),
        )
        return SearchResult(query, ranked)
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
            // component array (dropping any component that's just the business name), else the street line.
            // Some places' formatted address is prefixed with the business NAME ("Safeway, 5802 134th Pl SE");
            // the sheet already shows the name on its own line, so strip that prefix — else it reads twice.
            address = (field("address").str()
                ?: field("addressComponents").arr()?.mapNotNull { it.str() }
                    ?.filterNot { it.equals(name, ignoreCase = true) }?.joinToString(", ")?.ifBlank { null }
                ?: field("addressComponents").at(0).str())
                ?.let { addr -> stripNamePrefix(addr, name) },
            rating = field("rating").dbl(),
            reviewCount = field("reviewCount").int(),
            priceText = field("priceText").str(),
            // Google's search response carries price as a dollar *range* ("$10–20" at
            // [1][4][2]), not the classic 1–4 level, so derive a comparable 1–4 from the
            // label (its lower bound, or the count of '$' for the "$$" symbol style) — the
            // price filter has nothing to compare against otherwise.
            priceLevel = priceLevelOf(field("priceText").str()),
            website = field("website").str(),
            // Action link (Book/Reserve/Order) — only when there's a real http(s) URL, so a
            // shape change can never render a button that opens garbage.
            actionUrl = field("actionUrl").str()?.takeIf { it.startsWith("http", ignoreCase = true) },
            actionLabel = field("actionLabel").str()?.trim()?.ifBlank { null }?.takeIf { it.length <= 30 },
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

    /** Business photos. Google **gutted the keyless preview** (2026-06: the old ~10-photo
     *  `[105]` block is gone). An ordinary business now exposes a SINGLE hero photo at the
     *  calibrated `photos` block (`[1][72][0]`, URL leaf `[6][0]`) — and serves it
     *  **duplicated**, so we de-dup. Big/landmark places additionally carry a small
     *  "gallery preview" at `[1][204][0]` (URL leaf `[1][2][0][0]`); we fold those in where
     *  present. The full gallery (~30+) is **login-gated** now (see [PhotosParser]) — this is
     *  the most photos available keyless. De-dup by the re-sized URL so the hero never repeats. */
    private fun parsePhotos(entry: JsonElement, paths: Map<String, List<Int>>): List<String> {
        val urls = LinkedHashSet<String>()
        fun add(u: String?) {
            if (u != null && u.contains("googleusercontent"))
                urls += u.replace(Regex("=w\\d+-h\\d+.*$"), "=w500-h350")
        }
        entry.atPath(pathOf(paths, "photos")).arr()?.forEach { add(it.at(6, 0).str()) }
        entry.at(1, 204, 0).arr()?.forEach { add(it.at(1, 2, 0, 0).str()) }
        return urls.take(12)
    }

    /** Drop a leading business-name from a formatted address ("Safeway, 5802 134th Pl SE" → "5802 …").
     *  The sheet shows the name on its own line, so a name-prefixed address reads it twice. Strips ONLY
     *  when what follows the name is an explicit separator (","/"·"/dash) or goes straight to the street
     *  NUMBER — a bare space alone is NOT a boundary ("Safeway Plaza, …", "Boeing Access Rd" must survive),
     *  and an unexpected continuation char (apostrophe, exotic dot) leaves the address untouched rather
     *  than half-stripped. If stripping would empty the line, keep the original. */
    internal fun stripNamePrefix(addr: String, name: String): String {
        if (name.isBlank() || !addr.startsWith(name, ignoreCase = true)) return addr
        val rest = addr.substring(name.length)
        if (rest.isNotEmpty() && rest[0].isLetterOrDigit()) return addr // "Safeways Plaza" — not a boundary
        val afterSpaces = rest.trimStart(' ', ' ')
        // Require a real separator or the street number right after the name; anything else ("'s Fuel…",
        // "• …") means this isn't a plain name-prefix — don't touch it.
        if (afterSpaces.isNotEmpty() && !afterSpaces[0].isDigit() && afterSpaces[0] !in ",·–-") return addr
        return afterSpaces.trimStart(',', '·', '-', '–', ' ', ' ').ifBlank { addr }
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

    /** "People also search for": the root-level similar-places list (`similar` = `[2][11][0]`,
     *  present when a search focuses on one result). Each entry is
     *  `[featureId, name, [[_,_,lat,lng], …, rating@6]]`. Best-effort; skips malformed rows. */
    internal fun parseSimilarPlaces(root: JsonElement, paths: Map<String, List<Int>>): List<SimilarPlace> {
        val list = root.atPath(pathOf(paths, "similar")).arr() ?: return emptyList()
        return list.mapNotNull { e ->
            val name = e.at(1).str()?.ifBlank { null } ?: return@mapNotNull null
            val lat = e.at(2, 0, 2).dbl() ?: return@mapNotNull null
            val lng = e.at(2, 0, 3).dbl() ?: return@mapNotNull null
            SimilarPlace(name, LatLng(lat, lng), e.at(2, 6).dbl(), e.at(0).str())
        }
    }

    internal fun readHours(days: JsonElement?): List<String> {
        val arr = days.arr() ?: return emptyList()
        return arr.mapNotNull { day ->
            val name = day.at(0).str() ?: return@mapNotNull null
            // day[3] = ALL of the date's ranges, each `[text, [[openH],[closeH]]]`; join them — we used to read
            // only the first, so a split-shift day ("9 AM–12 PM, 1–5 PM") showed just the morning. Google bakes
            // HOLIDAY hours straight into day[3] (Jul 4 → "Closed"), and day[6][1] carries the label
            // ("4th of July") — surface it after a " · " (OpeningHours strips it before parsing the times).
            val hrs = day.at(3).arr()?.mapNotNull { it.at(0).str()?.ifBlank { null } }
                ?.joinToString(", ")?.ifBlank { null } ?: return@mapNotNull null
            val note = day.at(6, 1).str()?.ifBlank { null }
            if (note != null) "$name: $hrs · $note" else "$name: $hrs"
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
