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
                ?: findResultsArray(root, paths)
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
        // Validate through the CALIBRATED name path, not a hard-coded [11], so a remote
        // paths.name recalibration reaches this gate too (wrap as [null, node] first — the
        // entry-relative name path resolves node.at(11) by default).
        val entry = JsonArray(listOf(JsonNull, node))
        if (entry.atPath(pathOf(paths, "name")).str() == null) return null
        return listOf(entry)
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
            val entry = JsonArray(listOf(JsonNull, node))
            if (entry.atPath(pathOf(paths, "name")).str() == null) return@mapNotNull null // calibrated name path, not [11]
            entry
        }
        return entries.ifEmpty { null }
    }

    private fun toPlace(entry: JsonElement, near: LatLng?, paths: Map<String, List<Int>>): Place? {
        fun field(key: String): JsonElement? = entry.atPath(pathOf(paths, key))
        val name = field("name").str() ?: return null
        val lat = field("lat").dbl() ?: return null
        val lng = field("lng").dbl() ?: return null
        val loc = LatLng(lat, lng)
        // ONE status string feeds BOTH the open/closed boolean AND the displayed text (they used to
        // read in OPPOSITE orders, so the colour could contradict the words - audit 2026-07-06).
        // [203] FIRST, and hours must come from the SAME block as the status (live probe 2026-07-08,
        // the "Safeway closes soon at 10 PM but the hours say 5 AM-1 AM" report): [203] is the MAIN
        // entity's schedule while [118] carries a DEPARTMENT'S sub-schedule (a Safeway's [118] read
        // "9 AM-5 PM" - the pharmacy - while its [203] read "6 AM-12 AM", the store; Google's own UI
        // shows the [203] pair). We used to take status from [118] and hours from [203], which
        // mismatched on every department store.
        val rich = field("statusRich").str()
        val s118 = field("status118").str()
        val statusStr = rich ?: s118 ?: field("openStatus").str()
        return Place(
            id = "g:" + name.hashCode() + ":" + (lat * 1e4).toInt(),
            name = name,
            location = loc,
            category = field("category").str(),
            // Full address incl. city/state/zip — clean one-liner, else join the
            // component array (dropping any component that's just the business name), else the street line.
            // Some places' formatted address is prefixed with the business NAME ("Safeway, 1451 W Covell Blvd");
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
            // Open/closed comes from the STATUS TEXT, matched against the request language's
            // keyword table (parseOpenNow) — the same string the user sees, so the colour can
            // never contradict the words. The numeric "status codes" pinned 2026-07-03
            // ([1,203,1,4,1,0,1]/[1,203,1,8,1,0,1], 6=open/5=closed/13=soon) were DISPROVEN by a
            // live EN capture 2026-07-04: closed pharmacies carried 6 ("open") and an
            // Open-24-hours business carried 13/4 ("closed") — they're span/style markers, not
            // open/closed, and the French pin agreeing was a coincidence. Text is authoritative.
            openNow = parseOpenNow(statusStr),
            statusText = statusStr,
            // Dead POI: the `[23]==1` flag is the reliable signal (Caffé Italia has no
            // status text at all, so the text check alone missed it); keep the text
            // check as a belt-and-suspenders for places that DO spell it out.
            permanentlyClosed = field("closedFlag").int() == 1 || isPermanentlyClosed(
                field("status118").str(), field("statusRich").str(), field("openStatus").str(),
            ),
            // Owner-set TEMPORARY closure: when an owner marks the
            // business temporarily closed, Google replaces the status text with "Temporarily closed"
            // (localized) — surface it first-class so the UI can banner it and suppress the now-
            // misleading weekly hours, instead of quietly relying on the red status colour alone.
            temporarilyClosed = isTemporarilyClosed(
                field("status118").str(), field("statusRich").str(), field("openStatus").str(),
            ),
            // Coherent pairing: when the displayed status came from [118] (no [203] status), take
            // the hours from [118] first too - status and table then describe the same schedule.
            hours = parseHours(entry, paths, prefer118 = rich == null && s118 != null),
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

    /** Drop a leading business-name from a formatted address ("Safeway, 1451 W Covell Blvd" to "1451 ...").
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

    /** Owner-set TEMPORARY closure, matched across the request languages' status wording
     *  ("Temporarily closed" / "Fermé temporairement" / "Vorübergehend geschlossen" …). CONTAINS,
     *  not startsWith — several languages put the closed word first ("Fermé temporairement").
     *  The words are distinctive enough that no `hl` gating is needed. Unlike permanent closure,
     *  a temp-closed place STAYS on the map — the UI banners it instead. */
    private val TEMP_CLOSED_WORDS = listOf(
        "Temporarily", "temporairement", "Vorübergehend", "temporalmente", "temporaneamente",
        "temporariamente", "Tijdelijk", "Временно", "Tymczasowo", "Tillfälligt", "Тимчасово",
    )

    internal fun isTemporarilyClosed(vararg status: String?): Boolean =
        status.any { s -> s != null && TEMP_CLOSED_WORDS.any { s.contains(it, ignoreCase = true) } }

    /** Google's status strings begin with a small, stable set of words per UI language (`hl=`).
     *  CLOSED indicators per language — matched FIRST, because in several languages the closed
     *  form is a prefix-cousin of the open one and matching the open word first is exactly the
     *  bug that painted a closed Starbucks green:
     *    en "Opens 5 AM" vs "Open" · pt "Fechado" (closed) vs "Fecha às 19:00" (closes → open)
     *    nl "Opent om 09:00" (opens → closed) vs "Open" · fr "Ouvre à 07:00" (closed) vs "Ouvert". */
    private val CLOSED_WORDS = mapOf(
        "en" to listOf("Closed", "Opens", "Opening", "Temporarily", "Permanently"),
        "fr" to listOf("Fermé", "Ouvre", "Définitivement"),
        "de" to listOf("Geschlossen", "Öffnet", "Vorübergehend", "Dauerhaft"),
        "es" to listOf("Cerrado", "Abre"),
        "it" to listOf("Chiuso", "Apre"),
        "pt" to listOf("Fechado", "Abre"),
        "nl" to listOf("Gesloten", "Opent", "Tijdelijk", "Definitief"),
        "ru" to listOf("Закрыто", "Откроется", "Временно"),
        "pl" to listOf("Zamknięte", "Otwarcie", "Tymczasowo"),
        "sv" to listOf("Stängt", "Öppnar", "Tillfälligt"),
        "uk" to listOf("Зачинено", "Відчиниться", "Тимчасово"),
    )

    /** Languages [parseOpenNow] actually has a keyword table for. `GoogleMapsDataSource.localized()`
     *  gates its `hl=` rewrite on this — for a locale NOT covered here the scrape must stay `hl=en`,
     *  else the status text comes back in an unparseable language and openNow is always null (the UI
     *  then can't colour it). Keyed off [CLOSED_WORDS] so the set can never drift from the tables. */
    internal val STATUS_LANGS: Set<String> get() = CLOSED_WORDS.keys

    /** OPEN indicators per language: the "open" word itself plus the closes-later forms
     *  ("Closes 9 PM" / "Ferme à 19:00" / "Closing soon" — closing LATER means open NOW). */
    private val OPEN_WORDS = mapOf(
        "en" to listOf("Open", "Closes", "Closing"),
        "fr" to listOf("Ouvert", "Ferme"),
        "de" to listOf("Geöffnet", "Schließt"),
        "es" to listOf("Abierto", "Cierra"),
        "it" to listOf("Aperto", "Chiude"),
        "pt" to listOf("Aberto", "Fecha"),
        "nl" to listOf("Geopend", "Open", "Sluit"),
        "ru" to listOf("Открыто", "Закроется", "Закрывается"),
        "pl" to listOf("Otwarte", "Zamknięcie"),
        "sv" to listOf("Öppet", "Stänger"),
        "uk" to listOf("Відчинено", "Зачиняється"),
    )

    /** Live status text → open/closed, in the language the scrape requested (`hl=` follows
     *  [java.util.Locale.getDefault], same derivation as `GoogleMapsDataSource.localized()`).
     *  This is the AUTHORITATIVE open/closed signal: it reads the same words the user sees, so
     *  the status colour can never contradict the display. (The numeric status-code path was
     *  removed 2026-07-04 — a live capture proved those ints aren't open/closed codes; see the
     *  call site.) CLOSED words are matched before OPEN words — order is load-bearing, see
     *  [CLOSED_WORDS]. Unknown language → the English table; no match → null (callers stay
     *  conservative — placeStatusColor only ever greens an affirmative signal). */
    internal fun parseOpenNow(
        status: String?,
        lang: String = java.util.Locale.getDefault().language.lowercase(),
    ): Boolean? {
        val s = status?.trim()?.ifBlank { null } ?: return null
        val closed = CLOSED_WORDS[lang] ?: CLOSED_WORDS.getValue("en")
        val open = OPEN_WORDS[lang] ?: OPEN_WORDS.getValue("en")
        return when {
            closed.any { s.startsWith(it) } -> false
            open.any { s.startsWith(it) } -> true
            else -> null
        }
    }

    /** Weekly hours — `hours203` (the main entity's schedule) first, falling back to `hours118`
     *  (a department's sub-schedule, e.g. the pharmacy inside a grocery store); [prefer118] flips
     *  the order when the displayed STATUS came from [118], so the table matches the status. Both
     *  are a 7-entry array starting today; each day = name `[0]` + text `[3][0][0]`
     *  (leaf indices stable). */
    private fun parseHours(entry: JsonElement, paths: Map<String, List<Int>>, prefer118: Boolean = false): List<String> {
        val h203 = { readHours(entry.atPath(pathOf(paths, "hours203"))) }
        val h118 = { readHours(entry.atPath(pathOf(paths, "hours118"))) }
        return if (prefer118) h118().ifEmpty(h203) else h203().ifEmpty(h118)
    }

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
    private fun findResultsArray(root: JsonElement, paths: Map<String, List<Int>>): JsonArray? {
        if (root !is JsonArray) return null
        val namePath = pathOf(paths, "name") // entry-relative; validity gate follows a paths.name recalibration
        return root.filterIsInstance<JsonArray>()
            .filter { it.size >= 1 && it.firstOrNull().atPath(namePath).str() != null }
            .maxByOrNull { it.size }
    }

    private fun pathOf(paths: Map<String, List<Int>>, key: String): List<Int>? =
        paths[key] ?: Calibration.DEFAULT_PATHS[key]

    private fun JsonElement?.atPath(path: List<Int>?): JsonElement? =
        if (path == null) null else this.at(*path.toIntArray())
}
