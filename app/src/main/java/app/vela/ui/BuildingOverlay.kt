package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the open Microsoft building-footprint overlay is drawn to FILL GAPS where OSM lacks
 * buildings (suburbs the OSM import never reached). ON by default. It is already auto-suppressed
 * where OSM is dense (that layer would be occluded overdraw there), but this is the hard off switch
 * for anyone who wants zero extra building rendering. Turning it off clears the overlay entirely;
 * OSM buildings are unaffected.
 */
object BuildingOverlay {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "building_overlay_on"
}

/**
 * Developer-only: when on, the map shows a small badge reporting the building-overlay state
 * (OFF / none in region / hidden because OSM is dense / DRAWING) so the auto-suppression can be
 * verified at a glance. Off by default; a testing aid, not shipped-on.
 */
object BuildingDebug {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "bldg_overlay_debug"
}
