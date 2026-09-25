package app.vela.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.mutableStateOf

/**
 * When a downloaded region may update itself (user 2026-09-18).
 *
 * A rebaked region used to mean a fresh few-hundred-MB download, so updating was always a
 * deliberate act. Deltas change the economics (measured on Kentucky, a week of edits moved 1.3% of
 * the tiles and 3.2% of the bytes), but bytes are still bytes on a phone plan, so the choice stays
 * the user's, and the default is OFF
 * (see [mode]). On Wi-Fi or mobile, MapViewModel.scheduleAutoRegionPatches applies published
 * patches on its own once a day; the setting also decides whether the Update button may patch.
 *
 * [MOBILE] means "use cellular too" rather than "use everything": it still only ever applies a
 * DELTA unprompted. A full re-download is never automatic on any setting, because that is the
 * surprise this exists to prevent.
 */
object RegionUpdates {
    enum class Mode { OFF, WIFI, MOBILE }

    // WIFI by default since 2026-09-25: the whole path was watched working on a Pixel 9 on
    // 2026-09-19 (a published patch downloaded, applied and fingerprint-checked). Someone who
    // picked "Never" keeps it; only an untouched setting follows the default.
    val mode = mutableStateOf(Mode.WIFI)

    fun init(context: Context) {
        mode.value = when (prefs(context).getString(KEY, "wifi")) {
            "off" -> Mode.OFF
            "mobile" -> Mode.MOBILE
            else -> Mode.WIFI
        }
    }

    fun set(context: Context, value: Mode) {
        mode.value = value
        prefs(context).edit().putString(KEY, value.name.lowercase()).apply()
    }

    /** True when an automatic delta may run right now. Metered is the system's own answer, which
     *  covers a metered Wi-Fi network as well as cellular: what matters is whether the bytes cost
     *  the user, not which radio carried them. */
    fun allowedNow(context: Context): Boolean = when (mode.value) {
        Mode.OFF -> false
        Mode.MOBILE -> true
        Mode.WIFI -> runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm != null && !cm.isActiveNetworkMetered
        }.getOrDefault(false)
    }

    /** What the last attempt did, for the Settings row and the diagnostics export. Kept in memory
     *  on purpose: it describes this session, and a stale line from last week explains nothing. */
    val lastResult = mutableStateOf<String?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "region_update_mode"
}
