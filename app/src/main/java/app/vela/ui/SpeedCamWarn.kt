package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether an approaching speed camera is announced OUT LOUD during navigation (issue #229).
 *
 * Separate from [SpeedCams], which only draws them on the map, for two reasons. Seeing a marker
 * and being spoken to are different enough that wanting one is not wanting the other. And warning
 * about speed cameras while driving is treated differently country to country - some ban devices
 * that do it - so the spoken half is its own deliberate opt-in rather than something that arrives
 * with a map layer. Nested under the camera toggle in Settings, and it respects the global spoken
 * -directions mute like every other prompt.
 */
object SpeedCamWarn {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "speed_cam_warn"
}
