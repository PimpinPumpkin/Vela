package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the basemap's `building-3d` fill-extrusion layer renders (z16+). Extrusion is
 * the most fragment-shader-expensive thing the map draws, so this is the one-tap lever
 * for "panning stutters when zoomed in" on weaker GPUs. ON by default; the flat
 * footprints (a separate fill layer) always stay.
 */
object Buildings3d {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "buildings_3d"
}
