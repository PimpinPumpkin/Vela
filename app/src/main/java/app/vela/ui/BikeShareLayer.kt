package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether bike-share stations are drawn on the map, with their free bikes (SPEC 8, Bike-share
 * stations). Same reactive-holder shape as [SpeedCams]. OFF by default: it is a data flow of its
 * own to Transitous, so a rider opts in. The stations are fetched by
 * [app.vela.ui.map.BikeShareStations].
 */
object BikeShareLayer {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "bike_share_layer_on"
}
