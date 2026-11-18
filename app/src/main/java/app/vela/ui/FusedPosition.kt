package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Settings > Navigation "Fused position": guidance steers by the position Vela fuses from the GPS
 *  fix and its Doppler speed and course (`core/location/PositionFilter`, SPEC 4.6 "Fixes"). Off,
 *  it steers by the fix as it came and the dot is smoothed as when browsing. On by default. The
 *  `fusedOff` dial turns it off for every install without a release. */
object FusedPosition {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
        app.vela.core.location.GuidanceFix.rawWhen = { !on.value || AppTune.on("fusedOff", false) }
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "fused_position"
}
