package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether your speed and the posted limit show while driving, on the phone and on the car
 *  screen. ON by default (issue #625: some cars show both in the dash already). */
object SpeedDisplay {
    val on = mutableStateOf(true)
    val upcomingOn = mutableStateOf(false)
    val upcomingMeters = mutableStateOf(300)

    fun init(context: Context) {
        val p = prefs(context)
        on.value = p.getBoolean(KEY, true)
        upcomingOn.value = p.getBoolean(KEY_UPCOMING, false)
        upcomingMeters.value = p.getInt(KEY_UPCOMING_M, 300).coerceIn(100, 1000)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun setUpcoming(context: Context, value: Boolean) {
        upcomingOn.value = value
        prefs(context).edit().putBoolean(KEY_UPCOMING, value).apply()
    }

    fun setUpcomingMeters(context: Context, meters: Int) {
        val clamped = meters.coerceIn(100, 1000)
        upcomingMeters.value = clamped
        prefs(context).edit().putInt(KEY_UPCOMING_M, clamped).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "speed_display"
    private const val KEY_UPCOMING = "speed_upcoming_on"
    private const val KEY_UPCOMING_M = "speed_upcoming_m"
}
