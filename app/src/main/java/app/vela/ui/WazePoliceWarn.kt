package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether an approaching reported police presence or speed trap is announced out loud
 * during navigation. Sibling of [SpeedCamWarn]. Nested under the Waze police toggle in Settings,
 * and respects the global spoken-directions mute.
 */
object WazePoliceWarn {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "waze_police_warn"
}
