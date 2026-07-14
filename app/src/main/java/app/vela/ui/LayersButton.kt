package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether the map's LAYERS button (satellite / traffic / transit / terrain panel) is shown.
 *  ON by default; hideable from Settings for people who want the barest possible map. */
object LayersButton {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "layers_button_on"
}
