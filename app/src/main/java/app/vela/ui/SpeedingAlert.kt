package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether Vela says so out loud when you are over the posted limit while navigating (issue
 * #404). Off by default: being told off by your phone is an opt-in. Timing lives in :core
 * [app.vela.core.nav.SpeedingAlerts]; the posted limit is whatever the speed widget shows
 * (the offline graph's maxspeed, else the online overlay), so it only speaks where a limit is
 * known. Respects the global spoken-directions mute like every other prompt.
 */
object SpeedingAlert {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY = "speeding_alert"
}
