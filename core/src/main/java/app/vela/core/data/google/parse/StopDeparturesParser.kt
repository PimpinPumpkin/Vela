package app.vela.core.data.google.parse

import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.long
import app.vela.core.data.google.str
import app.vela.core.model.StopDeparture
import app.vela.core.model.StopDepartureLine
import app.vela.core.model.StopDepartures
import app.vela.core.model.TransitMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Parses a transit stop's live departure board out of the station's own place-details
 * payload — the SAME keyless, anonymous WebView + `APP_INITIALIZATION_STATE` channel
 * Vela uses for transit itineraries and photos. The board is embedded in the place page
 * (no separate RPC fires when you open "See departure board"), and it survives a
 * logged-out session, so it needs no endpoint and no login.
 *
 * Schema calibrated against a live NYC subway-hub capture (2026-07-12):
 *   place        `root[6]`
 *   transit node `place[62]`     = ["<station>", [ <service groups> ], …]
 *   group        `[null,"<Subway services>", [ <lines> ], … "<mode code>" …]`
 *   line         `[null, [ <directions> ], … "<line ftid>" …]`
 *   direction    `["<headsign>", null, null, [ <departures> ], …]`
 *   departure    time tuple `[rtEpoch, "<tz>", "4:35 AM", <utcOffset>, schedEpoch]`
 *   frequency    `[<headwaySec>, "20 min"]`
 *
 * The container path (transit node → groups → lines → directions) is positional, but the
 * leaf details (the time tuples, the frequency pair, the route label) are matched by SHAPE
 * within each direction so a moved leaf index degrades one line, never the board. The
 * `place[62]` anchor is validated and falls back to a shape search of the place node, so a
 * shifted field index still finds it. A non-station place simply has no transit node →
 * [parse] returns null (routine); a transit node that yields 0 lines throws
 * [CalibrationNeededException] (real drift), like the other parsers.
 */
object StopDeparturesParser {

    private const val TRANSIT_NODE_INDEX = 62

    /** Parse a raw `)]}'`-guarded place body (what the WebView reads out of
     *  `APP_INITIALIZATION_STATE`). Returns null for a place that isn't a transit stop. */
    fun parse(body: String): StopDepartures? = parse(GoogleResponse.parse(body))

    fun parse(root: JsonElement): StopDepartures? {
        val place = root.at(6) ?: return null
        val transit = findTransitNode(place) ?: return null
        val groups = transit.at(1).arr() ?: return null
        // Two layouts show up: a station/subway groups entries by LINE -> DIRECTION -> departures,
        // while a busy bus stop lists each upcoming departure FLAT, every one tagged with its own
        // route badge (the "14R" pill). Rather than assume one shape, walk each group's entries,
        // read the route badge + headsign + times off each, then GROUP by (route, direction) so the
        // 25 separate "route 14" departures collapse into one "14" row with its next few times.
        val merged = LinkedHashMap<String, MutableLine>()
        for (group in groups) {
            val mode = modeFor(group.at(1).str(), group)
            val entries = group.at(2).arr() ?: continue
            for (entry in entries) {
                for ((headsign, node) in directionsOf(entry)) {
                    val deps = collectDepartures(node)
                    if (deps.isEmpty()) continue
                    val badge = findBadge(entry) ?: findBadge(node)
                    val label = badge?.first ?: routeLabel(node, headsign)
                    val key = (label ?: "?") + "|" + (headsign ?: "")
                    val line = merged.getOrPut(key) {
                        MutableLine(label, mode, headsign, badge?.second, headwayOf(node))
                    }
                    line.add(deps)
                }
            }
        }
        if (merged.isEmpty()) throw CalibrationNeededException("stop departures: transit node found, 0 lines parsed")
        // Soonest-departing lines first, like Google's board.
        val lines = merged.values
            .map { it.build() }
            .filter { it.upcoming.isNotEmpty() || it.headsign != null }
            .sortedBy { it.upcoming.firstOrNull()?.epochSec ?: Long.MAX_VALUE }
            .take(MAX_LINES)
        if (lines.isEmpty()) throw CalibrationNeededException("stop departures: 0 lines after grouping")
        return StopDepartures(stationName = transit.at(0).str()?.takeIf { it.isNotBlank() }, lines = lines)
    }

