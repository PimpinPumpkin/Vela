package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether ending navigation (the red X, or Back during a drive) asks first. OFF by default: it is
 *  an extra tap every time a drive ends, for people who end drives by accident (issue #624). */
object NavEndConfirm {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "confirm_end_nav"
}
