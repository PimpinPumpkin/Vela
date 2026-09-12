package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Distance-unit preference. Backed by Compose state so reads inside composables
 * (via [formatDistance]) recompose when it flips, and persisted so it sticks.
 * Defaults to imperial where roads are signed in miles (US, UK, Liberia, Myanmar), metric elsewhere.
 */
object Units {
    val imperial = mutableStateOf(false)

    fun init(context: Context) {
        // The DEVICE's locale, not the JVM default: AppLocale.wrap runs before this (in
        // attachBaseContext) and sets the JVM default to the in-app language, which for the
        // plain "English" choice has no country, so a US phone silently flipped to kilometres
        // the moment its owner touched the language picker (seen on the P4a, 2026-09-12).
        val country = android.content.res.Resources.getSystem().configuration.locales.get(0)?.country
            ?: Locale.getDefault().country
        val default = country in setOf("US", "GB", "LR", "MM")
        imperial.value = prefs(context).getBoolean(KEY, default)
    }

    fun set(context: Context, value: Boolean) {
        imperial.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_units", Context.MODE_PRIVATE)
    private const val KEY = "imperial"
}

fun formatDistance(meters: Double): String =
    if (Units.imperial.value) {
        val feet = meters * 3.28084
        // Google-style feet: 50 ft steps at/above 100 ft, 10 ft steps below (min 10, never "0 ft").
        if (feet < 1000) "${if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50} ft"
        else String.format(Locale.US, "%.1f mi", meters / 1609.344)
    } else {
        if (meters < 1000) "${meters.roundToInt()} m"
        else String.format(Locale.US, "%.1f km", meters / 1000.0)
    }

fun formatDuration(seconds: Double): String {
    val totalMin = (seconds / 60.0).roundToInt()
    if (totalMin < 1) return "<1 min"
    if (totalMin < 60) return "$totalMin min"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0) "$h h" else "$h h $m min"
}

/** Current speed as a (value, unit) pair for the speedometer, e.g. (65, "mph") —
 *  imperial vs metric follows the [Units] preference. */
fun formatSpeed(metersPerSecond: Float): Pair<Int, String> =
    if (Units.imperial.value) {
        (metersPerSecond * 2.236936).roundToInt() to "mph"
    } else {
        (metersPerSecond * 3.6).roundToInt() to "km/h"
    }

/** Posted speed limit (given in km/h from OSM/GraphHopper) as a (value, unit) pair in the display
 *  units. An mph-tagged US road round-trips exactly: 35 mph → GraphHopper's max_speed EV quantizes to
 *  56.0 km/h → ×0.621371 = 34.8 → rounds back to 35 mph (all 25–85 mph US limits round-trip). */
fun formatSpeedLimit(kmh: Double): Pair<Int, String> =
    if (Units.imperial.value) (kmh * 0.621371).roundToInt() to "mph"
    else kmh.roundToInt() to "km/h"

/** The device's 12/24-hour clock SETTING (Settings > System > Date & time), which is separate
 *  from the locale: a US-English phone set to 24-hour shows "19:42" everywhere else and used to
 *  show "7:42 PM" here (issue #357). Read at startup and on every resume; mirrored into :core's
 *  [app.vela.core.data.ClockFormat] for the transit boards. */
object Clock24 {
    val on = mutableStateOf(false)

    fun refresh(context: Context) {
        val v = android.text.format.DateFormat.is24HourFormat(context)
        on.value = v
        app.vela.core.data.ClockFormat.use24h = v
    }
}

/** Wall-clock arrival time for a trip [remainingSeconds] from now, e.g. "7:42 PM" or "19:42",
 *  following the device's 12/24-hour setting, the way Google shows ETA during navigation. */
fun formatArrivalClock(remainingSeconds: Double): String {
    val arrival = java.time.LocalTime.now().plusSeconds(remainingSeconds.toLong())
    val fmt = if (Clock24.on.value) java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        else java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
    return arrival.format(fmt)
}

/** "Sep 7, 7:42 PM" / "7 Sep, 19:42": a saved trip's or log's timestamp in the device's own date
 *  and time formats. */
fun formatDateTime(context: Context, epochMs: Long): String {
    val d = java.util.Date(epochMs)
    return android.text.format.DateFormat.getMediumDateFormat(context).format(d) + ", " +
        android.text.format.DateFormat.getTimeFormat(context).format(d)
}
