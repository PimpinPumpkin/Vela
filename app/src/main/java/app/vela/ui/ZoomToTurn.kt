package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether the drive camera zooms out until the next turn sits just under the turn card, and back
 *  in as the car nears it (issue #781, SPEC 4.7 "Framing"). Off by default: it trades the street
 *  names and lights of the close zoom for a view of the stretch ahead. Read per frame by the nav
 *  ticker. */
object ZoomToTurn {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "zoom_to_turn"
}
