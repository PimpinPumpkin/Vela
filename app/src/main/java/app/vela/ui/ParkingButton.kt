package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether the map's PARKING button shows when no spot is saved. ON by default; off for people who
 *  never use it (issue #626). A saved spot always keeps the button, the way back to the car. */
object ParkingButton {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "parking_button_on"
}
