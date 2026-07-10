package app.vela.core.replay

import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavReplay

/**
 * The on-device trip-recording file format, in one place. `:app`'s `TripStore` does the file IO
 * and live recording; the *format* (and so the ability to re-read a shared `.csv` and audit it)
 * lives here in `:core` so the writer and the reader can't drift, and so a travel log can be run
 * back through [NavReplay] with no Android dependency.
 *
 * Plain CSV, one record per line:
 * - `META,<label>,<startedAt>,<destLat>,<destLng>` - header (written first)
 * - `RP,<encoded-polyline>` - the navigated route's blue line (optional)
 * - `RD,<distanceM>,<durationS>,<durationInTrafficS?>` - route totals (optional)
 * - `M,<type>,<lat>,<lng>,<distanceM>,<instruction>` - one per maneuver (instruction last; may hold commas)
 * - `<lat>,<lng>,<t>,<bearing>,<speed>` - one per recorded GPS fix
 *
 * The line kind is told by the first field, so [parsePoints] naturally ignores the non-fix lines
 * (their first field never parses as a latitude).
 */
object TripLog {

    /** One recorded GPS fix (the `:core`-side twin of `:app`'s `TripFix`). */
    data class Point(val lat: Double, val lng: Double, val t: Long, val bearing: Float, val speed: Float) {
        val latLng: LatLng get() = LatLng(lat, lng)
    }

    /** A route block and the fix index it became ACTIVE at. A mid-trip block records the drive
     * SWITCHING routes there (a reroute, an accepted faster route, or a restarted navigation) -
     * replay/audit must swap to it at that fix, never mash all blocks into one route. */
    data class RouteSegment(val route: Route, val fromPoint: Int)

    data class Parsed(
        val label: String,
        val startedAt: Long,
        val dest: LatLng?,
        val route: Route?, // the FIRST segment's route (compat convenience)
        val points: List<Point>,
        val segments: List<RouteSegment> = emptyList(),
    )

    /** The route block (`RP`/`RD`/`M` lines) appended to a trip after its `META` line. */
    fun encodeRoute(route: Route): String = buildString {
        append("RP,").append(PolylineCodec.encode(route.polyline)).append('\n')
        append("RD,${route.distanceMeters},${route.durationSeconds},${route.durationInTrafficSeconds ?: ""}\n")
        for (m in route.maneuvers) {
            val instr = m.instruction.replace('\n', ' ').replace("\r", "")
            append("M,${m.type.name},${m.location.lat},${m.location.lng},${m.distanceMeters},$instr\n")
        }
    }

    /** Rebuild the FIRST saved [Route] from a trip's lines (null if it has no `RP` line - an older
     * trip). NB this is the first SEGMENT only - the old implementation grabbed the first polyline
     * but EVERY `M` line in the file, so a trip whose drive switched routes (a second RP/RD/M
     * block) came back as a Franken-route with two DEPART→ARRIVE runs stitched together: replays
     * "arrived" mid-drive, card distances read km wrong, and the arrow matched the wrong half. */
    fun parseRoute(lines: List<String>): Route? = parseParsed(lines).segments.firstOrNull()?.route

    /** The GPS fixes (every `lat,lng,t,bearing,speed` line; route/META lines are skipped). */
    fun parsePoints(lines: List<String>): List<Point> = lines.mapNotNull { parseFix(it) }

    private fun parseFix(line: String): Point? {
        val p = line.split(',')
        if (p.size < 5) return null
        val lat = p[0].toDoubleOrNull() ?: return null
        val lng = p[1].toDoubleOrNull() ?: return null
        return Point(lat, lng, p[2].toLongOrNull() ?: 0L, p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f)
    }

