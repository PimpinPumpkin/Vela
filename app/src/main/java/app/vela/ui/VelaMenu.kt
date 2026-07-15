package app.vela.ui

// D-pad-first menu (docs/dpad.md). A Compose `DropdownMenu` Popup can't be pre-focused (~8
// approaches proven to fail), so on a D-pad-first device this renders the menu as a raw `Dialog`
// chooser instead - which DOES auto-focus its first item (the gallery/VelaDialog pattern). Under
// TOUCH it renders the ordinary anchored `DropdownMenu`, unchanged, so touch stays byte-identical.
// Same call shape as DropdownMenu: `VelaMenu(expanded, onDismissRequest) { item("A") { .. }; item("B") { .. } }`.
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Scope for [VelaMenu] content - call [item] once per menu entry (same order as it should show). */
class VelaMenuScope internal constructor(internal val dpad: Boolean) {
    internal var index = 0
}

/**
 * Drop-in [DropdownMenu] replacement that is **D-pad-first**: on a D-pad device it shows a raw
 * `Dialog` chooser whose FIRST item is focused on open (so no wasted "enter the menu" press);
 * under touch it shows the normal anchored `DropdownMenu`. Fully navigable either way: OK selects,
 * DOWN/UP walk, BACK/outside dismiss.
 */
@Composable
fun VelaMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable VelaMenuScope.() -> Unit) {
    val dpadFirst = rememberDpadFirstDevice()
    if (dpadFirst) {
        if (expanded) {
            Dialog(onDismissRequest = onDismissRequest) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                    // Cap to 85% of the screen and scroll - a long menu (many items) must not run off
                    // a small screen with the bottom items unreachable. Focus follows into view as
                    // DOWN walks past the fold.
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        VelaMenuScope(dpad = true).content()
                    }
                }
            }
        }
    } else {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
            VelaMenuScope(dpad = false).content()
        }
    }
}

/** A TOGGLE menu entry: label + trailing Switch, the layers-panel row. Same dual rendering as
 *  [item] (plain row under touch, focusable auto-focused chooser row under D-pad, OK flips) but
 *  it does NOT dismiss - toggles are browse-several controls, the user flips a few then leaves. */
@Composable
fun VelaMenuScope.toggleItem(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    if (!dpad) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // Fork adaptation: hybrid touch+keypad phones can key-walk the anchored
                // DropdownMenu too, so the touch row still needs a visible focus ring.
                .dpadHighlight(RoundedCornerShape(8.dp))
                .clickable { onToggle(!checked) }
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth(),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
        return
    }
    val autoFocus = index == 0
    index++
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    if (autoFocus) {
        LaunchedEffect(dpadFirst) {
            if (dpadFirst) repeat(30) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .dpadHighlight(RoundedCornerShape(8.dp))
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onToggle(!checked); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(checked) { detectTapGestures { onToggle(!checked) } }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/** A single menu entry. Renders a `DropdownMenuItem` under touch, or a focusable chooser row
 * (auto-focused if it's the first) under D-pad. [onClick] should also dismiss the menu, exactly
 * as it did for the DropdownMenuItem it replaces. */
@Composable
fun VelaMenuScope.item(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    if (!dpad) {
        DropdownMenuItem(
            text = { Text(text) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
            onClick = onClick,
        )
        return
    }
    val autoFocus = index == 0
    index++
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    if (autoFocus) {
        LaunchedEffect(dpadFirst) {
            if (dpadFirst) repeat(30) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .dpadHighlight(RoundedCornerShape(8.dp))
            // Single focus target = the explicit .focusable() (the only thing requestFocus lands
            // on in a raw Dialog); OK fires the action; touch tap via pointerInput (not .clickable,
            // which would add a 2nd focus target).
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onClick(); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
