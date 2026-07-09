package app.vela.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

/** How Vela picks light vs dark — independent of the OS theme, so you can run the
 *  app dark without flipping the whole phone (and vice-versa). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App-wide appearance preference. A process-wide reactive holder (like [app.vela.ui.Units]):
 * reading [mode] in a composable makes it recompose when the user flips the switch,
 * and the value is persisted so it survives restarts. Resolved to an actual
 * light/dark boolean by [isAppInDarkTheme].
 */
object AppTheme {
    val mode = mutableStateOf(ThemeMode.SYSTEM)

    fun init(context: Context) {
        mode.value = runCatching { ThemeMode.valueOf(prefs(context).getString(KEY, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, value: ThemeMode) {
        mode.value = value
        prefs(context).edit().putString(KEY, value.name).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "theme_mode"
}

/** The single source of truth for "is the app dark right now" — honours the user's
 *  [AppTheme] choice, falling back to the OS theme only in [ThemeMode.SYSTEM].
 *  Every place that used to call `isSystemInDarkTheme()` should call this instead. */
@Composable
fun isAppInDarkTheme(): Boolean = when (AppTheme.mode.value) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Material You dynamic colour preference (issue #15). Same reactive-holder shape as
 * [AppTheme]: flip it in Settings and every MaterialTheme surface recomposes with the
 * wallpaper palette. Off by default - Vela teal is the out-of-the-box look, dynamic
 * colour is the opt-in customization. Android 12+ only; the toggle is hidden below that.
 */
object DynamicColor {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** For non-compose readers (the nav notification): the persisted value, straight
     *  from prefs, so a Service path needs no compose state. */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "dynamic_color"
}
