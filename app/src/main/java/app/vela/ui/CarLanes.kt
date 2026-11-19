package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the car's turn card shows lane guidance (issue #866). On by default. Off, no step sent
 * to the car carries lanes, so the card does not grow as a turn comes near and Zoom to the next
 * turn does not pull back out. The phone's own lane strip is not affected. The phone's Settings >
 * Navigation and the car's settings screen share this switch.
 */
object CarLanes {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "car_lanes_on"
}
