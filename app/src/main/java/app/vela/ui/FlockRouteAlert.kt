package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the route picker warns when a route passes ALPR ("Flock") cameras. Separate from [Flock]
 * (the map layer) on purpose - you might want the cameras drawn but not the routing alert, or the
 * alert without the clutter of the layer. **OFF by default**; flipped from Settings > Map and
 * persisted. When on, [app.vela.ui.map.MapViewModel] counts the cameras near each computed route
 * (keyless, via `OverpassAlprCameras.fetchAlong`) and the directions panel badges the count.
 */
object FlockRouteAlert {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "flock_route_alert_on"
}
