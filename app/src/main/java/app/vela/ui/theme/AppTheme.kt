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

/** The MAP's own light/dark, apart from the app's (user 2026-09-28: a light map under dark
 *  settings, or the other way round). FOLLOW = whatever the app resolves to. AUTO = by the sun
 *  ([AppTheme.night]) whatever the app is, so a dark app can sit over a day and night map (issue
 *  #874). Stored by name under the same key: a build from before AUTO fails `valueOf` on it and
 *  falls back to FOLLOW, without rewriting it, so the choice is back after an upgrade. */
enum class MapThemeMode { FOLLOW, LIGHT, DARK, AUTO }

/**
 * App-wide appearance preference. A process-wide reactive holder (like [app.vela.ui.Units]):
 * reading [mode] in a composable makes it recompose when the user flips the switch,
 * and the value is persisted so it survives restarts. Resolved to an actual
 * light/dark boolean by [isAppInDarkTheme].
 */
object AppTheme {
    val mode = mutableStateOf(ThemeMode.SYSTEM)

    /** [MapThemeMode] for the map surface; the sheets and settings keep following [mode]. */
    val mapMode = mutableStateOf(MapThemeMode.FOLLOW)

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
    val sunriseSunset = mutableStateOf<Pair<Long, Long>?>(null)

    /** Mirrored from the nav state by MapViewModel, so [navDayNight] can act on it. */
    val navigating = mutableStateOf(false)

    private var lat: Double? = null
    private var lng: Double? = null
    private var at: Long = 0L // when the point was recorded (epoch ms); 0 = a pre-timestamp store
    private var appContext: Context? = null

