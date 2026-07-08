package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether rail lines (train + subway/light rail/tram) are highlighted on the map, Google-style.
 * Process-wide reactive holder like [Traffic] / [Units], flipped from Settings and persisted. Off by
 * default. The data is already in the keyless basemap tiles (OpenMapTiles `transportation` layer), so
 * this only adds a coloured line layer over it, no network.
 */
object TransitLayer {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "transit_layer_on"
}
