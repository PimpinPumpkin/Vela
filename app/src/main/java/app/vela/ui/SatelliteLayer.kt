package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Satellite imagery over the basemap (Esri World Imagery, the open keyless tile service; requires
 * attribution, drawn next to the scale bar while on). Process-wide reactive holder like
 * [TransitLayer], flipped from the map's satellite button and persisted. OFF by default - the
 * vector map is the product; imagery is a look-around mode. The raster slots in under the symbol
 * layers, so labels, POIs, the route line and Vela's own layers all stay readable on top.
 */
object SatelliteLayer {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "satellite_on"
}
