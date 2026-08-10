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

    fun clear() {
        synchronized(rows) { rows.clear(); t0 = 0L }
    }

    fun isEmpty(): Boolean = synchronized(rows) { rows.isEmpty() }

    /** Write the ring to a CSV in the cache dir and hand back a share intent, or null if empty. */
    fun shareIntent(context: Context): Intent? {
        val snapshot = synchronized(rows) { if (rows.isEmpty()) return null else rows.toList() }
        return runCatching {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val file = File(dir, "vela-nav-trace.csv")
            file.bufferedWriter().use { w ->
                w.write("# Vela nav smoothness trace. No position data: bearings, along-route\n")
                w.write("# distance, speed and frame timings only, safe to attach to an issue.\n")
                w.write("t_s,progress_m,speed_mps,window_m,chord_deg,display_deg,camera_deg,frame_dt_s\n")
                for (r in snapshot) {
                    w.write("${r[0]},${r[1]},${r[2]},${r[3]},${r[4]},${r[5]},${r[6]},${r[7]}\n")
                }
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
