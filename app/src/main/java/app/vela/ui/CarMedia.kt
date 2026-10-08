package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether Vela presents navigation on the car's media screen ([app.vela.car.media.VelaMediaService]).
 * Process-wide reactive holder like [TransitLayer], flipped from Settings and persisted.
 *
 * Off by default: it is a way onto a car screen for phones where the Android Auto navigation
 * category will not list a sideloaded app, and it shows a still map refreshed as the drive advances
 * rather than a live surface, so it is a choice the driver makes rather than the default. The
 * manifest service exists either way; this flag decides whether it offers any content.
 */
object CarMedia {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        load(context)
        // The manifest declares the media service disabled, so a phone where nobody turned this on
        // shows no media app in the car and the nav app is listed exactly as before. Reconcile the
        // component to the saved choice at launch.
        setComponentEnabled(context, on.value)
    }

    /** Safe to call from the media service's own process entry (it has no Application init). */
    fun load(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
        setComponentEnabled(context, value)
    }

    private fun setComponentEnabled(context: Context, enabled: Boolean) {
        runCatching {
            val name = android.content.ComponentName(context.packageName, "app.vela.car.media.VelaMediaService")
            val state = if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(name, state, android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "car_media_on"
}