    /** A stored point older than this is not trusted for the sun: with permission granted a fresh
     *  fix replaces it within a minute of launch anyway, and without permission (revoked since)
     *  the old point can be a continent away (review 2026-09-12: a revoked grant plus a flight
     *  east gave a dark map at 3 pm for the whole trip). */
    private const val POINT_MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun init(context: Context) {
        appContext = context.applicationContext
        mode.value = runCatching { ThemeMode.valueOf(prefs(context).getString(KEY, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        navDayNight.value = prefs(context).getBoolean(KEY_NAV_DAY_NIGHT, false)
        mapMode.value = mapModeFrom(prefs(context).getString(KEY_MAP, null))
        val p = prefs(context)
        if (p.contains(KEY_LAT)) {
            lat = p.getFloat(KEY_LAT, 0f).toDouble()
            lng = p.getFloat(KEY_LNG, 0f).toDouble()
            at = p.getLong(KEY_AT, 0L)
        }
        refreshNight()
    }

    fun set(context: Context, value: ThemeMode) {
        mode.value = value
        prefs(context).edit().putString(KEY, value.name).apply()
        refreshNight()
    }

    fun setMap(context: Context, value: MapThemeMode) {
        mapMode.value = value
        prefs(context).edit().putString(KEY_MAP, value.name).apply()
        refreshNight()
    }

    fun setNavDayNight(context: Context, value: Boolean) {
        navDayNight.value = value
        prefs(context).edit().putBoolean(KEY_NAV_DAY_NIGHT, value).apply()
    }

    /**
     * Feed the sun calculation a position. **Stored ROUNDED to about a kilometer** - sunrise moves
     * by ~4 seconds per kilometer of longitude, so the extra precision buys nothing and there is no
     * reason for a theme setting to keep a precise record of where its owner was.
     */
    fun rememberLocation(context: Context, latitude: Double, longitude: Double) {
        val rLat = Math.round(latitude * 100.0) / 100.0
        val rLng = Math.round(longitude * 100.0) / 100.0
        if (rLat == lat && rLng == lng) return
        lat = rLat
        lng = rLng
        at = System.currentTimeMillis()
        prefs(context).edit().putFloat(KEY_LAT, rLat.toFloat()).putFloat(KEY_LNG, rLng.toFloat()).putLong(KEY_AT, at).apply()
        refreshNight()
    }

    /** Recompute [night]. Cheap (closed-form, no allocation to speak of) - called on a one-minute
     *  tick from VelaRoot and whenever the inputs change. */
    fun refreshNight() {
        val la = lat
        val ln = lng
        val now = System.currentTimeMillis()
        val usable = la != null && ln != null && now - at < POINT_MAX_AGE_MS && locationAllowed()
        if (usable) {
            sunriseSunset.value = SunTimes.riseSet(la!!, ln!!, now)
            night.value = SunTimes.isNight(la, ln, now)
        } else {
            sunriseSunset.value = null
            // No fix yet (cold launch), location denied or revoked, or the stored point is a day
            // old: fall back to the clock rather than the sun somewhere the phone no longer is.
            night.value = SunTimes.isNightByClock(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
        }
    }

    private fun locationAllowed(): Boolean {
        val c = appContext ?: return true
        return androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** The stored map mode; nothing stored, or a name this build does not know, is FOLLOW. */
    internal fun mapModeFrom(stored: String?): MapThemeMode =
        runCatching { MapThemeMode.valueOf(stored ?: "FOLLOW") }.getOrDefault(MapThemeMode.FOLLOW)

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "theme_mode"
    private const val KEY_NAV_DAY_NIGHT = "theme_nav_day_night"
    private const val KEY_MAP = "theme_map_mode"
    private const val KEY_LAT = "theme_sun_lat"
    private const val KEY_LNG = "theme_sun_lng"
    private const val KEY_AT = "theme_sun_at"
}

/** The single source of truth for "is the app dark right now" - honors the user's
 * [AppTheme] choice, falling back to the OS theme only in [ThemeMode.SYSTEM].
 * Every place that used to call `isSystemInDarkTheme()` should call this instead. */
@Composable
fun isAppInDarkTheme(): Boolean {
    // Navigating with the nav day/night toggle on overrides the chosen mode - that IS the setting
    // (issue #262: "my default is dark, but driving in daylight I want the light map"). Driving
    // mode with no route counts as driving (issue #727).
    if ((AppTheme.navigating.value || app.vela.ui.DrivingMode.on.value) && AppTheme.navDayNight.value) return AppTheme.night.value
    return when (AppTheme.mode.value) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> app.vela.ui.NavSchedule.phoneDarkOverride(isSystemInDarkTheme())
        ThemeMode.AUTO -> AppTheme.night.value
    }
}

/** Is the MAP dark right now: the nav day/night override first (it is about the map), then the
 *  map's own mode, then whatever the app is. */
@Composable
fun isMapDark(): Boolean {
    // A look Vela does not paint is as dark as it comes: Vela's layers over it follow the look.
    val look = app.vela.ui.MapLook.style.value
    if (!look.painted) return look.darkLook
    val driveDayNight = (AppTheme.navigating.value || app.vela.ui.DrivingMode.on.value) && AppTheme.navDayNight.value
    return mapDarkOwn(AppTheme.mapMode.value, driveDayNight) { AppTheme.night.value }
        ?: isAppInDarkTheme()
}

/** The map's light or dark from its own settings, or null when it follows the app: the day and
 *  night while driving override first, then the map's mode. [night] is read only when it decides,
 *  so a fixed map does not recompose at sunset. Plain Kotlin for the unit test. */
internal fun mapDarkOwn(mode: MapThemeMode, driveDayNight: Boolean, night: () -> Boolean): Boolean? =
    if (driveDayNight) night() else when (mode) {
        MapThemeMode.LIGHT -> false
        MapThemeMode.DARK -> true
        MapThemeMode.AUTO -> night()
        MapThemeMode.FOLLOW -> null
    }

/** Whether "Day and night while navigating" can change anything. It cannot while the app goes by
 *  the sun and the map follows the app or goes by the sun too; with a fixed map under an app on
 *  AUTO it still turns that map day and night for drives. */
internal fun navDayNightMatters(mode: ThemeMode, mapMode: MapThemeMode): Boolean =
    mode != ThemeMode.AUTO || mapMode == MapThemeMode.LIGHT || mapMode == MapThemeMode.DARK

/** The map's true-black palette: only while the map follows an AMOLED app theme. */
@Composable
fun isMapAmoled(): Boolean = app.vela.ui.MapLook.style.value.painted && AppTheme.mapMode.value == MapThemeMode.FOLLOW && isAppInAmoled()

/** True when the app is resolved to true-black AMOLED theme right now. */
@Composable
fun isAppInAmoled(): Boolean =
    AppTheme.mode.value == ThemeMode.AMOLED && isAppInDarkTheme()


/**
 * Material You dynamic color preference (issue #15). Same reactive-holder shape as
 * [AppTheme]: flip it in Settings and every MaterialTheme surface recomposes with the
 * wallpaper palette. Off by default - Vela teal is the out-of-the-box look, dynamic
 * color is the opt-in customization. Android 12+ only; the toggle is hidden below that.
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