    private fun parseManeuver(line: String): Maneuver? {
        val p = line.split(',', limit = 6)
        if (p.size < 6) return null
        val type = runCatching { ManeuverType.valueOf(p[1]) }.getOrDefault(ManeuverType.UNKNOWN)
        val lat = p[2].toDoubleOrNull() ?: return null
        val lng = p[3].toDoubleOrNull() ?: return null
        return Maneuver(type, p[5], LatLng(lat, lng), p[4].toDoubleOrNull() ?: 0.0, 0.0)
    }

    fun parse(csv: String): Parsed = parseParsed(csv.split('\n').filter { it.isNotBlank() })

    /** SEQUENTIAL, segment-aware parse: each `RP` starts a new route block; the fixes seen so far
     * give the block its activation index. Order in the file is the order it happened. */
    private fun parseParsed(lines: List<String>): Parsed {
        val meta = lines.firstOrNull { it.startsWith("META,") }?.removePrefix("META,")?.split(',').orEmpty()
        val label = meta.getOrNull(0)?.ifBlank { "Trip" } ?: "Trip"
        val startedAt = meta.getOrNull(1)?.toLongOrNull() ?: 0L
        val dest = meta.getOrNull(2)?.toDoubleOrNull()?.let { la ->
            meta.getOrNull(3)?.toDoubleOrNull()?.let { lo -> LatLng(la, lo) }
        }
        val points = ArrayList<Point>()
        val segments = ArrayList<RouteSegment>()
        var rp: String? = null
        var rd: List<String> = emptyList()
        var ms = ArrayList<Maneuver>()
        var from = 0
        fun closeBlock() {
            val poly = rp?.let { PolylineCodec.decode(it) } ?: return
            rp = null
            if (poly.size < 2) return
            val distM = rd.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val durS = rd.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val trafficS = rd.getOrNull(2)?.toDoubleOrNull()
            segments += RouteSegment(
                Route(poly, listOf(RouteLeg(distM, durS, trafficS, ms.toList())), distM, durS, trafficS),
                from,
            )
        }
        for (line in lines) {
            when {
                line.startsWith("META,") -> Unit
                line.startsWith("RP,") -> {
                    closeBlock()
                    rp = line.substring(3)
                    rd = emptyList()
                    ms = ArrayList()
                    from = points.size
                }
                line.startsWith("RD,") -> rd = line.substring(3).split(',')
                line.startsWith("M,") -> parseManeuver(line)?.let { ms.add(it) }
                else -> parseFix(line)?.let { points.add(it) }
            }
        }
        closeBlock()
        return Parsed(label, startedAt, dest, segments.firstOrNull()?.route, points, segments)
    }

    /**
     * One-call audit of a shared trip CSV: replay the fixes through [NavReplay], SEGMENT-AWARE -
     * each recorded route block is audited against exactly the fixes driven on it, and the pieces
     * are merged into one report (maneuver/fix indices offset to stay file-global). Null if the
     * trip has no saved route. This is the entry point for "user shipped a travel log - show me
     * where the guidance diverged".
     */
    fun audit(csv: String, imperial: Boolean = true): NavReplay.Report? {
        val parsed = parse(csv)
        if (parsed.segments.isEmpty()) return null
        val cards = ArrayList<NavReplay.CardSnapshot>()
        val maneuvers = ArrayList<NavReplay.ManeuverDiff>()
        var manOffset = 0
        parsed.segments.forEachIndexed { i, seg ->
            val fromIdx = seg.fromPoint.coerceIn(0, parsed.points.size)
            val toIdx = (parsed.segments.getOrNull(i + 1)?.fromPoint ?: parsed.points.size)
                .coerceIn(fromIdx, parsed.points.size)
            val r = NavReplay.analyze(seg.route, parsed.points.subList(fromIdx, toIdx).map { it.latLng }, imperial)
            cards += r.cards.map { it.copy(fixIndex = it.fixIndex + fromIdx) }
            maneuvers += r.maneuvers.map { it.copy(index = it.index + manOffset) }
            manOffset += seg.route.maneuvers.size
        }
        return NavReplay.Report(cards, maneuvers)
    }
}
