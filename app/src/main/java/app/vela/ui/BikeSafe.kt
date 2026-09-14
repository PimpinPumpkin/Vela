package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.data.RoutingPrefs

/**
 * Bike routes prefer bike lanes and quiet streets (issue #401). ON by default, the way Google
 * routes bikes; off means the plain fastest bike route. Mirrors into [RoutingPrefs.bikeSafe],
 * which the directions engine reads for planning and every reroute.
 */
object BikeSafe {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
        RoutingPrefs.bikeSafe = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        RoutingPrefs.bikeSafe = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY = "bike_safe"
}
