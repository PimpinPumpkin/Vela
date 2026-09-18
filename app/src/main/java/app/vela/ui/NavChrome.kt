package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Where the name of the road you are driving is shown during navigation (issue #288 asked for
 *  it under the arrow; that pill cannot be centered for long names because it is pinned to the
 *  arrow, so the default is Google's fixed spot above the bottom bar). Values: "off", "bar", "puck". */
object RoadLabel {
    const val OFF = "off"
    const val BAR = "bar"
    const val PUCK = "puck"
    /** Inside the nav bar, where the lift chevron sits (issue #553). */
    const val IN_BAR = "inbar"
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

/** The navigation arrow's size and colors (issue #344): three sizes for eyes that want a bigger
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

/**
 * Where the PAUSE control sits during a drive (user 2026-09-18).
 *
 * On (the default) it takes the nav bar's right slot, which on a touch phone is an empty 54 dp
 * spacer holding the figures centered against End, and the right-edge stack keeps a plain mute
 * button. That puts the drive's two "hold something" controls where each is actually reached for:
 * pause beside End and the trip figures, mute up with the other map controls, neither of them
 * behind a pop-out.
 *
 * Off restores the combined button in the stack (tap opens, second tap pauses, long press mutes).
 *
 * The bar's right slot is also where the step-list button goes when it is asked for, and that
 * button wins: `PreferButtons` and a keypad-first device asked for a discrete target, so taking it
 * away to make room for this one is the wrong trade. The chevron handle opens the step list on its
 * own in both layouts, so nothing becomes unreachable either way.
 */
object PauseInBar {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "nav_pause_in_bar"
}

/**
 * What happens to a faster-route offer nobody answers (issue #594).
 *
 * The offer used to sit there until it was answered, which is a prompt covering part of the map
 * asking a driver to make a decision with their hands on the wheel. It always resolves itself now.
 * On (the default, and what Google does) an unanswered offer is TAKEN: the route it names is
 * faster, that is the whole reason it appeared, and the drive continues either way. Off, it is
 * dismissed instead, for anyone who would rather keep the route they chose unless they say so.
 *
 * "Leave it on screen" is deliberately not one of the choices.
 */
object FasterRouteAuto {
    /** True = take it, false = let it go. */
    val accept = mutableStateOf(true)

    fun init(context: Context) {
        accept.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        accept.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "faster_route_auto"
}
