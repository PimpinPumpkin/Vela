package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether ALPR ("Flock") surveillance cameras are drawn on the map. Process-wide reactive holder like
 * [Traffic] / [TransitLayer] / [Topography], flipped from Settings > Map and persisted. **OFF by default**
 * (it's a niche privacy-awareness layer, and querying Overpass has a cost) - the people who want to know
 * where the plate readers are can turn it on. Data is the community DeFlock mapping in OpenStreetMap
 * (`surveillance:type=ALPR`), fetched keyless per viewport, no account, no Flock/Google involved.
 */
object Flock {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "flock_cameras_on"
}
