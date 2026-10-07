package app.vela.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.mutableIntStateOf

/**
 * Two hardware keys that zoom the map from anywhere on it, without engaging the map target first
 * (issue #694): a keypad phone can give zoom to 2 and 5, a keyboard to any pair. Set in Settings >
 * Navigation. 0 means not set.
 *
 * [MainActivity] offers every key event here. A key that types (a digit, a letter) is offered
 * only after the app's own handling passed on it, so it still types in a text field. A key that
 * does not type (volume, camera) is offered first, because the system would otherwise use it.
 */
object ZoomKeys {
    val zoomIn = mutableIntStateOf(0)
    val zoomOut = mutableIntStateOf(0)

    /** The map's zoom, wired by the map view while it is on screen. */
    @Volatile var zoomBy: ((Double) -> Unit)? = null

    /** False while a Settings page covers the map. */
    @Volatile var mapShowing = true

    fun init(context: Context) {
        zoomIn.intValue = prefs(context).getInt(KEY_IN, 0)
        zoomOut.intValue = prefs(context).getInt(KEY_OUT, 0)
    }

    fun set(context: Context, zoomInKey: Boolean, keyCode: Int) {
        // One key cannot do both: giving it to one side takes it from the other.
        if (zoomInKey) {
            zoomIn.intValue = keyCode
            if (keyCode != 0 && zoomOut.intValue == keyCode) zoomOut.intValue = 0
        } else {
            zoomOut.intValue = keyCode
            if (keyCode != 0 && zoomIn.intValue == keyCode) zoomIn.intValue = 0
        }
        prefs(context).edit().putInt(KEY_IN, zoomIn.intValue).putInt(KEY_OUT, zoomOut.intValue).apply()
    }

    /** +1 or -1 when [keyCode] is one of the two keys, else 0. */
    fun stepFor(keyCode: Int): Int = when {
        keyCode == 0 -> 0
        keyCode == zoomIn.intValue -> 1
        keyCode == zoomOut.intValue -> -1
        else -> 0
    }

    /** Keys that cannot be assigned: the app is driven with them. */
    fun assignable(keyCode: Int): Boolean = keyCode !in RESERVED && keyCode != KeyEvent.KEYCODE_UNKNOWN

    /** Zooms on the key's first down and swallows its repeats and its up. True when the event
     *  was one of the two keys and the map is showing. */
    fun handle(event: KeyEvent): Boolean {
        val step = stepFor(event.keyCode)
        if (step == 0 || !mapShowing) return false
        val zoom = zoomBy ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) zoom(step.toDouble())
        return true
    }

    /** "2", "Volume up", "F5": the key's name without Android's prefix. */
    fun label(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }

    private val RESERVED = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MENU,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_IN = "zoom_key_in"
    private const val KEY_OUT = "zoom_key_out"
}
