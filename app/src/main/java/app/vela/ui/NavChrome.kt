package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Where the name of the road you are driving is shown during navigation (issue #288 asked for
 *  it under the arrow; that pill cannot be centred for long names because it is pinned to the
 *  arrow, so the default is Google's fixed spot above the bottom bar). Values: "off", "bar", "puck". */
object RoadLabel {
    const val OFF = "off"
    const val BAR = "bar"
    const val PUCK = "puck"
    val mode = mutableStateOf(BAR)

    fun init(context: Context) {
        mode.value = prefs(context).getString(KEY, BAR) ?: BAR
    }

    fun set(context: Context, value: String) {
        mode.value = value
        prefs(context).edit().putString(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "road_label"
}

/** The navigation arrow's size and colours (issue #344): three sizes for eyes that want a bigger
 *  target, and a white-disc variant so the puck does not blend into the blue route line. Both the
 *  follow-mode Compose overlay and the map symbol draw from the same bitmap, so one holder feeds
 *  both; the map style key carries [key] so a change re-registers the symbol image. */
object PuckStyle {
    const val SIZE_NORMAL = "normal"
    const val SIZE_LARGE = "large"
    const val SIZE_XL = "xl"
    const val STYLE_BLUE = "blue"
    const val STYLE_WHITE = "white"
    val size = mutableStateOf(SIZE_NORMAL)
    val style = mutableStateOf(STYLE_BLUE)

    fun scale(): Float = when (size.value) {
        SIZE_LARGE -> 1.25f
        SIZE_XL -> 1.5f
        else -> 1f
    }
    fun whiteDisc(): Boolean = style.value == STYLE_WHITE
    fun key(): String = "${size.value}/${style.value}"

    fun init(context: Context) {
        val p = prefs(context)
        size.value = p.getString(KEY_SIZE, SIZE_NORMAL) ?: SIZE_NORMAL
        style.value = p.getString(KEY_STYLE, STYLE_BLUE) ?: STYLE_BLUE
    }

    fun setSize(context: Context, value: String) {
        size.value = value
        prefs(context).edit().putString(KEY_SIZE, value).apply()
    }

    fun setStyle(context: Context, value: String) {
        style.value = value
        prefs(context).edit().putString(KEY_STYLE, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_SIZE = "puck_size"
    private const val KEY_STYLE = "puck_style"
}

/** "Prefer buttons over swipes": keeps a discrete button wherever a gesture has one (today: the
 *  step-list button on the nav bar beside the swipe-up handle). Off by default; keypad-first
 *  devices behave as if it were on. */
object PreferButtons {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "prefer_buttons"
}
