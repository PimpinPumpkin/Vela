package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Process-wide map colour-set pick (same reactive-holder shape as AppTheme/UiScale).
 * Two sets ship compiled: "modern" (the palette pixel-sampled from the Google app,
 * the default) and "classic" (the pre-sample look the project archived in
 * docs/MAP-STYLE.md - white roads, soft-yellow motorways, true greens). The user's
 * pick persists in `map_palette`; with NO pick, the signed calibration bundle's
 * `defaultMapPalette` decides (pushed by MapViewModel at init), so the fleet default
 * can change remotely without an app release - a user's own pick always wins.
 */
object MapColors {
    const val MODERN = "modern"
    const val CLASSIC = "classic"

    /** The user's explicit pick, or "" when they never chose. */
    val picked = mutableStateOf("")

    /** The calibration bundle's fleet default (pushed by the VM once the store loads). */
    val remoteDefault = mutableStateOf(MODERN)

    fun init(context: Context) {
        picked.value = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getString("map_palette", "") ?: ""
    }

    fun set(context: Context, palette: String) {
        picked.value = palette
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .edit().putString("map_palette", palette).apply()
    }

    /** The palette to draw with right now. */
    fun current(): String = picked.value.ifBlank { remoteDefault.value }

    fun classic(): Boolean = current() == CLASSIC
}
