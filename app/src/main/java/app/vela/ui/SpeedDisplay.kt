package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether your speed and the posted limit show while driving, on the phone and on the car
 *  screen. ON by default (issue #625: some cars show both in the dash already). */
object SpeedDisplay {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "speed_display"
}
