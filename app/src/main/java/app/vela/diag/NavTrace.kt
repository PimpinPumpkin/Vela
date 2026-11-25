package app.vela.diag

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Opt-in **nav smoothness trace** (issue #251): a per-frame record of the numbers behind the nav
 * camera, so a "the map swims / the puck jitters" report can be diagnosed from a REAL drive
 * instead of a simulated one. Recording only runs while navigating and only when the user turns
 * it on in Settings > Diagnostics.
 *
 * **It deliberately holds NO position data** - no latitude, longitude, street, or timestamp of
 * day. Every column is either a bearing, a distance ALONG the route, a speed, or a frame timing,
 * which is everything needed to separate the three candidate causes (frame drops vs route
 * geometry vs fix cadence) and nothing that says where the drive happened. That is what makes the
 * file safe to attach to a public issue, unlike a recorded trip, which carries the raw GPS trail
 * and must never be posted (see the location-hygiene rule in CLAUDE.md).
 *
 * Cheap by construction: one primitive-array append per frame into a bounded ring, no allocation
 * per row beyond the row itself, no I/O on the frame.
 *
 * While a trip is recording ([TripStore][app.vela.replay.TripStore] attaches it), the rows also
 * go to that trip's own file (`files/navtrace/<trip id>.csv`, [TripNavTrace]), written every
 * [FLUSH_ROWS] rows on a background thread, so the trip's share carries the trace of that drive
 * and a restart loses at most a few seconds of it. Those files are not backed up.
 */
object NavTrace {
    /** Settings > Diagnostics toggle, persisted in `vela_settings`. Off by default. */
    val enabled = mutableStateOf(false)

    // ~20 min at 60 fps. A full ring drops its OLDEST rows: a long drive keeps the most recent
    // stretch, which is the part the reporter just watched go wrong.
    private const val CAP = 72_000
    private val rows = ArrayDeque<FloatArray>(1024)
    // Nav decisions (reroute, faster route, silent upgrades) on the same clock as the rows. The
    // text is NavSession's note, which never carries a coordinate (the K-line contract).
    private val events = ArrayDeque<Pair<Float, String>>()

    @Volatile private var t0 = 0L

    /** The recording trip's trace file, its start, and the wall clock minus elapsedRealtime. */
    private class TripSink(val file: File, val startedAt: Long, val wallOffsetMs: Long, val header: String) {
        @Volatile var capped = false
    }
    @Volatile private var trip: TripSink? = null
    // Rows waiting for the trip's file, guarded by `rows`.
    private var pending = ArrayList<DoubleArray>(FLUSH_ROWS)
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "VelaNavTraceIO").apply { isDaemon = true } }
    }

    /** About five seconds of frames at 60 fps. */
    private const val FLUSH_ROWS = 300

    /** A trip's trace stops growing here (about an hour and a half at 60 fps). */
    private const val TRIP_MAX_BYTES = 32L * 1024 * 1024

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, on: Boolean) {
        enabled.value = on
        prefs(context).edit().putBoolean(KEY, on).apply()
        if (!on) clear()
    }

    // `adb shell setprop debug.vela.tune.navTraceLog 1` also writes each row to logcat (tag
    // VelaNavTrace) on the elapsedRealtime clock, so a mock drive's fix times line up with what was
    // drawn without exporting the file. The dial is read every 5 s, not per frame.
    @Volatile private var logRows = false
    @Volatile private var logCheckedMs = 0L

    private fun logging(nowMs: Long): Boolean {
        if (nowMs - logCheckedMs > 5_000L) {
            logCheckedMs = nowMs
            logRows = app.vela.ui.AppTune.localOn("navTraceLog")
        }
        return logRows
    }

    /** Whether the ticker should call [record] this frame. */
    fun active(nowMs: Long): Boolean = enabled.value || logging(nowMs)

    /** A fix as the puck took it: meters along the route, the speed and its age. Logcat only. */
    fun fix(elapsedMs: Long, alongM: Double, speed: Double, ageMs: Long, accepted: Boolean) {
        if (!logging(elapsedMs)) return
        android.util.Log.d(
            "VelaNavTrace",
            String.format(java.util.Locale.US, "fix er=%d m=%.2f v=%.2f age=%d ok=%d", elapsedMs, alongM, speed, ageMs, if (accepted) 1 else 0),
        )
    }

    /** Called once per nav frame from the map's motion ticker. No-op unless recording. */
    fun record(
        elapsedMs: Long,
        progressM: Double,
        speed: Double,
        windowM: Double,
        chordBearing: Float,
        displayBearing: Float,
        cameraBearing: Double,
        frameDt: Float,
        /** The yaw rate turning the arrow (GyroTurn), NaN when the arrow follows the road ahead. */
        gyroDps: Float,
        /** Meters along the route of the corner the arrow waits at (corner hold), NaN when none. */
        holdM: Double = Double.NaN,
        /** The bearing the camera eases toward this frame, NaN when it follows the arrow. */
        cameraTarget: Float = Float.NaN,
    ) {
        if (logging(elapsedMs)) {
            android.util.Log.d(
                "VelaNavTrace",
                String.format(
                    java.util.Locale.US, "row er=%d p=%.2f v=%.2f win=%.1f chord=%.1f disp=%.1f cam=%.1f gyro=%.1f hold=%.1f",
                    elapsedMs, progressM, speed, windowM, chordBearing, displayBearing, cameraBearing, gyroDps, holdM,
                ),
            )
        }
        if (!enabled.value) return
        val sink = trip
        synchronized(rows) {
            if (t0 == 0L) t0 = elapsedMs
            if (rows.size >= CAP) rows.removeFirst()
            rows.addLast(
                floatArrayOf(
                    (elapsedMs - t0) / 1000f, progressM.toFloat(), speed.toFloat(), windowM.toFloat(),
                    chordBearing, displayBearing, cameraBearing.toFloat(), frameDt, gyroDps,
                ),
            )
            if (sink != null && !sink.capped) {
                pending.add(
                    doubleArrayOf(
                        (elapsedMs + sink.wallOffsetMs - sink.startedAt).toDouble(), progressM, speed, windowM,
                        chordBearing.toDouble(), displayBearing.toDouble(), cameraBearing, cameraTarget.toDouble(),
                        frameDt * 1000.0, gyroDps.toDouble(), holdM,
                    ),
                )
                if (pending.size >= FLUSH_ROWS) flushLocked()
            }
        }
    }

    /** Start writing rows to the trace file of the trip [id], which began at [startedAt] (wall
     *  clock). A resumed trip appends to the file it already has. */
    fun attachTrip(context: Context, id: String, startedAt: Long) {
        val sink = runCatching {
            TripSink(
                tripFile(context, id), startedAt,
                System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime(), header(context),
            )
        }.getOrNull() ?: return
        synchronized(rows) { flushLocked(); trip = sink }
    }

    /** Stop writing to the recording trip's file, after what is waiting has gone to it. */
    fun detachTrip() {
        synchronized(rows) { flushLocked(); trip = null }
    }

    /** Delete the trace of the trip [id], after any write still queued for it. */
    fun deleteTrip(context: Context, id: String) {
        val f = runCatching { tripFile(context, id) }.getOrNull() ?: return
        writer.execute { runCatching { f.delete() } }
    }

    /** Delete every trip trace whose trip is not in [keep]. */
    fun pruneTrips(context: Context, keep: Set<String>) {
        val dir = runCatching { File(context.filesDir, DIR) }.getOrNull() ?: return
        writer.execute {
            dir.listFiles { f -> f.extension == "csv" && f.nameWithoutExtension !in keep }.orEmpty()
                .forEach { runCatching { it.delete() } }
        }
    }

    /** The trace saved with the trip [id], or null when it has none. Reads a file. */
    fun tripTrace(context: Context, id: String): String? = runCatching {
        tripFile(context, id).takeIf { it.isFile && it.length() > 0L }?.readText()
    }.getOrNull()

    /** Waits for the queued file writes. Tests only. */
    internal fun awaitWrites() { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }

    private fun tripFile(context: Context, id: String) = File(File(context.filesDir, DIR), "$id.csv")

    // Under `rows`. Hands what is waiting to the writer thread.
    private fun flushLocked() {
        val sink = trip
        if (sink == null || pending.isEmpty()) { pending.clear(); return }
        val batch = pending
        pending = ArrayList(FLUSH_ROWS)
        writer.execute { append(sink, batch) }
    }

    private fun append(sink: TripSink, batch: List<DoubleArray>) {
        runCatching {
            val f = sink.file
            f.parentFile?.mkdirs()
            val fresh = !f.isFile || f.length() == 0L
            if (!fresh && f.length() >= TRIP_MAX_BYTES) {
                if (!sink.capped) f.appendText("# stopped here: the trace reached ${TRIP_MAX_BYTES / (1024 * 1024)} MB\n")
                sink.capped = true
                return
            }
            java.io.FileWriter(f, true).buffered().use { w ->
                if (fresh) w.write(sink.header)
                for (r in batch) { w.write(app.vela.core.replay.TripNavTrace.format(r)); w.write("\n") }
            }
        }
    }

    /** The lines a file starts with: what it is, the build, the phone and the camera settings. */
    private fun header(context: Context): String = buildString {
        append("# Vela nav smoothness trace for one trip. No position data: bearings, along-route\n")
        append("# distance, speed and frame timings. t_ms counts from the trip's start (its META time).\n")
        append(buildLines(context))
        append(app.vela.core.replay.TripNavTrace.COLUMNS).append('\n')
    }

    // The build, phone and settings lines both exports start with.
    private fun buildLines(context: Context): String = buildString {
        // What a report is read against: without the build nobody can tell which camera ran.
        append("# vela ${app.vela.BuildConfig.VERSION_NAME} (build ${app.vela.BuildConfig.VERSION_CODE})\n")
        append("# android ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        val sp = runCatching { prefs(context) }.getOrNull()
        append("# settings: north_up_start=${sp?.getBoolean("nav_north_up", false)} texture_render=${sp?.getBoolean("texture_render", false)}" +
            " demo_drive=${sp?.getBoolean("demo_drive", false)} faster_route_auto=${sp?.getBoolean("faster_route_auto", true)}" +
            " gyro_turn=${app.vela.ui.GyroTurn.on.value}\n")
    }

    /** A nav decision, timed on the rows' clock. No-op unless recording. */
    fun event(text: String) {
        if (!enabled.value) return
        synchronized(rows) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (t0 == 0L) t0 = now
            if (events.size >= 2000) events.removeFirst()
            events.addLast((now - t0) / 1000f to text.replace('\n', ' ').replace(',', ';'))
        }
    }

    fun clear() {
        synchronized(rows) { rows.clear(); events.clear(); t0 = 0L }
    }

    fun isEmpty(): Boolean = synchronized(rows) { rows.isEmpty() }

    /** Write the ring to a CSV in the cache dir and hand back a share intent, or null if empty. */
    fun shareIntent(context: Context): Intent? {
        val (snapshot, evs) = synchronized(rows) { if (rows.isEmpty()) return null else rows.toList() to events.toList() }
        return runCatching {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val file = File(dir, "vela-nav-trace-${java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())}.csv")
            file.bufferedWriter().use { w ->
                w.write("# Vela nav smoothness trace. No position data: bearings, along-route\n")
                w.write("# distance, speed and frame timings only, safe to attach to an issue.\n")
                w.write(buildLines(context))
                w.write("# rows ${snapshot.size}, events ${evs.size}; events (route changes and why) are listed after the rows\n")
                w.write("t_s,progress_m,speed_mps,window_m,chord_deg,display_deg,camera_deg,frame_dt_s,gyro_dps\n")
                for (r in snapshot) {
                    // gyro_dps is empty while the arrow follows the road ahead (GyroTurn off or no reading).
                    val gyro = if (r[8].isNaN()) "" else r[8].toString()
                    w.write("${r[0]},${r[1]},${r[2]},${r[3]},${r[4]},${r[5]},${r[6]},${r[7]},$gyro\n")
                }
                w.write("\n# events\nt_s,event\n")
                for ((t, e) in evs) w.write("$t,$e\n")
            }
            shareFileIntent(
                context, file, "text/csv",
                "Vela nav smoothness trace",
                "Nav camera trace (no location data).",
                "Share nav trace",
            )
        }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY = "nav_trace"
    private const val DIR = "navtrace"
}
