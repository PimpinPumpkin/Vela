package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Distance-unit preference. Backed by Compose state so reads inside composables
 * (via [formatDistance]) recompose when it flips, and persisted so it sticks.
 * Defaults to imperial in the US/Liberia/Myanmar, metric elsewhere.
 */
object Units {
    val imperial = mutableStateOf(false)

    fun init(context: Context) {
        val default = Locale.getDefault().country in setOf("US", "LR", "MM")
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
        if (feet < 1000) "${feet.roundToInt()} ft"
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

/** Wall-clock arrival time for a trip [remainingSeconds] from now, e.g. "7:42 PM"
 *  (locale-aware 12/24-hour), the way Google shows ETA during navigation. */
fun formatArrivalClock(remainingSeconds: Double): String {
    val arrival = java.time.LocalTime.now().plusSeconds(remainingSeconds.toLong())
    return arrival.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
}
