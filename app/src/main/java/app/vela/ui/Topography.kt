package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the map draws terrain relief (the keyless terrarium-DEM hillshade). Process-wide reactive
 * holder like [Traffic] / [TransitLayer], flipped from Settings > Map and persisted. **OFF by
 * default (2026-07-12, user request):** Google doesn't shade topography unless you turn it on, and
 * the relief muddied the clean flat basemap; the people who want terrain context can flip it on. The
 * hillshade is a live layer (DEM raster + `HillshadeLayer`), NOT baked into the tiles, so this only
 * toggles a layer's visibility, no rebake and no network beyond the DEM tiles when it's on.
 */
object Topography {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "topography_on"
}
