package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Which route chooser the Directions button opens.
 *
 * ON (the default since 2026-09-18) is the Google-style picker: the mode as a title with icon
 * actions, underlined mode tabs carrying each mode's time, ONE selected-route summary with the
 * alternates behind a "2 other routes" line and time bubbles on the map, and a Start / Add stops /
 * Share bar. OFF is Vela's classic panel, which lists every route at once - kept because it shows
 * more at a glance, and because a picker is the kind of thing people have muscle memory for.
 *
 * It shipped as an opt-in experiment (`exp_google_chooser`, Settings > Diagnostics) and graduated
 * once it had been driven; [init] carries an experimenter's explicit choice over to the new key so
 * nobody's picker changes under them, and [setRemoteDefault] lets the signed calibration bundle
 * put the fleet back on the classic panel without an app release if the new default goes wrong.
 */
object RoutePicker {
    val googleStyle = mutableStateOf(true)

    /** The user's own toggle, or null while they never touched it (the remote default applies then). */
    private var explicit: Boolean? = null
    private var remoteClassic = false

    fun init(context: Context) {
        val p = prefs(context)
        explicit = when {
            p.contains(KEY) -> p.getBoolean(KEY, true)
            // Graduated experiment: an explicit opt-in (or opt-out) from when it lived under
            // Diagnostics still speaks for the user.
            p.contains(LEGACY_KEY) -> p.getBoolean(LEGACY_KEY, false)
            else -> null
        }
        googleStyle.value = explicit ?: !remoteClassic
    }

    fun set(context: Context, on: Boolean) {
        explicit = on
        googleStyle.value = on
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    /** MapViewModel pushes the bundle's value at init and after each refresh. */
    fun setRemoteDefault(classic: Boolean) {
        remoteClassic = classic
        if (explicit == null) googleStyle.value = !classic
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "route_picker_google"
    private const val LEGACY_KEY = "exp_google_chooser"
}
