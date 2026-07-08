package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.LatLng

/**
 * Simulated ("larp") location — a demo/screenshot tool, sibling of demo-drive. When set, Vela
 * pretends to be at this point: the live GPS collector is suspended and every "your location"
 * (the dot, the search distance bias, the directions origin, recenter) reads this instead, so the
 * app can be shown from anywhere without leaking where you actually are. `null` = off (real GPS).
 *
 * Process-wide reactive holder like [TransitLayer] / [Units], persisted in `vela_settings`. The
 * value is the wall-clock point; [MapViewModel] owns applying it to the location stream.
 */
object SimLocation {
    /** The pretend position, or null when off (use real GPS). */
    val point = mutableStateOf<LatLng?>(null)

    /** True while a simulated location is active. */
    val on: Boolean get() = point.value != null

    fun init(context: Context) {
        point.value = parse(prefs(context).getString(KEY, null))
    }

    fun set(context: Context, p: LatLng?) {
        point.value = p
        prefs(context).edit().apply {
            if (p == null) remove(KEY) else putString(KEY, "${p.lat},${p.lng}")
        }.apply()
    }

    private fun parse(s: String?): LatLng? {
        val parts = s?.split(",") ?: return null
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return LatLng(lat, lng)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "sim_location"
}
