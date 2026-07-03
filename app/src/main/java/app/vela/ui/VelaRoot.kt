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
import androidx.compose.ui.unit.dp
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
    Box {
        if (showSettings) {
            SettingsScreen(vm = vm, onBack = { showSettings = false })
        } else {
            MapScreen(vm = vm, onOpenSettings = { showSettings = true })
            if (Onboarding.showVoicePrompt.value) {
                VoicePrompt(
                    // "other TTS providers found" — since the neural voice isn't installed yet,
                    // voiceEngines() here is exactly the phone's installed system engines.
                    hasSystemVoice = vm.voiceEngines().isNotEmpty(),
                    onDownload = {
                        vm.downloadPiper()
                        Onboarding.dismissVoicePrompt(context)
                    },
                    onSkip = { Onboarding.dismissVoicePrompt(context) },
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
        title = { Text("Help improve Vela?") },
        text = {
            Column {
                Text(
                    "Both stay on your phone — nothing is sent unless you export it. Change either " +
                        "any time in Settings.",
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = diag, onCheckedChange = { diag = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("Share diagnostics", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A short local log of searches, routes & errors.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trips, onCheckedChange = { trips = it })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("Save my trips", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Records your nav GPS traces so drives can be replayed for testing. More " +
                                "revealing — your exact routes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(diag, trips) }) { Text("Save choices") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** One-time, first-run offer of Vela's on-device neural voice — recommended for the most natural
 *  spoken directions. If the phone already has system TTS engines we say so (they can use one
 *  instead); either way the choice is changeable in Settings → Voice. */
@Composable
private fun VoicePrompt(hasSystemVoice: Boolean, onDownload: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Spoken navigation voice") },
        text = {
            Text(
                "Vela has its own high-quality voice that runs entirely on your phone — the most " +
                    "natural spoken directions, with no account or extra app. Recommended. It's a " +
                    "one-time ~126 MB download (wifi recommended). " +
                    (if (hasSystemVoice) "Prefer not to? You can use a text-to-speech voice already " +
                        "installed on your phone instead. " else "") +
                    "You can change this any time in Settings → Voice.",
            )
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("Download Vela voice") } },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(if (hasSystemVoice) "Use system voice" else "Not now") }
        },
    )
}