    /** Accumulates all departures seen for one (route, direction) across the flat entry list. */
    private class MutableLine(
        val label: String?,
        val mode: TransitMode,
        val headsign: String?,
        val colorHex: String?,
        val headway: String?,
    ) {
        private val deps = ArrayList<StopDeparture>()
        private val seen = HashSet<String>()
        fun add(more: List<StopDeparture>) {
            for (d in more) if (d.clockText != null && seen.add(d.clockText)) deps.add(d)
        }
        fun build() = StopDepartureLine(
            label = label,
            mode = mode,
            headsign = headsign,
            colorHex = colorHex,
            headwayText = headway,
            upcoming = deps.sortedBy { it.epochSec ?: Long.MAX_VALUE }.take(MAX_TIMES),
        )
    }

    /** The direction sub-nodes of an entry. A station line nests them (each has a headsign [0] and its
     *  own departures); a flat bus departure has none, so the whole entry is one implicit direction. */
    private fun directionsOf(entry: JsonElement): List<Pair<String?, JsonElement>> {
        val d1 = entry.at(1).arr()
        if (d1 != null && d1.isNotEmpty() && d1.all { (it.at(0).str()?.length ?: 0) in 2..80 && hasTimeTuple(it, 0) }) {
            return d1.map { it.at(0).str() to it }
        }
        return listOf(firstHeadsign(entry) to entry)
    }

    /** The route badge Google draws as a coloured pill: `["<label>", <int>, "#fill", "#text"]` — the
     *  same shape as the itinerary line pills. First one found in the entry wins (its route). */
    private fun findBadge(node: JsonElement?, depth: Int = 0): Triple<String, String?, String?>? {
        if (node == null || depth > 10) return null
        val a = node as? JsonArray ?: return null
        val label = a.at(0).str()?.trim()
        val fill = a.at(2).str()
        // A route short name is short and alphanumeric-ish ("14", "14R", "38AX", "N", "M15-SBS"),
        // paired with a "#" fill; that combination is the pill and can't collide with a time/id node.
        if (label != null && label.length in 1..7 && label.any { it.isLetterOrDigit() } &&
            label.all { it.isLetterOrDigit() || it in "-/ " } && fill != null && fill.startsWith("#")
        ) {
            return Triple(label, fill, a.at(3).str()?.takeIf { it.startsWith("#") })
        }
        for (c in a) findBadge(c, depth + 1)?.let { return it }
        return null
    }

    /** Best-effort destination string for a flat bus departure (letters, not a tz/label/time). */
    private fun firstHeadsign(entry: JsonElement): String? {
        var found: String? = null
        fun walk(n: JsonElement?, depth: Int) {
            if (n == null || found != null || depth > 6) return
            val a = n as? JsonArray ?: return
            for (el in a) {
                (el as? JsonArray)?.let { walk(it, depth + 1) }
                val s = el.str()?.trim() ?: continue
                // A real destination reads like words ("Daly City", "Ferry Plaza") - require a space and
                // rule out ids/tokens/timezones/colors so a feature id can't masquerade as a headsign.
                if (found == null && s.length in 3..60 && ' ' in s && "/" !in s && ":" !in s &&
                    !s.startsWith("#") && !s.startsWith("0x") && s.any { it.isLetter() } && !TIME.matches(s)
                ) found = s
            }
        }
        walk(entry, 0)
        return found
    }

    /** The transit-services node hangs off the place at [TRANSIT_NODE_INDEX]; if that field
     *  has shifted, fall back to the first place child whose subtree holds a departure tuple. */
    private fun findTransitNode(place: JsonElement): JsonElement? {
        place.at(TRANSIT_NODE_INDEX)?.let { if (hasTimeTuple(it, 0)) return it }
        return place.arr()?.firstOrNull { hasTimeTuple(it, 0) }
    }

    private fun hasTimeTuple(node: JsonElement?, depth: Int): Boolean {
        if (node == null || depth > 12) return false
        val a = node as? JsonArray ?: return false
        if (isTimeTuple(a)) return true
        return a.any { hasTimeTuple(it, depth + 1) }
    }

    /** A departure time tuple: `[epoch, "<Area/City tz>", "4:35 AM", <offset>, epoch]`.
     *  Keyed off the timezone string (contains "/") + a clock time so it can't collide
     *  with the attribute/id arrays around it. */
    private fun isTimeTuple(a: JsonArray): Boolean {
        val tz = a.at(1).str() ?: return false
        val clock = a.at(2).str() ?: return false
        return "/" in tz && TIME.matches(clock)
    }

