package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

// Settings for the BYD vehicle integrations (SPEC 10.9). Process-wide holders, started in
// VelaApp, each a mutableStateOf mirror over the shared vela_settings file. All default off: the
// features touch a car's displays and HAL and must be chosen, never on for a phone or an unrelated
// head unit.

/** The cluster map (a Presentation on a BYD cluster display). [display] is the chosen display's
 *  name, null for the first one Vela offers. */
object ClusterMapPref {
    val on = mutableStateOf(false)
    val display = mutableStateOf<String?>(null)

    fun init(context: Context) {
        val p = prefs(context)
        on.value = p.getBoolean(KEY_ON, false)
        display.value = p.getString(KEY_DISPLAY, null)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY_ON, value).apply()
    }

    fun setDisplay(context: Context, name: String?) {
        display.value = name
        prefs(context).edit().putString(KEY_DISPLAY, name).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_ON = "cluster_map_on"
    private const val KEY_DISPLAY = "cluster_map_display"
}

/** The windshield HUD (road-info frames to the glass over the SOME/IP gateway). */
object SomeIpHudPref {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "someip_hud_on"
}

/** The FCE cluster HUD (vendor vehicle properties on head units that have the FCE HAL). */
object FceClusterHudPref {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "fce_cluster_hud_on"
}
