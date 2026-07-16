package app.vela.replay

import android.content.Context
import android.location.Location
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.replay.TripLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One recorded GPS fix along a trip. */
data class TripFix(val lat: Double, val lng: Double, val t: Long, val bearing: Float, val speed: Float)

/** Header for a saved trip — enough to list + replay it. */
data class TripMeta(
    val id: String,
    val label: String,
    val startedAt: Long,
    val fixCount: Int,
    val dest: LatLng?,
)

/**
 * Records navigation trips (the GPS trace + destination) to on-device files, so a
 * drive can be **replayed** later for testing turn-by-turn without driving it again.
 * Opt-in (the "save my trips" telemetry toggle) — strictly local, never uploaded.
 *
 * Plain CSV (no JSON dep in `:app`): line 1 = `META,<label>,<startedAt>,<destLat>,<destLng>,
 * <versionCode>`, then one `lat,lng,t,bearing,speed,offRoute,accuracy` per fix. FLIGHT-RECORDER
 * lines (2026-07-16, all appended formats - every parser reads by index/prefix and skips what it
 * doesn't know, so old files and old parsers stay compatible): `S,<epochMs>,<text>` = a line the
 * voice actually SPOKE (the voice-vs-card questions answer themselves); `J,<epochMs>,<frames>,
 * <janky>,<worstMs>` = a 30 s UI-thread frame-pacing sample while navigating (Choreographer
 * cadence - catches UI/vsync stalls, though the GL map thread renders separately); `B,<epochMs>,
 * <pct>` = battery level every ~2 min (drain per drive becomes measurable).
 *
 * Optionally the navigated **route** is saved too (right after META), so a replay drives the
 * *same* blue line the user actually saw — not a fresh re-route — and an offline analysis can
 * regenerate exactly what the cards/voice said at each fix and diff it against the route's real
 * maneuver positions. Those lines are `RP,<encoded-polyline>`, `RD,<distM>,<durS>,<trafficS?>`,
 * and one `M,<type>,<lat>,<lng>,<distM>,<instruction>` per maneuver; [load] skips them (their
 * first field never parses as a latitude).
 */
