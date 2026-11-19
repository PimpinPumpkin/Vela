package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * Whether a place sheet holds space for popular times while its details load (issue #904). Many
 * places have no chart, and some sessions never get one, so the placeholder sat there for 10 to
 * 20 seconds and went away empty. [MISSES_OFF] details answers in a row with no chart turn it off;
 * a chart turns it back on. Kept across launches (pref `popular_times_misses`).
 */
object PopularTimesHint {
    const val MISSES_OFF = 3
    private val misses = mutableIntStateOf(0)
    private var prefs: android.content.SharedPreferences? = null

    val show: Boolean get() = misses.intValue < MISSES_OFF

    fun init(context: Context) {
        prefs = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
        misses.intValue = prefs?.getInt(KEY, 0) ?: 0
    }

    /** A details answer came for a listing: [chart] says whether it had popular times. */
    fun answered(chart: Boolean) {
        val n = if (chart) 0 else (misses.intValue + 1).coerceAtMost(MISSES_OFF)
        if (n == misses.intValue) return
        misses.intValue = n
        prefs?.edit()?.putInt(KEY, n)?.apply()
    }

    private const val KEY = "popular_times_misses"
}
