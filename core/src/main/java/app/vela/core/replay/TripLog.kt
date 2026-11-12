package app.vela.core.replay

import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavReplay

/**
 * The on-device trip-recording file format, in one place. `:app`'s `TripStore` does the file IO
 * and live recording; the *format* (and so the ability to re-read a shared `.csv` and audit it)
 * lives here in `:core` so the writer and the reader can't drift, and so a travel log can be run
 * back through [NavReplay] with no Android dependency.
 *
 * Plain CSV, one record per line:
 * - `META,<label>,<startedAt>,<destLat>,<destLng>` — header (written first)
 * - `RP,<encoded-polyline>` — the navigated route's blue line (optional)
 * - `RD,<distanceM>,<durationS>,<durationInTrafficS?>,<reason>,<flags>` — route totals; reason
 *   (start/reroute/faster/heal/stop-added) and flags (provisional;abbreviated;offline;traffic;
 *   source=X;steps=N;named=KofN, then how the steps were made, [Route.made]) are appended fields,
 *   absent on older trips
 * - `M,<type>,<lat>,<lng>,<distanceM>,<instruction>` — one per maneuver (instruction last; may hold commas)
 * - `<lat>,<lng>,<t>,<bearing>,<speed>,<offRoute>,<accuracy>,<provider>,<offRouteHits>` — one per
 *   recorded GPS fix (the last four are appended fields, absent on older trips)
 * - `K,<t>,<text>`: a nav decision (recheck offered / kept, reroute attempt, swap) or a note on
 *   what the drive ran on; no coordinates, no names, no spoken text
 * - `N,<t>,<kind>,<msSinceFix>,<lat>,<lng>,<speed>,<accuracy>,<bearing>`: what the nav engine was
 *   handed, once per call: kind `f` is a fix as it came, `h` the last good position an outlier
 *   hold kept, `d` a dead-reckoned point between fixes, [msSinceFix] after the last fix line.
 *   Empty fields were not passed. A replay feeds the engine these, so it sees what the drive saw.
 * - `RC,<t>,<stage>,<source>,<flags>,<etaS>,<distanceM>,<encoded-polyline>`: one route a router
 *   answered while the drive asked for one (a reroute, a recheck): Google's and the open router's
 *   each, taken or not, so a file says why the route it switched to was the one it got
 *
 * `N` and `RC` lines hold positions, so a shared copy leaves them out ([TripScrub] drops kinds it
 * does not list).
 *
 * The line kind is told by the first field, so [parsePoints] naturally ignores the non-fix lines
 * (their first field never parses as a latitude).
 */
object TripLog {

    /** One recorded GPS fix (the `:core`-side twin of `:app`'s `TripFix`). */
    data class Point(
        val lat: Double, val lng: Double, val t: Long, val bearing: Float, val speed: Float,
        val offRoute: Boolean = false, // the engine's live off-route flag at this fix (2026-07-16+ recordings)
        val accuracyM: Float? = null,  // the fix's reported accuracy (drives the corridor scaling)
        val provider: String? = null,  // "gps" / "network" / "fused" (2026-09-13+ recordings)
        val offRouteHits: Int? = null, // the engine's off-route debounce count at this fix (2026-09-13+)
    ) {
        val latLng: LatLng get() = LatLng(lat, lng)
    }

    /** A flight-recorder event line: [tag] "S" (text = the spoken line), "J" (text =
     *  "frames,janky,worstMs"), "B" (text = battery percent), "K" (text = a nav decision:
     *  recheck offered / kept / reroute attempt / swap; never a coordinate). */
    data class Event(val tag: String, val t: Long, val text: String)

    /** One call of the nav engine as the drive made it (an `N` line): [segment] is the route block
     *  it ran against, [afterFix] the fix line it followed (-1 before the first), and [kind] `f` (a
     *  fix), `h` (an outlier hold's last good position) or `d` (dead-reckoned). */
    data class Input(
        val segment: Int, val afterFix: Int, val msSinceFix: Long, val kind: Char,
        val lat: Double, val lng: Double, val speed: Double?, val accuracyM: Double?, val bearing: Double?,
    ) {
        val latLng: LatLng get() = LatLng(lat, lng)
        /** False for a point that is not this fix's own position (the engine's `fromFix`). */
        val fromFix: Boolean get() = kind == 'f'
    }

    /** The payload of an `N` line after its time (the writer adds `N,<t>,`). */
    fun inputPayload(kind: Char, msSinceFix: Long, loc: LatLng, speed: Double?, accuracyM: Double?, bearing: Double?): String =
        "$kind,${msSinceFix.coerceAtLeast(0L)},${loc.lat},${loc.lng},${speed ?: ""},${accuracyM ?: ""},${bearing ?: ""}"

