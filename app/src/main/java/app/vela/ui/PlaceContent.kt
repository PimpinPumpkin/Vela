package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether place pages show reviews at all. OFF means the sheet renders no review
 * section and the app never fetches reviews for a selected place (no hidden
 * WebView scrape, no review traffic). Same process-wide reactive holder shape as
 * [LiveReviews] / [Traffic], persisted in vela_settings.
 */
object ShowReviews {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "show_reviews"
}

/**
 * Whether place pages load photos. OFF means no hero strip, no gallery, and no
 * photo fetch at all (the WebView gallery scrape is the heaviest per-place
 * request, so this is also the data-saver switch).
 */
object LoadPhotos {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "load_photos"
}

/**
 * Whether to hide adult / nightlife categories (bars, clubs, casinos, liquor stores, adult, smoking,
 * gambling, …) from search results and the ambient map. OFF by default (everything shown); ON drops
 * those places at the data-source seam via [app.vela.core.data.CategoryFilter]. Matches on Google's
 * free-text CATEGORY only, never the name, so a place categorised "Restaurant" is always kept. Same
 * process-wide reactive holder shape as [ShowReviews] / [LoadPhotos], persisted in vela_settings.
 */
object HideAdult {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
        app.vela.core.data.CategoryFilter.enabled = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        app.vela.core.data.CategoryFilter.enabled = value // gate the :core data-source seam
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "hide_adult"
}
