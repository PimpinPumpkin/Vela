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
 * - `META,<label>,<startedAt>,<destLat>,<destLng>` — header (written first)
 * - `RP,<encoded-polyline>` — the navigated route's blue line (optional)
 * - `RD,<distanceM>,<durationS>,<durationInTrafficS?>` — route totals (optional)
 * - `M,<type>,<lat>,<lng>,<distanceM>,<instruction>` — one per maneuver (instruction last; may hold commas)
 * - `<lat>,<lng>,<t>,<bearing>,<speed>` — one per recorded GPS fix
 *
 * The line kind is told by the first field, so [parsePoints] naturally ignores the non-fix lines
 * (their first field never parses as a latitude).
 */
object TripLog {

    /** One recorded GPS fix (the `:core`-side twin of `:app`'s `TripFix`). */
    data class Point(val lat: Double, val lng: Double, val t: Long, val bearing: Float, val speed: Float) {
        val latLng: LatLng get() = LatLng(lat, lng)
    }

    data class Parsed(
        val label: String,
        val startedAt: Long,
        val dest: LatLng?,
        val route: Route?,
        val points: List<Point>,
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

    /** Rebuild the saved [Route] from a trip's lines (null if it has no `RP` line — an older trip). */
    fun parseRoute(lines: List<String>): Route? {
        val rp = lines.firstOrNull { it.startsWith("RP,") } ?: return null
        val poly = PolylineCodec.decode(rp.substring(3))
        if (poly.size < 2) return null
        val rd = lines.firstOrNull { it.startsWith("RD,") }?.substring(3)?.split(',').orEmpty()
        val distM = rd.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val durS = rd.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val trafficS = rd.getOrNull(2)?.toDoubleOrNull()
        val maneuvers = lines.filter { it.startsWith("M,") }.mapNotNull { line ->
            val p = line.split(',', limit = 6)
            if (p.size < 6) return@mapNotNull null
            val type = runCatching { ManeuverType.valueOf(p[1]) }.getOrDefault(ManeuverType.UNKNOWN)
            val lat = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val lng = p[3].toDoubleOrNull() ?: return@mapNotNull null
            Maneuver(type, p[5], LatLng(lat, lng), p[4].toDoubleOrNull() ?: 0.0, 0.0)
        }
        return Route(poly, listOf(RouteLeg(distM, durS, trafficS, maneuvers)), distM, durS, trafficS)
    }

    /** The GPS fixes (every `lat,lng,t,bearing,speed` line; route/META lines are skipped). */
    fun parsePoints(lines: List<String>): List<Point> = lines.mapNotNull { line ->
        val p = line.split(',')
        if (p.size < 5) return@mapNotNull null
        val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null
        val lng = p[1].toDoubleOrNull() ?: return@mapNotNull null
        Point(lat, lng, p[2].toLongOrNull() ?: 0L, p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f)
    }

    fun parse(csv: String): Parsed {
        val lines = csv.split('\n').filter { it.isNotBlank() }
        val meta = lines.firstOrNull { it.startsWith("META,") }?.removePrefix("META,")?.split(',').orEmpty()
        val label = meta.getOrNull(0)?.ifBlank { "Trip" } ?: "Trip"
        val startedAt = meta.getOrNull(1)?.toLongOrNull() ?: 0L
        val dest = meta.getOrNull(2)?.toDoubleOrNull()?.let { la ->
            meta.getOrNull(3)?.toDoubleOrNull()?.let { lo -> LatLng(la, lo) }
        }
        return Parsed(label, startedAt, dest, parseRoute(lines), parsePoints(lines))
    }

    /**
     * One-call audit of a shared trip CSV: parse it and replay the fixes through [NavReplay].
     * Null if the trip has no saved route (nothing to diff the cards/voice against). This is the
     * entry point for "user shipped a travel log — show me where the guidance diverged".
     */
    fun audit(csv: String, imperial: Boolean = true): NavReplay.Report? {
        val parsed = parse(csv)
        val route = parsed.route ?: return null
        return NavReplay.analyze(route, parsed.points.map { it.latLng }, imperial)
    }
}