    /** The payload of an `RC` line after its time. [flags] is `;`-separated words, no commas. */
    fun candidatePayload(stage: String, source: String, flags: List<String>, route: Route): String =
        "$stage,$source,${flags.joinToString(";") { it.replace(',', ' ').replace(';', ' ') }}," +
            "${(route.durationInTrafficSeconds ?: route.durationSeconds).toInt()},${route.distanceMeters.toInt()}," +
            PolylineCodec.encode(route.polyline)

    /** The drive's routing choices as a `routing:` note: what a replay needs to say things as the
     *  drive did, and what a reroute was asked with. Words and switches only. */
    fun routingNote(imperial: Boolean, avoidTolls: Boolean, avoidHighways: Boolean, avoidFerries: Boolean, traffic: Boolean, rechecks: Boolean, mode: String): String =
        "routing: units ${if (imperial) "imperial" else "metric"}, avoid tolls ${onOff(avoidTolls)}, highways ${onOff(avoidHighways)}, " +
            "ferries ${onOff(avoidFerries)}, google traffic ${onOff(traffic)}, rechecks ${onOff(rechecks)}, mode $mode"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /** The units the drive spoke in, from its `routing:` note; null for a file from before it. */
    fun recordedImperial(events: List<Event>): Boolean? = events.firstOrNull { it.tag == "K" && it.text.startsWith("routing: ") }?.text?.let {
        when {
            "units imperial" in it -> true
            "units metric" in it -> false
            else -> null
        }
    }

    /** A route block and the fix index it became ACTIVE at. A mid-trip block records the drive
     *  SWITCHING routes there (a reroute, an accepted faster route, or a restarted navigation) —
     *  replay/audit must swap to it at that fix, never mash all blocks into one route. */
    data class RouteSegment(val route: Route, val fromPoint: Int, val reason: String? = null, val flags: String? = null)

    data class Parsed(
        val label: String,
        val startedAt: Long,
        val dest: LatLng?,
        val versionCode: Int? = null, // build that recorded the trip (null on pre-2026-07-16 files)
        val events: List<Event> = emptyList(), // flight-recorder lines (spoken/jank/battery)
        val route: Route?, // the FIRST segment's route (compat convenience)
        val points: List<Point>,
        val segments: List<RouteSegment> = emptyList(),
        /** What the nav engine was handed, in order (empty for a file from before `N` lines). */
        val inputs: List<Input> = emptyList(),
    )

    /** The route block (`RP`/`RD`/`M` lines) appended to a trip after its `META` line. */
    fun encodeRoute(route: Route, reason: String = ""): String = buildString {
        append("RP,").append(PolylineCodec.encode(route.polyline)).append('\n')
        // reason LAST on RD (appended field, 2026-07-16): "start"/"reroute"/"faster"/"heal"/
        // "stop-added" - the file distinguishes a wrong turn from a chosen faster route. Old
        // parsers read RD by index and ignore extras.
        // Provenance LAST on RD (appended field, 2026-09-13): which kind of route was driven. The
        // faster-route swap that adopted one of Google's alternates raw was invisible in the file
        // until the maneuver lines were read by hand; "provisional" here would have said it at
        // once. Old parsers ignore extras.
        val flags = buildList {
            if (route.provisional) add("provisional")
            if (route.abbreviatedSteps) add("abbreviated")
            if (route.offline) add("offline")
            if (route.hasLiveTraffic) add("traffic")
            if (route.source != app.vela.core.model.RouteSource.UNKNOWN) add("source=" + route.source.name)
            add("steps=${route.maneuvers.size}")
            // How many of the turns say where they go, and how the steps were made: the totals
            // line survives a share's trim, the steps at either end do not.
            add("named=${route.namedTurns}of${route.turns}")
            // A trip through stops whose router timed each leg: the stop figures were its own.
            if (route.legTimes.size >= 2) add("legs=${route.legTimes.size}")
            route.made.split(';').filter { it.isNotBlank() }.forEach { add(it.replace(',', ' ').replace('\n', ' ')) }
        }.joinToString(";")
        append("RD,${route.distanceMeters},${route.durationSeconds},${route.durationInTrafficSeconds ?: ""},$reason,$flags\n")
        // The congestion the drive was shown, as level:startMeters:lengthMeters along the line:
        // a replay paints the same colors, and a file says what traffic the route was chosen on.
        // A shared copy leaves the line out (TripScrub drops kinds it does not list): the route
        // is trimmed there, and offsets along the untrimmed line would paint the wrong stretches.
        if (route.trafficSpans.isNotEmpty()) {
            append("T,").append(route.trafficSpans.joinToString(";") { "${it.level}:${it.startMeters.toInt()}:${it.lengthMeters.toInt()}" }).append('\n')
        }
        for (m in route.maneuvers) {
            val instr = m.instruction.replace('\n', ' ').replace("\r", "").replace('\t', ' ')
            // The road entered and its ref ride after the text, tab-separated: a replay needs them
            // for the turn's own road bubble and the shield. They are words the text already
            // holds, and they leave with the line when a share trims it.
            // Then the step's own time: the remaining-time figure is the sum of the steps ahead,
            // so without it a replay's ETA was made of zeros.
            val names = "\t${m.road.orEmpty().replace('\t', ' ')}\t${m.ref.orEmpty().replace('\t', ' ')}\t${m.durationSeconds}"
            append("M,${m.type.name},${m.location.lat},${m.location.lng},${m.distanceMeters},$instr$names\n")
        }
    }

