package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Process-wide interface-size factor (same reactive-holder shape as AppTheme/Units). Applied in
 * VelaRoot as a LocalDensity override, so EVERY Compose surface scales (text, buttons, sheets)
 * while the map itself - an AndroidView rendering in real pixels - keeps its size. Built for
 * car/tablet screens where the map is fine but the touch targets run small (user 2026-07-11).
 */
object UiScale {
    val factor = mutableStateOf(1f)

    fun init(context: Context) {
        factor.value = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getFloat("ui_scale", 1f)
    }

    fun set(context: Context, f: Float) {
        factor.value = f
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .edit().putFloat("ui_scale", f).apply()
    }
}
