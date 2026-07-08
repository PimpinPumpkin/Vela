package app.vela.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** First-run welcome — what Vela is and why, then a single Get-started button. */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
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
            Icon(
                Icons.Default.Explore,
                contentDescription = null,
                modifier = Modifier.size(76.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
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
                Icons.Default.VisibilityOff,
                stringResource(R.string.welcome_feature_no_tracking_title),
                stringResource(R.string.welcome_feature_no_tracking_body),
            )
            WelcomeFeature(
                Icons.Default.Place,
                stringResource(R.string.welcome_feature_places_title),
                stringResource(R.string.welcome_feature_places_body),
            )
            WelcomeFeature(
                Icons.Default.Favorite,
                stringResource(R.string.welcome_feature_open_source_title),
                stringResource(R.string.welcome_feature_open_source_body),
            )
            Spacer(Modifier.height(40.dp))
            GetStartedButton(onGetStarted)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Filled primary "Get started" button that AUTO-FOCUSES on a D-pad device. A Material `Button`
 *  wouldn't take requestFocus (its nested focusable isn't reachable — same as dialog buttons), so
 *  this is a directly-`.focusable()` box (the only reliable focus target) with OK via `.onKeyEvent`
 *  and touch via `pointerInput`. Styled to look like the filled Button it replaces. */
@Composable
private fun GetStartedButton(onGetStarted: () -> Unit) {
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
            stringResource(R.string.welcome_get_started),
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
        icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        text = { Text(stringResource(R.string.welcome_donate_body)) },
    )
}
