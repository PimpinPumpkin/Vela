package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Opt-in UI experiments, off by default, under Settings > Diagnostics. Each is a whole alternate
 * surface kept side by side with the shipped one so the two can be compared on the same build
 * before one replaces the other.
 */
object Experiments {
    /** The route chooser laid out like Google Maps' (2026 layout): a mode title with icon actions,
     *  underlined mode tabs, ONE selected-route summary, alternates picked from time bubbles on the
     *  map, the turn list inline, and a Start / Add stops / Share bar. */
    val googleChooser = mutableStateOf(false)

    fun init(context: Context) {
        googleChooser.value = prefs(context).getBoolean(KEY_GOOGLE_CHOOSER, false)
    }

    fun setGoogleChooser(context: Context, on: Boolean) {
        googleChooser.value = on
        prefs(context).edit().putBoolean(KEY_GOOGLE_CHOOSER, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_GOOGLE_CHOOSER = "exp_google_chooser"
}
