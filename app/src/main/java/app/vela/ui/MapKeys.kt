package app.vela.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import app.vela.ui.map.MapCover
import app.vela.ui.map.MapDpadController

/**
 * Hardware keys that move the map from anywhere on it, without engaging the map target first
 * (issue #694): zoom, pan, rotate, and a select key that taps the middle of the visible map, held
 * for a long press. Set in Settings > Navigation, one key per action. 0 means not set.
 *
 * [MainActivity] offers every key event here. A key that types (a digit, a letter) is offered
 * only after the app's own handling passed on it, so it still types in a text field. A key that
 * does not type (volume, camera) is offered first, because the system would otherwise use it.
 */
object MapKeys {
    enum class Action(internal val pref: String) {
        ZOOM_IN("zoom_key_in"), ZOOM_OUT("zoom_key_out"),
        PAN_UP("map_key_pan_up"), PAN_DOWN("map_key_pan_down"),
        PAN_LEFT("map_key_pan_left"), PAN_RIGHT("map_key_pan_right"),
        ROTATE_LEFT("map_key_rotate_ccw"), ROTATE_RIGHT("map_key_rotate_cw"),
        SELECT("map_key_select"),
    }

    private val codes: Map<Action, MutableIntState> = Action.entries.associateWith { mutableIntStateOf(0) }

    /** The key set for [action], 0 for none. Snapshot state. */
    fun code(action: Action): Int = codes.getValue(action).intValue

    /** The map's key seam, wired by the map view while it is on screen. */
    @Volatile var controller: MapDpadController? = null

    /** False while a Settings page covers the map. */
    @Volatile var mapShowing = true

    /** The keypad layout the Settings button fills in. */
    val KEYPAD = mapOf(
        Action.ZOOM_OUT to KeyEvent.KEYCODE_1, Action.ZOOM_IN to KeyEvent.KEYCODE_3,
        Action.PAN_UP to KeyEvent.KEYCODE_2, Action.PAN_DOWN to KeyEvent.KEYCODE_8,
        Action.PAN_LEFT to KeyEvent.KEYCODE_4, Action.PAN_RIGHT to KeyEvent.KEYCODE_6,
        Action.ROTATE_LEFT to KeyEvent.KEYCODE_7, Action.ROTATE_RIGHT to KeyEvent.KEYCODE_9,
        Action.SELECT to KeyEvent.KEYCODE_5,
    )

    /** A pan moves the map by this share of the view, the arrows' step on the engaged map target. */
    private const val PAN = 0.22f

    /** Degrees a rotate key turns the map. More than the 12 degrees within which a finished turn
     *  springs back to north, so one press is kept. */
    private const val ROTATE_DEG = 15.0

    /** How long the select key is held before it long-presses, as OK on the map target. */
    private const val LONG_PRESS_MS = 500L

    private var selectHeld = false

    fun init(context: Context) {
        val p = prefs(context)
        for (a in Action.entries) codes.getValue(a).intValue = p.getInt(a.pref, 0)
    }

    /** Gives [keyCode] to [action]. One key does one thing: giving it here takes it from any
     *  other action. 0 clears the action. */
    fun set(context: Context, action: Action, keyCode: Int) {
        if (keyCode != 0) for ((a, s) in codes) if (a != action && s.intValue == keyCode) s.intValue = 0
        codes.getValue(action).intValue = keyCode
        save(context)
    }

    /** Sets every action to [KEYPAD]. */
    fun useKeypadLayout(context: Context) {
        for ((a, s) in codes) s.intValue = KEYPAD[a] ?: 0
        save(context)
    }

    /** The action [keyCode] is set to, or null. */
    fun actionFor(keyCode: Int): Action? =
        if (keyCode == 0) null else codes.entries.firstOrNull { it.value.intValue == keyCode }?.key

    /** Keys that cannot be assigned: the app is driven with them. */
    fun assignable(keyCode: Int): Boolean = keyCode !in RESERVED && keyCode != KeyEvent.KEYCODE_UNKNOWN

    /**
     * Acts on a key set to an action. Zoom acts on the key's first down; pan and rotate on every
     * down, so a held key keeps moving the map; select taps on the key's up, or long-presses once
     * it has been held [LONG_PRESS_MS]. True when the event was a set key and the map is showing.
     */
    fun handle(event: KeyEvent): Boolean {
        val action = actionFor(event.keyCode) ?: return false
        if (!mapShowing) return false
        val c = controller ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        // Zoom and rotate turn about the crosshair, so the spot under it stays under it.
        val at = MapCover.center()
        when (action) {
            Action.ZOOM_IN -> if (first) zoom(c, 1.0, at)
            Action.ZOOM_OUT -> if (first) zoom(c, -1.0, at)
            Action.PAN_UP -> if (down) c.panBy(0f, -PAN)
            Action.PAN_DOWN -> if (down) c.panBy(0f, PAN)
            Action.PAN_LEFT -> if (down) c.panBy(-PAN, 0f)
            Action.PAN_RIGHT -> if (down) c.panBy(PAN, 0f)
            Action.ROTATE_LEFT -> if (down) c.rotateBy(-ROTATE_DEG, at?.first, at?.second)
            Action.ROTATE_RIGHT -> if (down) c.rotateBy(ROTATE_DEG, at?.first, at?.second)
            Action.SELECT -> select(event, c)
        }
        return true
    }

    private fun zoom(c: MapDpadController, delta: Double, at: Pair<Float, Float>?) {
        if (at == null) c.zoomBy(delta) else c.zoomAt(delta, at.first, at.second)
    }

    private fun select(event: KeyEvent, c: MapDpadController) {
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> selectHeld = false
            event.action == KeyEvent.ACTION_DOWN -> if (!selectHeld && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                selectHeld = true
                MapCover.center()?.let { (x, y) -> c.longPressAt(x, y) }
            }
            event.action == KeyEvent.ACTION_UP -> {
                if (!selectHeld) MapCover.center()?.let { (x, y) -> c.tapAt(x, y) }
                selectHeld = false
            }
        }
    }

    /** "2", "Volume up", "F5": the key's name without Android's prefix. */
    fun label(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }

    private fun save(context: Context) {
        val e = prefs(context).edit()
        for ((a, s) in codes) e.putInt(a.pref, s.intValue)
        e.apply()
    }

    private val RESERVED = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MENU,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
}
