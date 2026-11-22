package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Settings > Map "Turn the map as you drive" (pref `free_heading_up`, on; issue #905). Off, the
 *  map with no route stays north-up at any speed, on the phone and the car, and two-finger
 *  rotation still works. A drive still turns the map. "Keep north up" ([NorthLock]) is stronger
 *  and wins. */
object TurnWithTravel {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "free_heading_up"
}
