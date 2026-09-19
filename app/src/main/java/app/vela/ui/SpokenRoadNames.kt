package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether spoken guidance names the road you are turning onto (issue #596). On by default, which
 * is what Vela has always done; off, the voice says "Turn left" instead of "Turn left onto Maple
 * Street". Google and most navigators offer the same switch.
 *
 * Nothing on screen changes: the banner, the step list and the pill under the puck keep the name.
 * The value is mirrored into the `:core` flag of the same name, which is what `NavEngine` reads,
 * because `:core` never reaches up into an app holder.
 */
object SpokenRoadNames {
    val on = mutableStateOf(true)

    private const val KEY = "spoken_road_names"

    private fun prefs(context: Context) =
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
        app.vela.core.nav.SpokenRoadNames.enabled = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        app.vela.core.nav.SpokenRoadNames.enabled = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }
}