@Singleton
class TripStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "trips").apply { mkdirs() }

    @Volatile private var active: File? = null
    private val lock = Any()

    /** Begin a trip; returns its id (or null if one's already recording). */
    fun startTrip(label: String, dest: LatLng?, startedAt: Long): String? = synchronized(lock) {
        if (active != null) return null
        val id = "trip_$startedAt"
        val f = File(dir, "$id.csv")
        val safe = label.replace(',', ' ').replace('\n', ' ').take(80).ifBlank { "Trip" }
        runCatching {
            // versionCode LAST: TripLog.parse reads META by index, so appending keeps old files
            // and old parsers compatible - and an audit can now say which build recorded a trip.
            f.writeText("META,$safe,$startedAt,${dest?.lat ?: ""},${dest?.lng ?: ""},${app.vela.BuildConfig.VERSION_CODE}\n")
            active = f
        }
        return id
    }

    /** Append a fix to the active trip (no-op if none is recording). */
    fun record(loc: Location, offRoute: Boolean = false) = synchronized(lock) {
        val f = active ?: return
        runCatching {
            // offRoute + accuracy LAST (appended columns, 2026-07-16): the parser reads fixes by
            // index, so old files and old parsers stay compatible. offRoute = which fixes the
            // engine considered off-route; accuracy = what the accuracy-scaled corridor actually
            // saw (an off-route audit is unreconstructable without it).
            f.appendText(
                "${loc.latitude},${loc.longitude},${loc.time},${loc.bearing},${loc.speed}," +
                    "${if (offRoute) 1 else 0},${loc.accuracy}\n",
            )
        }
        Unit
    }

    /** Append a flight-recorder EVENT line: [tag] is one of "S" (spoken), "J" (jank sample),
     *  "B" (battery). No-op unless a trip is recording. Commas inside [payload] are fine for S
     *  (the parser splits with a limit); J/B payloads are numeric. */
    fun note(tag: String, payload: String) = synchronized(lock) {
        val f = active ?: return
        runCatching { f.appendText("$tag,${System.currentTimeMillis()},${payload.replace('\n', ' ')}\n") }
        Unit
    }

    /**
     * Persist the navigated [route] (blue line + maneuvers) into the active trip. Written once,
     * right after [startTrip], so a later replay drives the exact same route and an analysis can
     * diff the spoken/printed directions against where the turns really are. No-op if no trip is
     * recording or the route is too short to be meaningful.
     */
    fun saveRoute(route: Route, reason: String = "") = synchronized(lock) {
        val f = active ?: return
        if (route.polyline.size < 2) return
        runCatching { f.appendText(TripLog.encodeRoute(route, reason)) }
        Unit
    }

    /** Close the active trip. Deletes it if it captured too few fixes to be useful. */
    fun finishTrip() = synchronized(lock) {
        val f = active ?: return
        active = null
        runCatching { if (countFixes(f) < 5) f.delete() }
        Unit
    }

    val isRecording: Boolean get() = active != null

    fun list(): List<TripMeta> = runCatching {
        dir.listFiles { f -> f.extension == "csv" }.orEmpty()
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.startedAt }
    }.getOrDefault(emptyList())

    fun load(id: String): List<TripFix> = runCatching {
        File(dir, "$id.csv").readLines().drop(1).mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 5) return@mapNotNull null
            TripFix(
                p[0].toDoubleOrNull() ?: return@mapNotNull null,
                p[1].toDoubleOrNull() ?: return@mapNotNull null,
                p[2].toLongOrNull() ?: 0L,
                p[3].toFloatOrNull() ?: 0f,
                p[4].toFloatOrNull() ?: 0f,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Re-read the route saved alongside a trip (the same blue line the user drove), or null if it
     * wasn't recorded — e.g. an older trip from before route-saving, in which case the caller
     * re-routes. Reconstructs a [Route] with a single leg holding all the maneuvers; enough for
     * [app.vela.core.nav.NavEngine] to replay turn-by-turn identically.
     */
    fun loadRoute(id: String): Route? =
        runCatching { TripLog.parseRoute(File(dir, "$id.csv").readLines()) }.getOrNull()

    /** The raw CSV for a saved trip, for export/share (or null if missing). */
    fun rawCsv(id: String): String? = runCatching { File(dir, "$id.csv").readText() }.getOrNull()

    fun delete(id: String) {
        runCatching { File(dir, "$id.csv").delete() }
        if (active?.name == "$id.csv") active = null
    }

    private fun readMeta(f: File): TripMeta? = runCatching {
        val first = f.bufferedReader().use { it.readLine() } ?: return null
        if (!first.startsWith("META,")) return null
        val p = first.removePrefix("META,").split(',')
        val label = p.getOrNull(0)?.ifBlank { "Trip" } ?: "Trip"
        val startedAt = p.getOrNull(1)?.toLongOrNull() ?: 0L
        val dest = p.getOrNull(2)?.toDoubleOrNull()?.let { la -> p.getOrNull(3)?.toDoubleOrNull()?.let { lo -> LatLng(la, lo) } }
        TripMeta(f.nameWithoutExtension, label, startedAt, countFixes(f), dest)
    }.getOrNull()

    // Count only the `lat,...` fix lines. A trip with a saved route also holds RP/RD/M
    // lines; lines-minus-header counted those as fixes, inflating the count and letting
    // a no-fix trip slip past the too-short-to-keep delete check.
    private fun countFixes(f: File): Int = runCatching {
        f.useLines { lines -> lines.count { it.substringBefore(',').toDoubleOrNull() != null } }
    }.getOrDefault(0)
}
