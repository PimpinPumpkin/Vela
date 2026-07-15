package app.vela.ui

// D-pad-first dialog (docs/dpad.md). A Material `AlertDialog` opens with its window focused but
// no button Compose-focused, and NOTHING pre-places that focus (~10 approaches proven to fail).
// A hand-built RAW `Dialog` with a directly-`.focusable()`/`.clickable` element DOES auto-focus
// (Vela's photo gallery proves it). So this is a drop-in AlertDialog replacement that lands
// already focused on the safe/dismiss button - OK activates it, arrows move to confirm, BACK
// dismisses. Styled to match Material's AlertDialog so touch looks the same.
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for a two-button `AlertDialog` that is **D-pad-first**: it auto-focuses the
 * dismiss (safe) button on open, so the first OK activates it and arrows reach the confirm button -
 * no wasted "enter the dialog" press. Pass a composable [text] body (may hold checkboxes etc.).
 * Under touch it looks and behaves like the Material dialog it replaces.
 */
@Composable
fun VelaDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties()) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            // Cap to 90% of the screen and SCROLL the body so a tall dialog (e.g. the diagnostics
            // checkboxes) never pushes the buttons off a small screen - the title stays pinned at
            // the top and the buttons at the bottom, only the middle scrolls. Without this, on a
            // feature-phone-sized display the Confirm/Dismiss buttons fell off-screen, unreachable.
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                    .padding(24.dp),
                horizontalAlignment = if (icon != null) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.height(16.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(16.dp))
                // weight(1f, fill = false) + verticalScroll: the body takes only what it needs, but
                // when the whole dialog would exceed the cap it shrinks and scrolls instead of
                // shoving the buttons off-screen. Focusable body content (checkboxes) scrolls into
                // view as D-pad focus moves through it.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        text()
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    // Dismiss (safe) side auto-focused; confirm reachable by arrow.
                    DialogButton(dismissText, onDismiss, autoFocus = true)
                    Spacer(Modifier.width(8.dp))
                    DialogButton(confirmText, onConfirm, autoFocus = false)
                }
            }
        }
    }
}

/** A directly-`.clickable` text button (so requestFocus lands on it inside a raw Dialog window -
 * a Material `TextButton`'s nested focusable does not, verified on-device). Touch tap and D-pad
 * OK both fire [onClick]; when [autoFocus] it grabs focus on open. */
@Composable
private fun DialogButton(text: String, onClick: () -> Unit, autoFocus: Boolean) {
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    if (autoFocus) {
        // EXACT photo-gallery pattern (the one proven to auto-focus in a raw Dialog): retry a
        // plain requestFocus. Focus target is an explicit .focusable() below (a Material button's
        // nested focusable / a bare .clickable did NOT take focus here).
        LaunchedEffect(dpadFirst) {
            if (dpadFirst) repeat(30) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .focusRequester(fr)
            .dpadHighlight(RoundedCornerShape(20.dp))
            // OK/Enter fires the action. The single focus target is the explicit .focusable()
            // below - the ONLY thing requestFocus lands on in a raw Dialog (gallery-proven); a
            // Material button's nested focusable / a bare .clickable did not take focus.
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onClick(); true
                } else {
                    false
                }
            }
            .focusable()
            // Touch tap via pointerInput (NOT .clickable, which would add a 2nd focus target).
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
