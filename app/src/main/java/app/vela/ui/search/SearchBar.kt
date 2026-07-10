package app.vela.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
// D-pad-only operation (docs/dpad.md) - one import block so upstream merges stay clean.
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape as DpadRoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.vela.ui.dpadHighlight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vela.R

@Composable
fun SearchBar(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
    onBack: (() -> Unit)? = null,
    dpadMode: Boolean = false,
    offline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // D-pad (docs/dpad.md): mere focus traversal must NOT fall into the text field (its
    // focus opens the full-screen search overlay - a trap when you're just walking the
    // chrome). In dpadMode the whole bar is ONE focus stop; pressing OK "arms" the field
    // and focuses it. We deliberately do NOT raise the soft IME: on a keypad device the
    // user types on hardware keys (which reach the focused field directly), and a shown
    // soft IME holds an active InputConnection that SWALLOWS the BACK key - reproduced
    // on-device as the "can't get out of search" trap. Hiding it lets BACK reach the app
    // and close the overlay in one press.
    var fieldArmed by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(fieldArmed) {
        if (fieldArmed) {
            runCatching { fieldFocus.requestFocus() }
            // Show/hide the soft keyboard by HOW the field was armed, not the static device type.
            // On a hybrid touch+keypad phone dpadMode is always true, so keying off
            // it hid the keyboard even when the user TAPPED the bar - they couldn't type by touch
            // (alltechdev tester report 2026-07-08). Keying off the live input mode shows the
            // keyboard for a touch tap and hides it for a D-pad OK, which is what each wants.
            if (inputMode.inputMode == InputMode.Keyboard) keyboard?.hide() else keyboard?.show()
        }
    }
    // Match the darker tone of the category chips (elevated chips sit on
    // surfaceContainerLow; the default Card uses the lightest surface) so the
    // search box and the chips read as one set.
    // D-pad: the "arm the field" clickable goes on the TEXT REGION only (below), NOT the
    // whole Card - a card-level clickable made the entire bar ONE focus stop and swallowed
    // the Settings gear inside it (the gear became unreachable by D-pad; measured on-device).
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A back arrow while the full-screen search page is open, else the
            // search glyph.
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.search_close_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Placeholder and input share one centered Box so they line up exactly.
            // In dpadMode this text region is the "arm the field" focus stop (OK arms +
            // focuses the field); the gear / clear / back stay independently focusable.
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .then(
                        if (dpadMode) {
                            Modifier
                                .dpadHighlight(DpadRoundedCornerShape(20.dp))
                                .clickable { fieldArmed = true }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    // Until armed in dpadMode the field is DISABLED, so it doesn't swallow a TOUCH tap
                    // (a live but unfocusable field ate the tap and did nothing - the "can't tap the
                    // search bar" bug on hybrid touch+keypad phones). Disabled lets the tap
                    // reach the Box's arm-clickable, which arms it; the field stays MOUNTED (only the
                    // enabled flag flips), so arming focuses it cleanly with no remount race.
                    enabled = !dpadMode || fieldArmed,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        // Unfocusable until armed in dpadMode; untouched otherwise.
                        .focusProperties { canFocus = !dpadMode || fieldArmed }
                        // Deterministic escape (docs/dpad.md): catch BACK on the focused
                        // field via onPreviewKeyEvent (root→leaf) + KeyDown, so it fires
                        // BEFORE BasicTextField's built-in "BACK clears focus" - which
                        // otherwise blurred the field on the down event and left the KeyUp
                        // with nothing focused, taking a 3rd press to escape (measured).
                        // With the IME up, its window still eats the first BACK to hide
                        // itself; the next BACK reaches here and closes - platform-standard
                        // two-press behaviour, now deterministic.
                        .onPreviewKeyEvent { ev ->
                            when {
                                // BACK/ESC closes the overlay (see comment above).
                                dpadMode && onBack != null &&
                                    (ev.key == Key.Back || ev.key == Key.Escape) -> {
                                    if (ev.type == KeyEventType.KeyDown) onBack()
                                    true
                                }
                                // DOWN moves focus OUT of the field into the entry rows /
                                // suggestions below. A single-line BasicTextField otherwise
                                // swallows DOWN (cursor move), trapping focus on the field so
                                // the Home/Work/recent/suggestion rows were unreachable by
                                // D-pad (measured). Explicitly hand focus downward instead.
                                dpadMode && ev.key == Key.DirectionDown -> {
                                    if (ev.type == KeyEventType.KeyDown) {
                                        focusManager.moveFocus(FocusDirection.Down)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        .onFocusChanged {
                            if (!it.isFocused) fieldArmed = false
                            onFocusChange(it.isFocused)
                        },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.search_clear_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Quiet offline indicator: a greyed globe-with-a-slash + "Offline", shown when there's no
            // connection. Hidden while typing so it doesn't crowd the clear "X".
            if (offline && query.isEmpty() && onBack == null) {
                Icon(
                    Icons.Default.PublicOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.search_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (searching) {
                // padding BEFORE size: sizing the indicator itself to 22dp keeps it a true circle.
                // The old `.size(22.dp).padding(end=10.dp)` squeezed the arc into a 12x22 box, so it
                // drew as an ellipse and looked like it was spinning off-axis (user report).
                CircularProgressIndicator(Modifier.padding(end = 10.dp).size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.search_settings_cd))
                }
            }
        }
    }
}
