package app.vela.ui

import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m"
    else String.format(Locale.US, "%.1f km", meters / 1000.0)

fun formatDuration(seconds: Double): String {
    val totalMin = (seconds / 60.0).roundToInt()
    if (totalMin < 1) return "<1 min"
    if (totalMin < 60) return "$totalMin min"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0) "$h h" else "$h h $m min"
}
