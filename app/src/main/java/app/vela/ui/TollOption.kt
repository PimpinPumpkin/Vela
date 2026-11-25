package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the route chooser also asks Google for the same trip with tolls avoided, to show a toll
 * road as a choice ([app.vela.core.nav.TollCompare]). On by default. It costs one more directions
 * request when a drive is planned, the same trip data as the first.
 */
object TollOption {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "toll_option"
}