    private val TIME = Regex("""^\d{1,2}:\d{2}\s?[AP]M$""", RegexOption.IGNORE_CASE)

    /** All upcoming departures in a direction, in document order, de-duplicated by clock text.
     *  Real-time when Google's live epoch [0] differs from the timetable epoch [4]. */
    private fun collectDepartures(dir: JsonElement): List<StopDeparture> {
        val out = ArrayList<StopDeparture>()
        val seen = HashSet<String>()
        fun walk(n: JsonElement?, depth: Int) {
            if (n == null || depth > 9 || out.size >= MAX_TIMES * 2) return
            val a = n as? JsonArray ?: return
            if (isTimeTuple(a)) {
                val clock = a.at(2).str()
                if (clock != null && seen.add(clock)) {
                    val rt = a.at(0).long()
                    val sched = a.at(4).long()
                    out.add(StopDeparture(
                        clockText = clock,
                        epochSec = sched ?: rt,
                        realtime = rt != null && sched != null && rt != sched,
                    ))
                }
                return // don't descend into a matched tuple
            }
            a.forEach { walk(it, depth + 1) }
        }
        walk(dir, 0)
        return out
    }

    /** The running frequency pair `[<headwaySec>, "20 min"]`. Language-robust: the label's
     *  leading number must equal headwaySec/60, which rules out the id/attribute pairs. */
    private fun headwayOf(dir: JsonElement): String? {
        var found: String? = null
        fun walk(n: JsonElement?, depth: Int) {
            if (n == null || found != null || depth > 9) return
            val a = n as? JsonArray ?: return
            if (a.size == 2) {
                val sec = a.at(0).long()
                val label = a.at(1).str()
                if (sec != null && sec in 30..14_400 && label != null && label.length in 2..14) {
                    val lead = label.trim().takeWhile { it.isDigit() }
                    if (lead.isNotEmpty() && lead.toLongOrNull() == sec / 60) { found = label.trim(); return }
                }
            }
            a.forEach { walk(it, depth + 1) }
        }
        walk(dir, 0)
        return found
    }

    private val LABEL = Regex("""^[A-Za-z0-9]{1,4}$""")

    /** Best-effort route short name ("7"). Google appends it to a combined "headsign + label"
     *  string; take the trailing token of the longest direction string that starts with the
     *  headsign's leading word. Null when nothing clean matches (the board still reads fine). */
    private fun routeLabel(dir: JsonElement, headsign: String?): String? {
        val firstWord = headsign?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return null
        var best: String? = null
        fun walk(n: JsonElement?, depth: Int) {
            if (n == null || depth > 9) return
            val a = n as? JsonArray ?: return
            for (el in a) {
                (el as? JsonArray)?.let { walk(it, depth + 1) }
                val s = el.str()?.trim() ?: continue
                if (s != headsign && s.startsWith(firstWord) && ' ' in s) {
                    val tail = s.substringAfterLast(' ')
                    if (LABEL.matches(tail) && (best == null || s.length > (best!!.length))) best = s
                }
            }
        }
        walk(dir, 0)
        return best?.substringAfterLast(' ')
    }

    private fun modeFor(groupName: String?, group: JsonElement): TransitMode {
        val hay = StringBuilder()
        groupName?.let { hay.append(it).append(' ') }
        fun walk(n: JsonElement?, depth: Int) {
            if (n == null || depth > 4) return
            when (n) {
                is JsonArray -> n.forEach { walk(it, depth + 1) }
                else -> n.str()?.let { if (it.length < 30) hay.append(it).append(' ') }
            }
        }
        walk(group.at(1), 0)
        val s = hay.toString().lowercase()
        return when {
            "bus" in s -> TransitMode.BUS
            "tram" in s || "light rail" in s || "streetcar" in s || "lightrail" in s -> TransitMode.TRAM
            "subway" in s || "metro" in s || "underground" in s -> TransitMode.SUBWAY
            "train" in s || "rail" in s -> TransitMode.TRAIN
            "ferry" in s || "boat" in s -> TransitMode.FERRY
            else -> TransitMode.GENERIC
        }
    }

    private const val MAX_TIMES = 4
    private const val MAX_LINES = 24
}
