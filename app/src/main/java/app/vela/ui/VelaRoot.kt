package app.vela.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
            if (Onboarding.showDonatePrompt.value) {
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
                    onEnable = { vm.setDiagnostics(true); Onboarding.dismissDiagPrompt(context) },
                    onDismiss = { Onboarding.dismissDiagPrompt(context) },
                )
            }
        }
    }
}

/** One-time, opt-in nudge to enable on-device diagnostics (Vela wants the data, but
 *  asks plainly and honours "Not now"). */
@Composable
private fun DiagPrompt(onEnable: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help improve Vela?") },
        text = {
            Text(
                "Vela can keep a short, on-device log of what it does — searches, routes, and " +
                    "errors — so problems are debuggable. It stays on your phone; nothing is sent " +
                    "unless you export it yourself. Turn it off any time in Settings → Diagnostics.",
            )
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