    /** Rebuild the FIRST saved [Route] from a trip's lines (null if it has no `RP` line — an older
     *  trip). NB this is the first SEGMENT only — the old implementation grabbed the first polyline
     *  but EVERY `M` line in the file, so a trip whose drive switched routes (a second RP/RD/M
     *  block) came back as a Franken-route with two DEPART→ARRIVE runs stitched together: replays
     *  "arrived" mid-drive, card distances read km wrong, and the arrow matched the wrong half. */
    fun parseRoute(lines: List<String>): Route? = parseParsed(lines).segments.firstOrNull()?.route

    /** The GPS fixes (every `lat,lng,t,bearing,speed` line; route/META lines are skipped). */
    fun parsePoints(lines: List<String>): List<Point> = lines.mapNotNull { parseFix(it) }

    /** What the saved-trip list shows beside a trip's name: how many fixes, how far, how long. */
    data class Stats(val fixes: Int, val distanceM: Double, val durationMs: Long)

    /**
     * One pass over a trip's lines for the list row. [lines] is a Sequence so a long drive is
     * read without holding the file in memory. Distance is the sum of fix-to-fix steps; duration
     * runs from the first to the last fix that carries a timestamp.
     */
    fun stats(lines: Sequence<String>): Stats {
        var fixes = 0
        var dist = 0.0
        var prevLat = 0.0
        var prevLng = 0.0
        var firstT = 0L
        var lastT = 0L
        for (line in lines) {
            val lat = line.substringBefore(',').toDoubleOrNull() ?: continue
            val p = line.split(',', limit = 4)
            val lng = p.getOrNull(1)?.toDoubleOrNull() ?: continue
            val t = p.getOrNull(2)?.toLongOrNull() ?: 0L
            if (fixes > 0) dist += LatLng(prevLat, prevLng).distanceTo(LatLng(lat, lng))
            prevLat = lat
            prevLng = lng
            if (t > 0L) {
                if (firstT == 0L) firstT = t
                lastT = t
            }
            fixes++
        }
        return Stats(fixes, dist, (lastT - firstT).coerceAtLeast(0L))
    }

    private fun parseFix(line: String): Point? {
        val p = line.split(',')
        if (p.size < 5) return null
        val lat = p[0].toDoubleOrNull() ?: return null
        val lng = p[1].toDoubleOrNull() ?: return null
        return Point(
            lat, lng, p[2].toLongOrNull() ?: 0L, p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f,
            offRoute = p.getOrNull(5) == "1",
            accuracyM = p.getOrNull(6)?.toFloatOrNull(),
            provider = p.getOrNull(7)?.takeIf { it.isNotBlank() },
            offRouteHits = p.getOrNull(8)?.toIntOrNull(),
        )
    }

    private val ROAD_IN_TEXT = Regex("(?: onto |^Head out on |^Continue on )([^,:]+?)(?:, | toward |$)")

    private fun parseManeuver(line: String): Maneuver? {
        val p = line.split(',', limit = 6)
        if (p.size < 6) return null
        val type = runCatching { ManeuverType.valueOf(p[1]) }.getOrDefault(ManeuverType.UNKNOWN)
        val lat = p[2].toDoubleOrNull() ?: return null
        val lng = p[3].toDoubleOrNull() ?: return null
        val text = p[5].split('\t')
        return Maneuver(
            type, text[0], LatLng(lat, lng), p[4].toDoubleOrNull() ?: 0.0, text.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
            // A file from before the names were kept: the English text still says the road
            // ("... onto 1st Street"), which is enough for the replay's road label and callout.
            road = text.getOrNull(1)?.ifBlank { null } ?: if (text.size == 1) ROAD_IN_TEXT.find(text[0])?.groupValues?.get(1)?.trim()?.ifBlank { null } else null,
            ref = text.getOrNull(2)?.ifBlank { null },
        )
    }

