package app.vela.core.data.google.parse

import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.long
import app.vela.core.data.google.str
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Parses the public-transit (`!3e3`) directions payload that Google embeds in
 * the maps SPA's `APP_INITIALIZATION_STATE` (we read it through a real WebView —
 * OkHttp gets a bot-degraded, driving-only reply, exactly like photos).
 *
 * Schema calibrated against a live Davis→Sacramento capture (2026-06-18):
 *   trips         `root[0][1]`        (array of trip options + trailing metadata)
 *   per trip, the SUMMARY node is `trip[0]` (`trip[1]` holds the per-stop leg
 *   tree — a future drill-down); within that summary `s`:
 *     distance    `s[2][1]`           ("15.0 miles")
 *     duration    `s[3][1]`           ("45 min")
 *     departure   `s[5][0]` = [epochSec, tz, "6:10 AM", …]
 *     arrival     `s[5][1]` = [epochSec, tz, "6:55 AM", …]
 *     agency      `s[6][4][0][0]`     ("Amtrak Chartered Vehicle")
 *     lines       line nodes shaped  ["<name>", <int>, "#fill", "#textcolor"]
 *                 scattered through `s[14]` (the mode/line badge subtree); we
 *                 walk for them rather than pin a brittle per-leg index, since
 *                 Google interleaves mode-icon facets with line facets there.
 *
 * Every field is null-safe: a drifted index degrades one itinerary, it doesn't
 * crash the list. `parse` throws [CalibrationNeededException] only when the
 * whole shape has moved (0 itineraries parsed), which the UI surfaces as a
 * non-fatal "needs calibration" notice.
 */
object TransitParser {

    /** Parse a raw `)]}'`-guarded directions body (what the WebView reads out of
     *  `APP_INITIALIZATION_STATE`). Kept here so `:app` — which has no JSON lib —
     *  can hand over the string and stay out of kotlinx.serialization. */
    fun parse(body: String): List<TransitItinerary> = parse(GoogleResponse.parse(body))

    fun parse(root: JsonElement): List<TransitItinerary> {
        val trips = root.at(0, 1).arr()
            ?: throw CalibrationNeededException("transit trips (root[0][1])")
        val parsed = trips.mapNotNull { runCatching { parseItinerary(it) }.getOrNull() }
            .filter { it.durationText != null || it.departureText != null }
        if (parsed.isEmpty()) throw CalibrationNeededException("transit: 0 itineraries parsed")
        return parsed
    }

    private fun parseItinerary(trip: JsonElement): TransitItinerary {
        val t = trip.at(0) ?: trip // the trip's summary node
        val dep = t.at(5, 0)
        val arr = t.at(5, 1)
        return TransitItinerary(
            departureEpochSec = dep.at(0).long(),
            arrivalEpochSec = arr.at(0).long(),
            departureText = dep.at(2).str(),
            arrivalText = arr.at(2).str(),
            durationText = t.at(3, 1).str(),
            distanceText = t.at(2, 1).str(),
            agency = t.at(6, 4, 0, 0).str(),
            lines = parseLines(t.at(14)),
        )
    }

    /** Walk the badge subtree for transit-line nodes — `["<name>", <int>,
     *  "#fill", "#text"]` — collecting them in document order, de-duplicated by
     *  name. Walk legs carry no such node, so a walk-only segment contributes
     *  nothing here (matching Google's compact card, which shows only the lines). */
    private fun parseLines(badges: JsonElement?): List<TransitLine> {
        val root = badges.arr() ?: return emptyList()
        // The mode-icon facet ("bus2.png") sits in a sibling node of the line
        // facet, not inside it, so derive one dominant vehicle class for the whole
        // badge subtree and apply it to the lines (correct for single-mode trips,
        // a sane approximation for mixed ones).
        val mode = guessMode(root)
        val out = ArrayList<TransitLine>()
        val seen = HashSet<String>()
        fun walk(n: JsonElement) {
            val a = n as? JsonArray ?: return
            val name = a.getOrNull(0).str()
            val fill = a.getOrNull(2).str()
            if (name != null && name.length in 2..60 && fill != null && fill.startsWith("#") && seen.add(name)) {
                out.add(
                    TransitLine(
                        name = name.trim(),
                        mode = mode,
                        colorHex = fill,
                        textColorHex = a.getOrNull(3).str()?.takeIf { it.startsWith("#") },
                    )
                )
            }
            a.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** Infer the vehicle class from any icon filename ("bus2.png", "tram.png",
     *  "rail.png", …) or mode label in the badge subtree. Vehicle classes are
     *  tested before WALK: line nodes only exist for ridden segments, so a trip
     *  whose badges mention both "walk" and "bus" is a bus trip with a walk leg. */
    private fun guessMode(node: JsonElement): TransitMode {
        val hay = StringBuilder()
        fun walk(n: JsonElement) {
            when (n) {
                is JsonArray -> n.forEach(::walk)
                else -> n.str()?.let { if (it.length < 40) hay.append(it).append(' ') }
            }
        }
        walk(node)
        val s = hay.toString().lowercase()
        return when {
            "bus" in s -> TransitMode.BUS
            "tram" in s || "light rail" in s || "streetcar" in s || "lightrail" in s -> TransitMode.TRAM
            "subway" in s || "metro" in s -> TransitMode.SUBWAY
            "train" in s || "rail" in s -> TransitMode.TRAIN
            "ferry" in s || "boat" in s -> TransitMode.FERRY
            "walk" in s -> TransitMode.WALK
            else -> TransitMode.GENERIC
        }
    }
}
