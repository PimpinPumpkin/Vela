package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Where the name of the road you are driving is shown during navigation (issue #288 asked for
 *  it under the arrow; that pill cannot be centred for long names because it is pinned to the
 *  arrow, so the default is Google's fixed spot above the bottom bar). Values: "off", "bar", "puck". */
object RoadLabel {
    const val OFF = "off"
    const val BAR = "bar"
    const val PUCK = "puck"
    val mode = mutableStateOf(BAR)

    fun init(context: Context) {
        mode.value = prefs(context).getString(KEY, BAR) ?: BAR
    }

    fun set(context: Context, value: String) {
        mode.value = value
        prefs(context).edit().putString(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "road_label"
}

/** "Prefer buttons over swipes": keeps a discrete button wherever a gesture has one (today: the
 *  step-list button on the nav bar beside the swipe-up handle). Off by default; keypad-first
 *  devices behave as if it were on. */
object PreferButtons {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "prefer_buttons"
}
