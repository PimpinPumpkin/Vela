package app.vela.ui

import app.vela.ui.icons.Sym

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.rememberDpadFirstDevice
import app.vela.ui.dpadHighlight

/** First run: what Vela is, then the one choice that decides what leaves the phone. Nothing
 *  behind this screen is composed until both are done, so no request is made before the answer. */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    if (step == 0) {
        WelcomeIntro(onNext = { step = 1 })
    } else {
        val context = LocalContext.current
        androidx.activity.compose.BackHandler { step = 0 }
        GoogleChoice(onContinue = { useGoogle ->
            GoogleFree.set(context, !useGoogle)
            onGetStarted()
        })
    }
}

/** What Vela is and why, then a single Get-started button. */
@Composable
private fun WelcomeIntro(onNext: () -> Unit) {
    // Scrollable so the Get-started button is always reachable — on a small D-pad screen
    // (e.g. 480×640 keypad phone) the fixed layout pushed the button off the bottom with no
    // way to scroll to it, so a D-pad user couldn't SEE it (it was focusable-when-clipped,
    // but invisible — docs/dpad.md). heightIn(min = screen height) keeps the weight spacers
    // centring the content on tall screens; on short ones the column grows and scrolls.
    val scroll = rememberScrollState()
    val minH = LocalConfiguration.current.screenHeightDp.dp
    // D-pad-first (docs/dpad.md): reveal the Get-started button (below the fold on a tiny screen)
    // so it can hold focus + be seen on open. scrollTo(maxValue) is a no-op on a normal phone
    // where it already fits (maxValue 0). No-op under touch.
    val dpadFirst = rememberDpadFirstDevice()
    LaunchedEffect(dpadFirst) {
        if (dpadFirst) repeat(20) {
            if (scroll.maxValue > 0) { scroll.scrollTo(scroll.maxValue); return@LaunchedEffect }
            kotlinx.coroutines.delay(50)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(scroll)
                .heightIn(min = minH)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            VelaMark(84.dp)
            Spacer(Modifier.height(16.dp))
            Text("Vela Maps", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            WelcomeFeature(
                Sym.VisibilityOff,
                stringResource(R.string.welcome_feature_no_tracking_title),
                stringResource(R.string.welcome_feature_private_body),
            )
            WelcomeFeature(
                Sym.Place,
                stringResource(R.string.welcome_feature_places_title),
                stringResource(R.string.welcome_feature_places_body),
            )
            WelcomeFeature(
                Sym.Favorite,
                stringResource(R.string.welcome_feature_open_source_title),
                stringResource(R.string.welcome_feature_open_source_body),
            )
            Spacer(Modifier.height(40.dp))
            WelcomeButton(stringResource(R.string.welcome_get_started), onNext)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Use Google or not, asked once before the map exists. Each option is two short lines: what you
 * get, then what Google sees. Google is preselected. The same switch is Settings > Privacy >
 * "Use Vela without Google", and the full list of what is sent is in PRIVACY.md.
 */
@Composable
private fun GoogleChoice(onContinue: (useGoogle: Boolean) -> Unit) {
    var useGoogle by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val minH = LocalConfiguration.current.screenHeightDp.dp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(scroll)
                .heightIn(min = minH)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            VelaMark(56.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.welcome_google_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.welcome_google_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            ChoiceCard(
                selected = useGoogle,
                title = stringResource(R.string.welcome_google_on_title),
                gives = stringResource(R.string.welcome_google_on_gives),
                sends = stringResource(R.string.welcome_google_on_sends),
                onClick = { useGoogle = true },
            )
            Spacer(Modifier.height(12.dp))
            ChoiceCard(
                selected = !useGoogle,
                title = stringResource(R.string.welcome_google_off_title),
                gives = stringResource(R.string.welcome_google_off_gives),
                sends = stringResource(R.string.welcome_google_off_sends),
                onClick = { useGoogle = false },
            )
            Spacer(Modifier.height(32.dp))
            WelcomeButton(stringResource(R.string.welcome_continue)) { onContinue(useGoogle) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One option: a radio mark, its name, what you get, and what Google sees. The whole card is the
 *  one focus stop, and the radio inside is display only. */
@Composable
private fun ChoiceCard(selected: Boolean, title: String, gives: String, sends: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .dpadHighlight(shape)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                // One paragraph, so the two sentences wrap together. The second is dimmer.
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(gives)
                        append(" ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = dim))
                        append(sends)
                        pop()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** The Vela mark as the launcher draws it: the two-tone sail on the brand gradient. The launcher
 *  foreground keeps its art inside the adaptive icon's safe zone, so it is drawn 1.5 times the
 *  box and clipped. */
@Composable
private fun VelaMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 27))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xFF0D3D43), androidx.compose.ui.graphics.Color(0xFF149387)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

/** The filled primary button of a first-run page, which takes focus on a D-pad device. A Material
 *  `Button` would not take requestFocus here (its nested focusable is not reachable, the same as
 *  dialog buttons), so this is a directly `.focusable()` box with OK via `.onKeyEvent` and touch
 *  via `pointerInput`, styled like the filled Button it replaces. */
@Composable
private fun WelcomeButton(label: String, onGetStarted: () -> Unit) {
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    LaunchedEffect(dpadFirst) {
        if (dpadFirst) repeat(40) {
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRequester(fr)
            .dpadHighlight(RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary)
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onGetStarted(); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onGetStarted() } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The release notes of the build just updated to, shown once (see [WhatsNew]). Same shape as the
 *  donate prompt: an icon, a title, the text, a filled "Got it" and a quiet "Full notes". */
@Composable
fun WhatsNewPrompt(version: String, notes: String, onOpenRelease: () -> Unit, onDismiss: () -> Unit) {
    VelaDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.whatsnew_title, version),
        confirmText = stringResource(R.string.whatsnew_got_it),
        onConfirm = onDismiss,
        dismissText = stringResource(R.string.whatsnew_full_notes),
        onDismiss = onOpenRelease,
        dismissLowEmphasis = true,
        icon = { Icon(Sym.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        text = {
          Column {
            // The list can be long after a week of nightlies; cap it and scroll inside the dialog.
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
            // The same switch as Settings > About, where the prompt is.
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .dpadHighlight()
                    .dpadClickable { WhatsNew.setEnabled(context, !WhatsNew.enabled.value) },
            ) {
                androidx.compose.material3.Checkbox(checked = WhatsNew.enabled.value, onCheckedChange = null)
                Text(
                    stringResource(R.string.whatsnew_show_after_updates),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
          }
        },
    )
}

/** The one-time, low-pressure donation prompt (see [Onboarding] for the etiquette). */
@Composable
fun DonatePrompt(onDonate: () -> Unit, onDismiss: () -> Unit) {
    VelaDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.welcome_donate_title),
        confirmText = stringResource(R.string.welcome_donate_confirm),
        onConfirm = onDonate,
        dismissText = stringResource(R.string.welcome_donate_dismiss),
        onDismiss = onDismiss,
        icon = { Icon(Sym.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        text = { Text(stringResource(R.string.welcome_donate_body)) },
    )
}