    fun parse(csv: String): Parsed = parseParsed(csv.split('\n').filter { it.isNotBlank() })

    /** SEQUENTIAL, segment-aware parse: each `RP` starts a new route block; the fixes seen so far
     *  give the block its activation index. Order in the file is the order it happened. */
    private fun parseParsed(lines: List<String>): Parsed {
        val meta = lines.firstOrNull { it.startsWith("META,") }?.removePrefix("META,")?.split(',').orEmpty()
        val label = meta.getOrNull(0)?.ifBlank { "Trip" } ?: "Trip"
        val startedAt = meta.getOrNull(1)?.toLongOrNull() ?: 0L
        val dest = meta.getOrNull(2)?.toDoubleOrNull()?.let { la ->
            meta.getOrNull(3)?.toDoubleOrNull()?.let { lo -> LatLng(la, lo) }
        }
        val versionCode = meta.getOrNull(4)?.toIntOrNull() // absent on trips recorded before 2026-07-16
        val points = ArrayList<Point>()
        val segments = ArrayList<RouteSegment>()
        val events = ArrayList<Event>()
        val inputs = ArrayList<Input>()
        var rp: String? = null
        var rd: List<String> = emptyList()
        var spans: List<app.vela.core.model.TrafficSpan> = emptyList()
        var ms = ArrayList<Maneuver>()
        var from = 0
        fun closeBlock() {
            val poly = rp?.let { PolylineCodec.decode(it) } ?: return
            rp = null
            if (poly.size < 2) return
            val distM = rd.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val durS = rd.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val trafficS = rd.getOrNull(2)?.toDoubleOrNull()
            val reason = rd.getOrNull(3)?.takeIf { it.isNotBlank() }
            val flags = rd.getOrNull(4)?.takeIf { it.isNotBlank() }
            // The flags ride back onto the route so a replay reads the same provenance the drive
            // saw; files older than the source field come back UNKNOWN with the booleans intact.
            val flagSet = flags?.split(';')?.toSet().orEmpty()
            val source = flagSet.firstOrNull { it.startsWith("source=") }
                ?.let { runCatching { app.vela.core.model.RouteSource.valueOf(it.substringAfter('=')) }.getOrNull() }
                ?: app.vela.core.model.RouteSource.UNKNOWN
            // A file from before the steps kept their times: share the route's time out by length,
            // so the replay's remaining time counts down instead of reading nothing.
            val steps = if (ms.none { it.durationSeconds > 0.0 } && durS > 0.0) {
                val total = ms.sumOf { it.distanceMeters }.takeIf { it > 0.0 }
                if (total == null) ms.toList() else ms.map { it.copy(durationSeconds = durS * it.distanceMeters / total) }
            } else ms.toList()
            segments += RouteSegment(
                Route(
                    poly, listOf(RouteLeg(distM, durS, trafficS, steps)), distM, durS, trafficS,
                    trafficSpans = spans,
                    provisional = "provisional" in flagSet, abbreviatedSteps = "abbreviated" in flagSet,
                    offline = "offline" in flagSet, source = source,
                ),
                from,
                reason,
                flags,
            )
        }
        for (line in lines) {
            when {
                line.startsWith("META,") -> Unit
                line.startsWith("RP,") -> {
                    closeBlock()
                    rp = line.substring(3)
                    rd = emptyList()
                    spans = emptyList()
                    ms = ArrayList()
                    from = points.size
                }
                line.startsWith("RD,") -> rd = line.substring(3).split(',')
                line.startsWith("T,") -> spans = line.substring(2).split(';').mapNotNull { s ->
                    val f = s.split(':')
                    val level = f.getOrNull(0)?.toIntOrNull(); val start = f.getOrNull(1)?.toDoubleOrNull(); val len = f.getOrNull(2)?.toDoubleOrNull()
                    if (level != null && start != null && len != null) app.vela.core.model.TrafficSpan(level, start, len) else null
                }
                line.startsWith("M,") -> parseManeuver(line)?.let { ms.add(it) }
                // The block an input ran against is the one open when it was written.
                line.startsWith("N,") -> parseInput(line, if (rp != null) segments.size else segments.size - 1, points.size - 1)?.let { inputs.add(it) }
                line.startsWith("RC,") -> Unit
                line.startsWith("S,") || line.startsWith("J,") || line.startsWith("B,") || line.startsWith("K,") -> {
                    val e = line.split(',', limit = 3)
                    val t = e.getOrNull(1)?.toLongOrNull()
                    if (t != null) events.add(Event(e[0], t, e.getOrNull(2).orEmpty()))
                }
                else -> parseFix(line)?.let { points.add(it) }
            }
        }
        closeBlock()
        return Parsed(label, startedAt, dest, versionCode, events, segments.firstOrNull()?.route, points, segments, inputs)
    }

