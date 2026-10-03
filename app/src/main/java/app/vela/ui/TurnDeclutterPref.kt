package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether the nav map drops everything but street names while the camera swings through a turn
 *  or follows a pan or pinch (default on, user 2026-10-02). Read per frame by the nav ticker. */
object TurnDeclutterPref {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "turn_declutter"
}
