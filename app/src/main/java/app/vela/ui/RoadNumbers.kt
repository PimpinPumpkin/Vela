package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Navigation > Road numbers first (issue #836). Off by default: a numbered road
 * through a town keeps its street name, as its signs do. On, a road with a route number goes
 * by the number: the pill under the arrow and the car's "Continue on" at once, the turn
 * directions from the next route on, for the roads whose dedication names ("Pearl Harbor
 * Memorial Expressway") never appear on a sign. Mirrored into the `:core` flag of the same
 * name, which `roadLabel` and the instruction builders read.
 */
object RoadNumbers {
    val on = mutableStateOf(false)

    private const val KEY = "road_numbers"

    private fun prefs(context: Context) =
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
        app.vela.core.nav.RoadNumbers.preferred = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        app.vela.core.nav.RoadNumbers.preferred = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }
}
