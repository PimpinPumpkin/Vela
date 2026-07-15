package app.vela.core.data.google.parse

import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.long
import app.vela.core.data.google.str
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitStep
import app.vela.core.model.TransitStopTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parses the public-transit (`!3e3`) directions payload that Google embeds in
 * the maps SPA's `APP_INITIALIZATION_STATE` (we read it through a real WebView -
 * OkHttp gets a bot-degraded, driving-only reply, exactly like photos).
 *
 * Schema calibrated against a live Davis→Sacramento capture (2026-06-18):
 * trips         `root[0][1]`        (array of trip options + trailing metadata)
 * per trip, the SUMMARY node is `trip[0]`; `trip[1][0][1]` holds the per-stop
 * leg tree (board/alight/intermediate stops, delay, headsign, agency phone,
 * alerts - see [parseSteps]). Within the summary `s`:
 * distance    `s[2][1]`           ("15.0 miles")
 * duration    `s[3][1]`           ("45 min")
 * departure   `s[5][0]` = [epochSec, tz, "6:10 AM", …]
 * arrival     `s[5][1]` = [epochSec, tz, "6:55 AM", …]
 * agency      `s[6][4][0][0]`     ("Amtrak Chartered Vehicle")
 * lines       line nodes shaped  ["<name>", <int>, "#fill", "#textcolor"]
 * scattered through `s[14]` (the mode/line badge subtree); we
 * walk for them rather than pin a brittle per-leg index, since
 * Google interleaves mode-icon facets with line facets there.
 *
 * Every field is null-safe: a drifted index degrades one itinerary, it doesn't
 * crash the list. `parse` throws [CalibrationNeededException] only when the
 * whole shape has moved (0 itineraries parsed), which the UI surfaces as a
 * non-fatal "needs calibration" notice.
 */
object TransitParser {

    /** Parse a raw `)]}'`-guarded directions body (what the WebView reads out of
     * `APP_INITIALIZATION_STATE`). Kept here so `:app` - which has no JSON lib -
     * can hand over the string and stay out of kotlinx.serialization. [origin]/[dest]
     * (the trip endpoints) anchor the first/last walk legs for on-demand walk directions. */
    fun parse(body: String, origin: LatLng? = null, dest: LatLng? = null): List<TransitItinerary> =
        parse(GoogleResponse.parse(body), origin, dest)

    fun parse(root: JsonElement, origin: LatLng? = null, dest: LatLng? = null): List<TransitItinerary> {
        val trips = root.at(0, 1).arr()
            ?: throw CalibrationNeededException("transit trips (root[0][1])")
        val parsed = trips.mapNotNull { runCatching { parseItinerary(it, origin, dest) }.getOrNull() }
            .filter { it.durationText != null || it.departureText != null }
        if (parsed.isEmpty()) throw CalibrationNeededException("transit: 0 itineraries parsed")
        return parsed
    }

    private fun parseItinerary(trip: JsonElement, origin: LatLng?, dest: LatLng?): TransitItinerary {
        val t = trip.at(0) ?: trip // the trip's summary node
        val dep = t.at(5, 0)
        val arr = t.at(5, 1)
        val legsEl = trip.at(1, 0, 1)
        // The ride legs (those carrying a stop block at leg[5]) hold the operating agency's phone
        // and any service alerts; a walk leg has neither.
        val rideLegs = legsEl.arr()?.filter { it.at(5).arr() != null }.orEmpty()
        val agencyNode = rideLegs.firstNotNullOfOrNull { it.at(0, 6, 4, 0) }
        return TransitItinerary(
            departureEpochSec = dep.at(0).long(),
            arrivalEpochSec = arr.at(0).long(),
            departureText = dep.at(2).str(),
            arrivalText = arr.at(2).str(),
            durationText = t.at(3, 1).str(),
            distanceText = t.at(2, 1).str(),
            agency = t.at(6, 4, 0, 0).str() ?: agencyNode.at(0).str(),
            agencyPhone = agencyNode.at(4).str(),
            alerts = rideLegs.flatMap { parseAlerts(it.at(0, 9)) }.distinct(),
            fare = parseFare(t),
            lines = parseLines(t.at(14)),
            steps = assignWalkEndpoints(parseSteps(legsEl), origin, dest),
        )
    }

