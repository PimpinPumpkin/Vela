package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * "Use Vela without Google" (Settings > Privacy, pref `google_free`, off by default): the one
 * switch that stops every request to a Google host. Mirrored into the `:core` flag
 * [app.vela.core.data.NoGoogle], which gates the data source at the network edge; the app gates
 * its own Google surfaces (hidden WebViews, the traffic raster, the satellite fallback, the tap
 * lookup, Street View) on this holder. What it costs is in the settings hint and in docs/FAQ.md.
 */
object GoogleFree {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
        app.vela.core.data.NoGoogle.enabled = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        app.vela.core.data.NoGoogle.enabled = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "google_free"
}
