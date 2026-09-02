package app.vela.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import app.vela.core.util.SunTimes

/** How Vela picks light vs dark - independent of the OS theme, so you can run the
 * app dark without flipping the whole phone (and vice-versa).
 *
 * [AUTO] is day/night by the actual sun at your location (issue #262), which is NOT the same as
 * [SYSTEM]: the OS theme is whatever the phone is set to, and only some ROMs schedule it at all.
 * A map is the case where it matters most - a white map at night is genuinely blinding. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED, AUTO }

/**
 * App-wide appearance preference. A process-wide reactive holder (like [app.vela.ui.Units]):
 * reading [mode] in a composable makes it recompose when the user flips the switch,
 * and the value is persisted so it survives restarts. Resolved to an actual
 * light/dark boolean by [isAppInDarkTheme].
 */
object AppTheme {
    val mode = mutableStateOf(ThemeMode.SYSTEM)

    /**
     * Day/night while NAVIGATING, whatever [mode] says the rest of the time (issue #262).
     *
     * Orthogonal to the mode on purpose, because that is the ask: keep the app dark by choice, but
     * do not hand me a dark map in daylight when I am actually driving by it. With [ThemeMode.AUTO]
     * selected this changes nothing - it is already day/night.
     */
    val navDayNight = mutableStateOf(false)

    /** Whether it is dark outside right now, recomputed by [refreshNight]. Read, never written, by
     *  the theme - so the sun math runs once a minute in one place instead of per composable. */
    val night = mutableStateOf(false)

    /** Mirrored from the nav state by MapViewModel, so [navDayNight] can act on it. */
    val navigating = mutableStateOf(false)

    private var lat: Double? = null
    private var lng: Double? = null

    fun init(context: Context) {
        mode.value = runCatching { ThemeMode.valueOf(prefs(context).getString(KEY, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        navDayNight.value = prefs(context).getBoolean(KEY_NAV_DAY_NIGHT, false)
        val p = prefs(context)
        if (p.contains(KEY_LAT)) {
            lat = p.getFloat(KEY_LAT, 0f).toDouble()
            lng = p.getFloat(KEY_LNG, 0f).toDouble()
        }
        refreshNight()
    }

    fun set(context: Context, value: ThemeMode) {
        mode.value = value
        prefs(context).edit().putString(KEY, value.name).apply()
        refreshNight()
    }

    fun setNavDayNight(context: Context, value: Boolean) {
        navDayNight.value = value
        prefs(context).edit().putBoolean(KEY_NAV_DAY_NIGHT, value).apply()
    }

    /**
     * Feed the sun calculation a position. **Stored ROUNDED to about a kilometre** - sunrise moves
     * by ~4 seconds per kilometre of longitude, so the extra precision buys nothing and there is no
     * reason for a theme setting to keep a precise record of where its owner was.
     */
    fun rememberLocation(context: Context, latitude: Double, longitude: Double) {
        val rLat = Math.round(latitude * 100.0) / 100.0
        val rLng = Math.round(longitude * 100.0) / 100.0
        if (rLat == lat && rLng == lng) return
        lat = rLat
        lng = rLng
        prefs(context).edit().putFloat(KEY_LAT, rLat.toFloat()).putFloat(KEY_LNG, rLng.toFloat()).apply()
        refreshNight()
    }

    /** Recompute [night]. Cheap (closed-form, no allocation to speak of) - called on a one-minute
     *  tick from VelaRoot and whenever the inputs change. */
    fun refreshNight() {
        val la = lat
        val ln = lng
        night.value = if (la != null && ln != null) {
            SunTimes.isNight(la, ln, System.currentTimeMillis())
        } else {
            // No fix yet (cold launch, or location denied outright): fall back to the clock.
            SunTimes.isNightByClock(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "theme_mode"
    private const val KEY_NAV_DAY_NIGHT = "theme_nav_day_night"
    private const val KEY_LAT = "theme_sun_lat"
    private const val KEY_LNG = "theme_sun_lng"
}

/** The single source of truth for "is the app dark right now" - honours the user's
 * [AppTheme] choice, falling back to the OS theme only in [ThemeMode.SYSTEM].
 * Every place that used to call `isSystemInDarkTheme()` should call this instead. */
@Composable
fun isAppInDarkTheme(): Boolean {
    // Navigating with the nav day/night toggle on overrides the chosen mode - that IS the setting
    // (issue #262: "my default is dark, but driving in daylight I want the light map").
    if (AppTheme.navigating.value && AppTheme.navDayNight.value) return AppTheme.night.value
    return when (AppTheme.mode.value) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.AUTO -> AppTheme.night.value
    }
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