    private fun parseInput(line: String, segment: Int, afterFix: Int): Input? {
        val p = line.split(',')
        if (p.size < 9) return null
        val kind = p[2].singleOrNull() ?: return null
        return Input(
            segment.coerceAtLeast(0), afterFix, p[3].toLongOrNull() ?: 0L, kind,
            p[4].toDoubleOrNull() ?: return null, p[5].toDoubleOrNull() ?: return null,
            p[6].toDoubleOrNull(), p[7].toDoubleOrNull(), p[8].toDoubleOrNull(),
        )
    }

    /**
     * One-call audit of a shared trip CSV: replay the fixes through [NavReplay], SEGMENT-AWARE —
     * each recorded route block is audited against exactly the fixes driven on it, and the pieces
     * are merged into one report (maneuver/fix indices offset to stay file-global). Null if the
     * trip has no saved route. This is the entry point for "user shipped a travel log — show me
     * where the guidance diverged".
     */
    fun audit(csv: String, imperial: Boolean = true): NavReplay.Report? {
        val parsed = parse(csv)
        if (parsed.segments.isEmpty()) return null
        val cards = ArrayList<NavReplay.CardSnapshot>()
        val maneuvers = ArrayList<NavReplay.ManeuverDiff>()
        var manOffset = 0
        val units = recordedImperial(parsed.events) ?: imperial
        parsed.segments.forEachIndexed { i, seg ->
            val fromIdx = seg.fromPoint.coerceIn(0, parsed.points.size)
            val toIdx = (parsed.segments.getOrNull(i + 1)?.fromPoint ?: parsed.points.size)
                .coerceIn(fromIdx, parsed.points.size)
            val inSeg = parsed.inputs.filter { it.segment == i }
            val r = if (parsed.inputs.isNotEmpty()) {
                // What the engine was handed, bearings included: an input carries one only when
                // the drive passed one. A card's fix index is then the fix the input followed.
                val rr = NavReplay.analyze(seg.route, inSeg.map { it.latLng }, units, speeds = inSeg.map { it.speed }, bearings = inSeg.map { it.bearing })
                rr.copy(cards = rr.cards.map { c -> c.copy(fixIndex = (inSeg.getOrNull(c.fixIndex)?.afterFix ?: fromIdx).coerceAtLeast(0)) })
            } else {
                val pts = parsed.points.subList(fromIdx, toIdx)
                // Speed only: a fix the phone gave no bearing is recorded with 0, which reads as north.
                val rr = NavReplay.analyze(seg.route, pts.map { it.latLng }, units, speeds = pts.map { it.speed.toDouble() })
                rr.copy(cards = rr.cards.map { it.copy(fixIndex = it.fixIndex + fromIdx) })
            }
            cards += r.cards
            maneuvers += r.maneuvers.map { it.copy(index = it.index + manOffset) }
            manOffset += seg.route.maneuvers.size
        }
        return NavReplay.Report(cards, maneuvers)
    }
    /** Longest a trip label may be. Long enough for a real description, short enough that the
     *  header stays a header. */
    private const val MAX_LABEL = 80

    /**
     * Rewrite a trip's label, returning the new file lines, or null when [lines] is not a trip
     * (no META header) - the caller must then leave the file alone rather than write something
     * unreadable over a recording.
     *
     * Lives here, beside the format it edits, so the rename can never drift from what the parser
     * expects; `:app`'s TripStore does the file IO around it.
     *
     * The label is sanitized the same way the writer sanitizes it: a comma would shift every
     * later field of the header by one (the destination would be read as the start time), and a
     * newline would turn the header into two records, the second of which parses as garbage.
     * Only field 0 changes; the start time, destination and version code are carried through
     * untouched, and every line after the header is untouched.
     */
    fun renameHeader(lines: List<String>, label: String): List<String>? {
        val first = lines.firstOrNull() ?: return null
        if (!first.startsWith("META,")) return null
        val safe = label.replace(',', ' ').replace('\n', ' ').trim().take(MAX_LABEL).ifBlank { "Trip" }
        val rest = first.removePrefix("META,").split(',').drop(1)
        return listOf((listOf("META", safe) + rest).joinToString(",")) + lines.drop(1)
    }
}
