package app.vela.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import androidx.hilt.navigation.compose.hiltViewModel
import app.vela.ui.map.MapScreen
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.SettingsScreen

/**
 * Root composable. One [MapViewModel] instance is shared between the map and
 * settings (settings tweaks the same map/voice state), so we drive a single
 * boolean rather than a NavHost with cross-graph VM scoping. The first-run
 * [WelcomeScreen] gates everything else; the one-time [DonatePrompt] overlays the
 * map once the app has earned it (see [Onboarding]).
 */
@Composable
fun VelaRoot(vm: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current

    if (!Onboarding.welcomeDone.value) {
        WelcomeScreen(onGetStarted = { Onboarding.completeWelcome(context) })
        return
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsOpenOffline by rememberSaveable { mutableStateOf(false) }
    Box {
        // MapScreen stays composed even while Settings is open, and Settings draws OVER it as an
        // opaque overlay. Swapping the two out instead disposed the remembered MapLibre MapView, so
        // returning from Settings rebuilt the map from scratch and it snapped back to the stale
        // center at the default zoom, losing the user's pan/zoom (a reported bug).
        MapScreen(vm = vm, onOpenSettings = { showSettings = true })
        if (showSettings) {
            SettingsScreen(
                vm = vm,
                onBack = { showSettings = false; settingsOpenOffline = false },
                openOffline = settingsOpenOffline,
            )
        } else {
            if (Onboarding.showVoicePrompt.value) {
                VoicePrompt(
                    // The Vela voice is recommended for EVERYONE — the same prompt regardless of
                    // whether the phone has a system TTS engine, so every install ends up on the
                    // same consistent voice unless they deliberately change it in Settings.
                    sizeMb = vm.defaultVoiceSizeMb(),
                    onDownload = {
                        vm.downloadPiper()
                        Onboarding.dismissVoicePrompt(context)
                    },
                    onSkip = { Onboarding.dismissVoicePrompt(context) },
                )
            } else if (Onboarding.showOfflinePrompt.value) {
                OfflinePrompt(
                    onSetup = {
                        Onboarding.dismissOfflinePrompt(context)
                        settingsOpenOffline = true
                        showSettings = true
                    },
                    onSkip = { Onboarding.dismissOfflinePrompt(context) },
                )
            } else if (Onboarding.showDonatePrompt.value) {
                DonatePrompt(
                    onDonate = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Onboarding.DONATE_URL)))
                        }
                        Onboarding.dismissDonatePrompt(context)
                    },
                    onDismiss = { Onboarding.dismissDonatePrompt(context) },
                )
            } else if (Onboarding.showDiagPrompt.value) {
                DiagPrompt(
                    onChoose = { diag, trips ->
                        if (diag) vm.setDiagnostics(true)
                        if (trips) vm.setTripRecording(true)
                        Onboarding.dismissDiagPrompt(context)
                    },
                    onDismiss = { Onboarding.dismissDiagPrompt(context) },
                )
            }
        }
    }
}

/** One-time, opt-in nudge with TWO separate choices — basic diagnostics (default on)
 *  and the more-invasive trip recording (default off, since it captures your exact
 *  routes). Both stay on-device; "Not now" enables neither. */
@Composable
private fun DiagPrompt(onChoose: (diagnostics: Boolean, trips: Boolean) -> Unit, onDismiss: () -> Unit) {
    var diag by remember { mutableStateOf(true) }
    var trips by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.root_diag_title)) },
        text = {
            Column {
                Text(stringResource(R.string.root_diag_body))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = diag, onCheckedChange = { diag = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.root_diag_share_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.root_diag_share_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trips, onCheckedChange = { trips = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.root_diag_trips_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.root_diag_trips_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(diag, trips) }) { Text(stringResource(R.string.root_diag_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.root_not_now)) } },
    )
}

/** One-time, first-run offer of Vela's on-device neural voice — RECOMMENDED for everyone, the same
 *  prompt whether or not the phone has a system TTS engine (consistency: every install lands on the
 *  same voice unless the user deliberately changes it). Skipping still leaves nav working via the
 *  system voice if one exists; a different voice — including a system engine — is one tap away in
 *  Settings → Voice. [sizeMb] is the actual download size of the voice that will be fetched, so the
 *  number can never go stale. */
@Composable
private fun VoicePrompt(sizeMb: Int, onDownload: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.root_voice_title)) },
        text = {
            Text(
                stringResource(R.string.root_voice_body_intro, sizeMb) + " " +
                    stringResource(R.string.root_voice_body_outro),
            )
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.root_voice_download)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.root_not_now)) } },
    )
}

/** One-time, first-run offer to set up offline maps. Vela's live data comes from Google, so without a
 *  connection only downloaded areas work. Surfacing this during onboarding means people find it before
 *  they lose signal on the road, not after. "Set up" opens Settings straight to the Offline section. */
@Composable
private fun OfflinePrompt(onSetup: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.root_offline_title)) },
        text = { Text(stringResource(R.string.root_offline_body)) },
        confirmButton = { TextButton(onClick = onSetup) { Text(stringResource(R.string.root_offline_setup)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.root_not_now)) } },
    )
}
