package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether ALPR ("Flock") surveillance cameras are drawn on the map. Process-wide reactive holder like
 * [Traffic] / [TransitLayer] / [Topography], flipped from Settings > Map and persisted. **ON by default**
 * (2026-07-13, user call): it's a headline privacy feature for Vela's audience, and now that the data is a
 * bundled on-device dataset ([app.vela.data.FlockCameras]) rather than a per-viewport Overpass query, it's
 * basically free to draw and only shows at neighbourhood zoom, so leaving it on costs nothing. Anyone who
 * doesn't want it flips it off in Settings (an explicit off is persisted and honoured). Data is the
 * community DeFlock mapping in OpenStreetMap (`surveillance:type=ALPR`), no account, no Flock/Google.
 * NB the ROUTE-avoid re-rank ([FlockRouteAlert]) stays OFF by default - that one changes route choice.
 */
object Flock {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "flock_cameras_on"
}
