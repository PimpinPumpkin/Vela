package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

object NavLineWidth {
    val custom = mutableStateOf(false)
    val widthMultiplier = mutableStateOf(1f)

    fun init(context: Context) {
        val p = prefs(context)
        custom.value = p.getBoolean(KEY_CUSTOM, false)
        widthMultiplier.value = p.getFloat(KEY_MULTIPLIER, 1f)
    }

    fun setCustom(context: Context, value: Boolean) {
        custom.value = value
        prefs(context).edit().putBoolean(KEY_CUSTOM, value).apply()
    }

    fun setMultiplier(context: Context, value: Float) {
        widthMultiplier.value = value
        prefs(context).edit().putFloat(KEY_MULTIPLIER, value).apply()
    }

    fun currentMultiplier(): Float = if (custom.value) widthMultiplier.value else 1f

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_CUSTOM = "nav_line_custom"
    private const val KEY_MULTIPLIER = "nav_line_multiplier"
}
