package app.vela.ui

import android.content.pm.PackageManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp

/**
 * D-pad / keyboard operability helpers. Vela must be FULLY drivable with a 5-key D-pad
 * (no touchscreen at all — touch is a bonus): every interactive element is reachable by
 * focus traversal, every gesture has a key alternative, and the focused element is
 * obvious. These helpers carry the "obvious" part plus the mode detection.
 *
 * Detection note (learned on a real keypad phone, 2026-07-07): feature phones LIE about
 * `FEATURE_TOUCHSCREEN` (the tiny `mtk-tpd` panel reports `touchscreen=finger`), so that
 * flag is useless for "is this a D-pad device". The reliable signal is
 * `KeyCharacterMap.deviceHasKey(KEYCODE_DPAD_*)` — true iff the hardware actually has
 * D-pad keys. See docs/dpad.md.
 */

/**
 * True on a device where the D-pad is a PRIMARY input: it has physical D-pad keys, OR it
 * genuinely has no touchscreen. On such a device the app defaults to D-pad-first —
 * affordances (crosshair, zoom buttons, focus rings) are shown persistently and initial
 * focus is placed for the user, rather than waiting for a first keypress to flip modes.
 */
@Composable
fun rememberDpadFirstDevice(): Boolean {
    val context = LocalContext.current
    return remember { detectDpadFirst(context) }
}

/** Multi-signal D-pad detection. Any ONE positive signal wins, because different keypad
 *  phones expose the D-pad differently and none of the signals is reliable alone:
 *  - a genuinely touchless device;
 *  - ANY currently-attached input device that reports `SOURCE_DPAD`, INCLUDING the
 *    framework's "Virtual" aggregate device (id -1). On the real MTK keypad phone tested,
 *    the DPAD source lives ONLY on that Virtual device (`src=0x301` = KEYBOARD|DPAD); the
 *    physical `mtk-kpd` reports just `0x101` (KEYBOARD) even though its key layout emits
 *    DPAD keycodes — so filtering out virtual devices (an earlier bug) made detection
 *    FALSE and the whole D-pad UI never appeared. Do NOT exclude virtual devices here.
 *  - `KeyCharacterMap.deviceHasKey(DPAD_CENTER)` (returned false on the MTK one — kept
 *    only as a last-resort signal for other hardware). */
private fun detectDpadFirst(context: android.content.Context): Boolean {
    val noTouch = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    val hasDpadDevice = runCatching {
        InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            (dev.sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        }
    }.getOrDefault(false)
    val hasDpadKey = runCatching {
        KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DPAD_CENTER)
    }.getOrDefault(false)
    return noTouch || hasDpadDevice || hasDpadKey
}

/** Back-compat alias kept for call sites that mean "structural D-pad-first decisions". */
@Composable
fun rememberNoTouchDevice(): Boolean = rememberDpadFirstDevice()

/** True while the user is driving the UI with keys — always true on a D-pad-first device,
 *  and flips live from the input mode on a touch phone with an attached keyboard/remote
 *  (affordances appear on the first key press, melt away on the next tap). */
@Composable
fun rememberDpadMode(): Boolean {
    val dpadFirst = rememberDpadFirstDevice()
    val inputModeManager = LocalInputModeManager.current
    return dpadFirst || inputModeManager.inputMode == InputMode.Keyboard
}

/**
 * A clearly visible focus ring for D-pad traversal, drawn only while the element (or a
 * descendant — Material buttons host their own focus target) holds focus AND the UI is
 * key-driven, so it never appears under touch. Apply it to any interactive element; pass
 * the element's own [shape] so the ring hugs it.
 */
fun Modifier.dpadHighlight(shape: Shape = RoundedCornerShape(14.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val dpadFirst = rememberDpadFirstDevice()
    val inputModeManager = LocalInputModeManager.current
    // On a D-pad-first device the input mode may still read Touch until the first key
    // event, so honour dpadFirst directly — rings must be visible from the very start.
    val show = focused && (dpadFirst || inputModeManager.inputMode == InputMode.Keyboard)
    this
        .onFocusEvent { focused = it.hasFocus }
        .then(
            if (show) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            },
        )
}
