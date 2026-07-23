package app.vela.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.vela.ui.dpadContainVertical
import app.vela.ui.dpadSwallowHorizontal

/**
 * The shared frame for every Settings page (the hub and each sub-screen): a back-button TopAppBar
 * over a scrolling Column. It carries, in ONE place, the hard-won D-pad focus plumbing the old
 * single-page Settings had inline (docs/dpad.md, both halves device-found at 240x320):
 *
 *  - The page opens ALREADY FOCUSED on the Back button, via a settle-window retry loop (below)
 *    that is STRONGER than `dpadAutoFocus(requester)`: it keeps re-requesting when focus lands and
 *    is then STOLEN by the soft-key bar teardown's window-focus churn, not just until the first
 *    land. A page that places initial focus elsewhere (the hub restoring focus to the row you came
 *    back from) passes [autoFocusBack] = false - the Back button keeps its requester so the UP
 *    bridge still works, it just doesn't grab focus on open.
 *  - DOWN from Back ENTERS the content: Compose's directional search can't cross the TopAppBar ->
 *    Column boundary and CLEARS focus instead, so Back routes DOWN straight to the first content
 *    row via requestFocus (never moveFocus, which clears at a container edge). The content lambda
 *    receives that bridge as [topRow] - ATTACH IT to the page's FIRST focusable control.
 *  - UP from the top content row routes back to Back (the mirror trap), gated on the top row
 *    actually holding focus.
 *  - Bare LEFT/RIGHT are swallowed on the Column AND the Back button (`dpadSwallowHorizontal`) - a
 *    no-target horizontal move in a verticalScroll Column clears focus irrecoverably. A horizontal
 *    row of siblings inside must drive its own L/R via `dpadRowSibling` (issue #24).
 *
 * All of it is inert under touch; the touch layout is byte-identical to a plain Scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    autoFocusBack: Boolean = true,
    content: @Composable ColumnScope.(topRow: Modifier) -> Unit,
) {
    val backFocus = remember { FocusRequester() }
    val topRowFocus = remember { FocusRequester() }
    var atTopItem by remember { mutableStateOf(false) }
    // The auto-focus SETTLE WINDOW (device-found on the hub, 240x320): opening Settings tears the
    // Yapchik soft-key bar down (SuppressBarWhile), and that Android-View removal churns window
    // focus and STEALS it off the freshly-focused Back button - the same churn the search bar's
    // armed field defends against (docs/dpad.md). dpadAutoFocus stops once focus lands, so the
    // steal left the page with NOTHING focused and the first keypress wasted re-establishing it
    // (test 02 caught it: focus Y = none after a pure-key open). So the scaffold re-requests Back
    // whenever, during the first ~2s, focus is neither on Back nor anywhere in the content - and a
    // real key press ends the window at once so it never fights the user.
    var backFocused by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var userDrove by remember { mutableStateOf(false) }
    // KNOWN WALL (device-logged, do not re-attempt): when Settings is the session's FIRST
    // focus-touching surface - entered via the soft-key Options menu, whose key events live in the
    // bar and the menu's own Dialog window, so the main window has never held Compose focus -
    // requestFocus AND moveFocus both no-op (40x50ms each, zero landings; the same wall as the
    // cold-open bare map, docs/dpad.md). There the accepted behaviour applies here too: the first
    // key press establishes focus ON THE BACK BUTTON (the first focusable - screenshot-verified,
    // ring and all), so exactly one press is spent, like the bare map. Once ANY focus has existed
    // in the session, this loop lands Back on open and re-lands it when the soft-key bar teardown's
    // window churn steals it.
    val dpadFirst = app.vela.ui.rememberDpadFirstDevice()
    if (autoFocusBack && dpadFirst) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // No fixed horizon: a 2s window LOST the race when the soft-key bar teardown's focus
            // steal landed late under load (audit_dynamic: "opened unfocused" on some runs, ring
            // present on others - same flow). Re-request WHILE nothing is focused anywhere and the
            // user has not pressed a key: it can never fight the user (it only acts on empty focus)
            // and stops the instant focus exists or input arrives. Bounded only by the page's life.
            while (true) {
                if (userDrove) return@LaunchedEffect
                if (!backFocused && !contentHasFocus) runCatching { backFocus.requestFocus() }
                kotlinx.coroutines.delay(50)
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                // The bar SEPARATES from the page via the neutral elevated container role in every
                // theme (a brand-teal bar in light was hated - user feedback; neutral it is).
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .dpadHighlight(androidx.compose.foundation.shape.CircleShape)
                            .focusRequester(backFocus)
                            .onFocusEvent { backFocused = it.isFocused }
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown) userDrove = true
                                if (ev.key == Key.DirectionDown && ev.type == KeyEventType.KeyDown) {
                                    runCatching { topRowFocus.requestFocus() }; true
                                } else false
                            }
                            .dpadSwallowHorizontal(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .onFocusEvent { contentHasFocus = it.hasFocus }
                .dpadSwallowHorizontal()
                // A DOWN past the last control (or stray UP) must never CLEAR focus - the vertical
                // edge trap audit_dynamic caught on the short Saved places page (docs/dpad.md).
                .dpadContainVertical()
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown) userDrove = true
                    if (ev.key == Key.DirectionUp && atTopItem) {
                        if (ev.type == KeyEventType.KeyDown) runCatching { backFocus.requestFocus() }
                        true
                    } else false
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 44dp minimum interactive size for every Material control on Settings pages: the
            // default 48dp box is most of the "weird empty space" around radios, buttons and
            // chips (user report, large-screen phone). 44dp keeps a comfortable touch target;
            // D-pad focus/rings are unaffected (the ring helpers pin to the control's REAL size).
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 44.dp,
            ) {
                content(Modifier.focusRequester(topRowFocus).onFocusEvent { atTopItem = it.isFocused })
            }
        }
    }
}
