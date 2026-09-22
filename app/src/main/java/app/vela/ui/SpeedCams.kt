package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether fixed speed/radar cameras are drawn on the map (issue #229). Same reactive-holder shape
 * as [Flock]; OFF by default - the audience that wants radar warnings (mostly Europe) opts in. The
 * cameras come from the baked road-features region ([app.vela.data.RoadFeatures], checked through
 * MapViewModel.roadFeaturesCover); a live per-viewport Overpass fetch answers only where the manifest
 * has no region.
 */
object SpeedCams {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "speed_cameras_on"
}