    /** Give each WALK leg its start/end coordinates so the UI can fetch turn-by-turn walking
     * directions on demand: from the previous ride's alight stop (or the trip origin) to the next
     * ride's board stop (or the trip destination). */
    private fun assignWalkEndpoints(steps: List<TransitStep>, origin: LatLng?, dest: LatLng?): List<TransitStep> {
        if (steps.none { it.line == null }) return steps
        return steps.mapIndexed { i, step ->
            if (step.line != null) step else {
                val from = (i - 1 downTo 0).firstNotNullOfOrNull { steps[it].alightStop?.location } ?: origin
                val to = (i + 1..steps.lastIndex).firstNotNullOfOrNull { steps[it].boardStop?.location } ?: dest
                step.copy(walkFrom = from, walkTo = to)
            }
        }
    }

    /** Service alerts on a ride leg: `leg[0][9]` is an array whose entries carry a
     * human title at `[2]` ("Route 9 / 9A - Southbound Midtown Detour"). */
    private fun parseAlerts(node: JsonElement?): List<String> =
        node.arr()?.mapNotNull { it.at(2).str()?.takeIf { s -> s.length in 4..140 } }.orEmpty()

    private val FARE = Regex("""^[$€£¥]\s?\d[\d.,]*(?:\s?[-–]\s?[$€£¥]?\d[\d.,]*)?$""")

    /** Best-effort fare: scan the trip summary for a currency-shaped string. Many US
     * agencies (Miami-Dade here) send none, so this is usually null - Google itself
     * then shows only "Tickets and information" + a phone, which we surface instead. */
    private fun parseFare(t: JsonElement): String? {
        var found: String? = null
        fun walk(n: JsonElement) {
            if (found != null) return
            when (n) {
                is JsonArray -> n.forEach(::walk)
                else -> n.str()?.trim()?.let { if (FARE.matches(it)) found = it }
            }
        }
        walk(t)
        return found
    }

    /** The ordered legs (walk/ride) for the drill-down - they live at
     * `trip[1][0][1]` in the SAME payload (no extra RPC). Each leg's summary is
     * `leg[0]` (duration `[3][1]`, distance `[2][1]`, mode/line badge `[14]`,
     * headsign `[14][2][1][0]`). A RIDE leg also carries the full stop block at
     * `leg[5]`: board `[5][0]`, alight `[5][1]`, stop count `[5][2]`, and the
     * ordered intermediate stops `[5][7]` - each stop node holding name `[0]`,
     * agency code `[1]`, and real-time/scheduled call-time tuples. Calibrated
     * against a live Miami→Aventura capture (2026-07-07). */
    private fun parseSteps(legs: JsonElement?): List<TransitStep> {
        val arr = legs.arr() ?: return emptyList()
        return arr.mapNotNull { runCatching { parseStep(it) }.getOrNull() }
            .filter { it.durationText != null || it.line != null }
    }

    private fun parseStep(leg: JsonElement): TransitStep {
        val sum = leg.at(0) ?: leg
        val line = parseLines(sum.at(14)).firstOrNull()
        val stops = leg.at(5) // present on ride legs only
        val board = parseStopTime(stops.at(0))
        val alight = parseStopTime(stops.at(1))
        val times = collectTimes(leg)
        return TransitStep(
            mode = line?.mode ?: TransitMode.WALK,
            durationText = sum.at(3, 1).str(),
            distanceText = sum.at(2, 1).str(),
            line = line,
            departText = board?.timeText ?: (if (line != null) times.firstOrNull() else null),
            arriveText = alight?.timeText ?: (if (line != null) times.lastOrNull() else null),
            headsign = sum.at(14, 2, 1, 0).str()?.takeIf { it.length in 2..80 },
            boardStop = board,
            alightStop = alight,
            numStops = stops.at(2).long()?.toInt()?.takeIf { it in 1..500 },
            delayText = delayText(stops.at(0)),
            intermediateStops = stops.at(7).arr()?.mapNotNull { parseStopTime(it) }.orEmpty(),
        )
    }

