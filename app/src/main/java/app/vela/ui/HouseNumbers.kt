package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * How far out house numbers appear (issue #329, 2026-09-13). One zoom gate is shared by the
 * basemap `vela-housenumber` layer (OSM `addr:housenumber`) and the OpenAddresses overlay, so
 * both sets arrive together. The gate used to be a fixed 18.3 (issue #257 lowered it from 19,
 * where people zoomed in, saw street names and no numbers, and concluded Vela had none); a step
 * further out is the new default, and the person who wants a quieter or busier street view can
 * pick. The map style reloads on a change (the level rides `styleKey`).
 */
object HouseNumbers {
    const val NEAR = "near"     // the old fixed gate: numbers only at the closest zoom
    const val NORMAL = "normal" // default: half a level further out
    const val FAR = "far"       // a full level out; blocks start to carpet in numbers
    val level = mutableStateOf(NORMAL)

    fun minZoom(): Float = when (level.value) {
        NEAR -> 18.3f
        FAR -> 17.3f
        else -> 17.8f
    }

    fun init(context: Context) {
        level.value = prefs(context).getString(KEY, NORMAL) ?: NORMAL
    }

    fun set(context: Context, value: String) {
        level.value = value
        prefs(context).edit().putString(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "housenumber_zoom"
}
