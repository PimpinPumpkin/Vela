package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Settings > Map "Keep north up" (pref `keep_north_up`, off). On, nothing turns the map: the
 *  two-finger rotation gesture is off, a bearing that arrives some other way goes back to north
 *  when the camera rests, every drive runs north-up (the phone and the car), and the drive
 *  compass no longer switches to heading-up. */
object NorthLock {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "keep_north_up"
}
