package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the place sheet's Reviews tab shows **Google's own live reviews panel** in a visible
 * WebView (fast, auto-pages as you scroll, Google's server-side review search) instead of the
 * background-scraped native list. A process-wide reactive holder (like [Traffic] / [Units]),
 * persisted across launches. ON by default - experimental, one toggle away from the native
 * scrape in Settings if Google's markup shifts under the CSS surgery.
 */
object LiveReviews {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "live_reviews_panel"
}
