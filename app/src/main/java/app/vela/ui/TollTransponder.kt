package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * The driver has a toll transponder. Off by default. Without one, a toll shown on a route also
 * shows the fee a vehicle with no transponder is billed for the crossing or the trip
 * ([app.vela.core.data.Tolls]).
 */
object TollTransponder {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "toll_transponder"
}
