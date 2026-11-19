package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the road-ahead bar shows while navigating, on the phone and on the car screen.
 * [on] governs the feature (pref `route_bar`, off by default); [carOn] is the nested toggle
 * for whether it also shows on Android Auto (pref `car_route_bar_on`, on by default when
 * [on] is enabled).
 */
object RouteBarPref {
    val on = mutableStateOf(false)
    val carOn = mutableStateOf(true)

    fun init(context: Context) {
        val p = prefs(context)
        on.value = p.getBoolean(KEY, false)
        carOn.value = p.getBoolean(KEY_CAR, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun setCar(context: Context, value: Boolean) {
        carOn.value = value
        prefs(context).edit().putBoolean(KEY_CAR, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "route_bar"
    private const val KEY_CAR = "car_route_bar_on"
}
