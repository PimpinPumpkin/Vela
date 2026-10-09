package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether bridge openings on the route are announced while navigating in the Netherlands, from
 * NDW's open data ([app.vela.core.data.NdwBridgeOpenings]). On by default: it asks NDW only when
 * a route enters the Netherlands, and a movable bridge that is open can hold a driver up for ten
 * minutes or more. The card and the spoken line follow the spoken-directions setting like every
 * other prompt.
 */
object BridgeAlert {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "bridge_openings"
}
