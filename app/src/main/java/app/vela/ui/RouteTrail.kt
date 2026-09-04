package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether the part of the route you have already driven stays drawn behind the arrow during
 *  navigation (grey, Google's old look) or disappears (Google's current look, and the default
 *  since 2026-09-03: the grey strip trailing down the screen under the arrow was the first thing
 *  asked about once the puck stopped jittering). Read per frame by the nav ticker. */
object RouteTrail {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "route_trail"
}
