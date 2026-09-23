package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Performance > "Load all photos and reviews" (pref `place_full_load`, 2026-09-23, off).
 * Off: a place tap takes the first few photos and the first page of reviews and stops; "More
 * photos" and the All reviews page fetch the rest. On: the old behavior, the whole gallery walk and
 * up to 50 reviews on every tap. Each tap used to cost several hundred requests to Google across
 * three hidden pages, and that volume is what puts a network into Google's limited view.
 */
object FullPlaceLoad {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "place_full_load"
}
