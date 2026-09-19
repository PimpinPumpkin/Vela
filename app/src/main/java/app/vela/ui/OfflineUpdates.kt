package app.vela.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.mutableStateOf

/**
 * When a downloaded region may update itself (user 2026-09-18).
 *
 * A rebaked region used to mean a fresh few-hundred-MB download, so updating was always a
 * deliberate act. Deltas change the economics (a week of edits is about 3% of a state's tiles), but
 * bytes are still bytes on a phone plan, so the choice stays the user's and the default is the
 * cautious one: patch on Wi-Fi, ask nowhere else.
 *
 * [MOBILE] means "use cellular too" rather than "use everything": it still only ever applies a
 * DELTA unprompted. A full re-download is never automatic on any setting, because that is the
 * surprise this exists to prevent.
 */
object RegionUpdates {
    enum class Mode { OFF, WIFI, MOBILE }

    // OFF until the whole path has been proven on a device: the bake only started publishing
    // patches on 2026-09-18 and nothing has yet downloaded and applied one in the wild. A feature
    // that rewrites an installed archive does not get to default itself on before somebody has
    // watched it work. Flip to WIFI once it has.
    val mode = mutableStateOf(Mode.OFF)

    fun init(context: Context) {
        mode.value = when (prefs(context).getString(KEY, "off")) {
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
