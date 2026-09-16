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
    /** The user's own toggle, or null while they never touched it (the signed bundle's
     *  `experimentGoogleChooser` applies then, so the experiment can be switched on remotely). */
    private var explicitChooser: Boolean? = null
    private var remoteChooser = false

    fun init(context: Context) {
        val p = prefs(context)
        explicitChooser = if (p.contains(KEY_GOOGLE_CHOOSER)) p.getBoolean(KEY_GOOGLE_CHOOSER, false) else null
        googleChooser.value = explicitChooser ?: remoteChooser
    }

    fun setGoogleChooser(context: Context, on: Boolean) {
        explicitChooser = on
        googleChooser.value = on
        prefs(context).edit().putBoolean(KEY_GOOGLE_CHOOSER, on).apply()
    }

    /** MapViewModel pushes the bundle's value at init and after each refresh. */
    fun setRemoteDefault(on: Boolean) {
        remoteChooser = on
        if (explicitChooser == null) googleChooser.value = on
    }

    private fun prefs(context: Context) = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_GOOGLE_CHOOSER = "exp_google_chooser"
}
