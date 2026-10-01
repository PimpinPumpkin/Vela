package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * What a location link from another app does (discussion #640): a delivery app hands over one
 * stop after another, and opening a pin, then Directions, then Start for each is three taps a
 * hundred times a day.
 *
 * - [PLACE] (default): a location link shows the place; a directions link opens the route chooser.
 * - [DIRECTIONS]: a location link opens the route chooser too.
 * - [START]: both kinds start the drive as soon as a route exists, through the same gates the
 *   Start button passes (precise location, notifications).
 */
object LinkAction {
    const val PLACE = "place"
    const val DIRECTIONS = "directions"
    const val START = "start"

    val mode = mutableStateOf(PLACE)

    fun init(context: Context) {
        mode.value = prefs(context).getString(KEY, PLACE) ?: PLACE
    }

    fun set(context: Context, value: String) {
        mode.value = value
        prefs(context).edit().putString(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "link_action"
}
