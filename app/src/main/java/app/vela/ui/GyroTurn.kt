package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Diagnostics, "Turn the arrow with the gyroscope": the drive arrow's heading follows
 * the car's measured rate of turn ([app.vela.core.location.GyroHeading]) instead of the route's
 * bearing a little ahead, so it turns when the car does. Off by default while it is compared on
 * real drives; it can be flipped mid-drive.
 */
object GyroTurn {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "gyro_turn"
}
