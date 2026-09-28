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
 * per row beyond the row itself, no I/O until the drive ends or the user exports.
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

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, on: Boolean) {
        enabled.value = on
        prefs(context).edit().putBoolean(KEY, on).apply()
        if (!on) clear()
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
    ) {
        if (!enabled.value) return
        synchronized(rows) {
            if (t0 == 0L) t0 = elapsedMs
            if (rows.size >= CAP) rows.removeFirst()
            rows.addLast(
                floatArrayOf(
                    (elapsedMs - t0) / 1000f, progressM.toFloat(), speed.toFloat(), windowM.toFloat(),
                    chordBearing, displayBearing, cameraBearing.toFloat(), frameDt,
                ),
            )
        }
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
                // What a report is read against: without the build nobody can tell which camera ran.
                w.write("# vela ${app.vela.BuildConfig.VERSION_NAME} (build ${app.vela.BuildConfig.VERSION_CODE})\n")
                w.write("# android ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                val sp = prefs(context)
                w.write("# settings: north_up_start=${sp.getBoolean("nav_north_up", false)} texture_render=${sp.getBoolean("texture_render", false)}" +
                    " demo_drive=${sp.getBoolean("demo_drive", false)} faster_route_auto=${sp.getBoolean("faster_route_auto", true)}\n")
                w.write("# rows ${snapshot.size}, events ${evs.size}; events (route changes and why) are listed after the rows\n")
                w.write("t_s,progress_m,speed_mps,window_m,chord_deg,display_deg,camera_deg,frame_dt_s\n")
                for (r in snapshot) {
                    w.write("${r[0]},${r[1]},${r[2]},${r[3]},${r[4]},${r[5]},${r[6]},${r[7]}\n")
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
}