    /** One stop node → name + code + shown/scheduled call time. A node carries up to
     * four time tuples ([epochSec, tz, "h:mm AM", …]): real-time arrival/departure at
     * `[2]`/`[3]`, timetable arrival/departure at `[7]`/`[8]`. We show the real-time
     * value and keep the timetable one only when it differs (a delay). */
    private fun parseStopTime(node: JsonElement?): TransitStopTime? {
        val n = node ?: return null
        val name = n.at(0).str()?.takeIf { it.isNotBlank() } ?: return null
        val realtime = n.at(2, 2).str() ?: n.at(3, 2).str()
        val scheduled = n.at(7, 2).str() ?: n.at(8, 2).str()
        val lat = n.at(4, 2).dbl(); val lng = n.at(4, 3).dbl()
        // Signed minutes off the timetable (negative = early) so the row can colour a late call
        // differently from an early one - the feed carries both.
        val rtEpoch = n.at(2, 0).long() ?: n.at(3, 0).long()
        val schedEpoch = n.at(7, 0).long() ?: n.at(8, 0).long()
        val moved = realtime != null && scheduled != null && realtime != scheduled
        return TransitStopTime(
            name = name,
            code = n.at(1).str()?.takeIf { it.isNotBlank() },
            timeText = realtime ?: scheduled,
            scheduledText = scheduled?.takeIf { realtime != null && it != realtime },
            location = if (lat != null && lng != null) LatLng(lat, lng) else null,
            delayMin = if (moved && rtEpoch != null && schedEpoch != null) ((rtEpoch - schedEpoch) / 60.0).roundToInt() else null,
        )
    }

    /** "5 min late" / "2 min early" from a stop node's real-time vs timetable epoch. */
    private fun delayText(node: JsonElement?): String? {
        val n = node ?: return null
        val realtime = n.at(2, 0).long() ?: n.at(3, 0).long() ?: return null
        val scheduled = n.at(7, 0).long() ?: n.at(8, 0).long() ?: return null
        val diffMin = ((realtime - scheduled) / 60.0).roundToInt()
        return when {
            diffMin >= 1 -> "$diffMin min late"
            diffMin <= -1 -> "${abs(diffMin)} min early"
            else -> null
        }
    }

    private val TIME = Regex("""^\d{1,2}:\d{2}\s?[AP]M$""")

    /** Every "h:mm AM/PM" in a leg, in document order - board time first, alight last. */
    private fun collectTimes(leg: JsonElement): List<String> {
        val out = ArrayList<String>()
        fun walk(n: JsonElement) {
            when (n) {
                is JsonArray -> n.forEach(::walk)
                else -> n.str()?.let { if (TIME.matches(it)) out.add(it) }
            }
        }
        walk(leg)
        return out
    }

    /** Walk the badge subtree for transit-line nodes - `["<name>", <int>,
     * "#fill", "#text"]` - collecting them in document order, de-duplicated by
     * name. Walk legs carry no such node, so a walk-only segment contributes
     * nothing here (matching Google's compact card, which shows only the lines). */
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
            // length 1..60: single-digit route numbers ("9", "5") are real bus lines; the
            // `["name", int, "#fill", "#text"]` node shape + a "#…" fill keeps that from over-matching.
            if (name != null && name.length in 1..60 && fill != null && fill.startsWith("#") && seen.add(name)) {
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
     * "rail.png", …) or mode label in the badge subtree. Vehicle classes are
     * tested before WALK: line nodes only exist for ridden segments, so a trip
     * whose badges mention both "walk" and "bus" is a bus trip with a walk leg. */
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
