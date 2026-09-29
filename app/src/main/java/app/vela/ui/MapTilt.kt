package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether a two-finger drag tilts the map into 3D. ON by default; off for people who never use
 *  the 3D view and want a pinch to only ever zoom (issue #627). The nav camera's own tilt is not
 *  affected. */
object MapTilt {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "two_finger_tilt"
}
