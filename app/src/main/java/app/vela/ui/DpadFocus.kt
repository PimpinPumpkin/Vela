package app.vela.ui

import android.content.pm.PackageManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
 * D-pad-FIRST initial focus (hard rule, docs/dpad.md): place focus on this element as soon
 * as the screen/overlay appears, so a D-pad user NEVER has to press a key just to "wake up"
 * focus — every screen or view must land already focused. Attach the returned
 * [FocusRequester] to the primary element via `Modifier.focusRequester(...)`.
 *
 * Retries briefly (20 × 50 ms) because a freshly-composed focus node often isn't attached on
 * the first frame — the first `requestFocus()` would silently throw and nothing would end up
 * focused (this is the exact reason MapScreen's map-target acquisition retries; generalized
 * here). Only requests on a **D-pad-first device** (`rememberDpadFirstDevice`), so touch UX
 * is byte-identical — no focus ring pops up under touch. Pass [keys] to re-grab focus when an
 * overlay's content swaps (e.g. a step preview); omit for a plain screen that focuses once.
 */
@Composable
fun rememberDpadAutoFocus(vararg keys: Any?): FocusRequester {
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    LaunchedEffect(dpadFirst, *keys) {
        if (dpadFirst) {
            repeat(20) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    return fr
}

// Note (docs/dpad.md): a Compose Material secondary window — a `DropdownMenu`'s Popup or an
// `AlertDialog` — opens with WINDOW focus but no content Compose-focused, and Compose sets that
// focus ONLY on the first real key event. Nothing in-app pre-places it (~10 approaches verified
// failing on-device: requestFocus on the item / a custom focusable child / a TextButton / a bare
// .clickable, retry-until-onFocusEvent, outer-scope delayed request, FocusManager.moveFocus(Down),
// and synthetic KeyEvent dispatch to the popup ComposeView and its rootView with/without a DPAD
// source). The ONE seam that works is a hand-built RAW `Dialog` with an explicit `.focusable()`
// element (the photo gallery proves it). SOLVED on that seam: `VelaDialog` (ui/VelaDialog.kt)
// replaces every AlertDialog, and `VelaMenu` (ui/VelaMenu.kt) replaces every DropdownMenu (touch
// still gets the anchored DropdownMenu; D-pad gets a raw-Dialog chooser that auto-focuses item 0).
// So every menu/dialog now lands already focused — see those files.

/**
 * Robust auto-focus Modifier — the version to use when the target may be **off-screen** (below
 * the fold in a scroll container) or otherwise not laid out on the first frame. There,
 * [rememberDpadAutoFocus]'s "requestFocus didn't throw → stop" retry gives up while focus never
 * actually landed (measured on-device: the Welcome screen's off-screen Get-started button stayed
 * unfocused). This variant keeps re-requesting (every 50 ms, up to ~2 s) **until `onFocusEvent`
 * confirms focus truly landed**, then stops (so it never fights the user once they navigate
 * away). Apply directly to the target: `Modifier.dpadAutoFocus()`. No-op under touch.
 *
 * NB this still can't focus a `DropdownMenu` popup item — that's a separate, unfixable Compose
 * limitation (the popup only takes item focus on the first key event; requestFocus/moveFocus
 * can't pre-place it, five approaches verified). Menus stay stock DropdownMenus (fully
 * navigable) so touch is byte-identical. See docs/dpad.md "Known limitations".
 */
fun Modifier.dpadAutoFocus(): Modifier = composed {
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(dpadFirst) {
        if (dpadFirst) {
            repeat(40) {
                if (focused) return@LaunchedEffect
                runCatching { fr.requestFocus() }
                kotlinx.coroutines.delay(50)
            }
        }
    }
    this
        .focusRequester(fr)
        .onFocusEvent { focused = it.isFocused }
}

/**
 * A clearly visible focus ring for D-pad traversal, drawn only while the element (or a
 * descendant — Material buttons host their own focus target) holds focus AND the UI is
 * key-driven, so it never appears under touch. Apply it to any interactive element; pass
 * the element's own [shape] so the ring hugs it.
 */
/**
 * Makes a text field D-pad ESCAPABLE: UP/DOWN move focus to the previous/next form control
 * instead of being swallowed by the field's own cursor handling. Without this, a single- or
 * multi-line `TextField`/`BasicTextField` eats the vertical arrows, trapping focus on the
 * field so nothing below it is reachable (measured on-device: the search field, and the
 * "Try the voice" / filter fields in Settings). Apply to any text field that sits in a
 * vertical list of focusable controls. Inert under touch. Fires via `onPreviewKeyEvent`
 * (root→leaf) so it wins before the field consumes the key. Falls through (returns false)
 * at a list edge where focus can't move, so the field still behaves normally there.
 */
fun Modifier.dpadFieldEscape(): Modifier = composed {
    val dpad = rememberDpadMode()
    val focusManager = LocalFocusManager.current
    if (!dpad) {
        this
    } else {
        this.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) {
                // Swallow the matching key-up too, so the field never sees a half event.
                ev.key == Key.DirectionDown || ev.key == Key.DirectionUp
            } else {
                when (ev.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    else -> false
                }
            }
        }
    }
}

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
