package app.vela.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    // Location permission launcher for the onboarding rationale. The map no longer fires the raw
    // system dialog on its own (see MapScreen); this owns the first ask so it comes AFTER a
    // plain-words explanation. A grant starts location immediately; a denial just moves on.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) vm.startLocation()
        Onboarding.dismissLocationPrompt(context)
    }
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
            if (Onboarding.showLocationPrompt.value) {
                // FIRST first-run step: explain location before Android's system dialog. "Allow"
                // launches the real request; "Not now" leaves search/browse working (the locate
                // button re-asks later). Either way we advance to the voice offer.
                PermissionRationale(
                    title = stringResource(R.string.root_location_title),
                    body = stringResource(R.string.root_location_body),
                    allowText = stringResource(R.string.root_location_allow),
                    onAllow = {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    onNotNow = { Onboarding.dismissLocationPrompt(context) },
                )
            } else if (Onboarding.showVoicePrompt.value) {
                VoicePrompt(
                    // The Vela voice is recommended for EVERYONE, but the choice is honest: the
                    // prominent button downloads it, the quiet one keeps whatever voice the phone
                    // already has. Same prompt regardless of what's installed.
                    sizeMb = vm.defaultVoiceSizeMb(),
                    onDownload = {
                        vm.downloadPiper()
                        Onboarding.dismissVoicePrompt(context)
                    },
                    onUseExisting = { Onboarding.dismissVoicePrompt(context) },
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
            }
        }
    }
}

/** One-time, first-run offer of Vela's on-device neural voice. RECOMMENDED for everyone, but an
 *  honest two-way choice: the prominent "Download Vela voice" vs a quiet, low-emphasis "Use existing
 *  voice" that keeps whatever TTS the phone already has (nav still works through it). Not a fake or
 *  disabled option, just visibly de-emphasised so the recommendation is clear. Either choice is
 *  one-time; the voice is changeable any time in Settings → Voice. [sizeMb] is the real download
 *  size, so the number never goes stale. */
@Composable
private fun VoicePrompt(sizeMb: Int, onDownload: () -> Unit, onUseExisting: () -> Unit) {
    VelaDialog(
        onDismissRequest = onUseExisting,
        title = stringResource(R.string.root_voice_title),
        confirmText = stringResource(R.string.root_voice_download),
        onConfirm = onDownload,
        dismissText = stringResource(R.string.root_voice_use_system),
        onDismiss = onUseExisting,
        dismissLowEmphasis = true,
        text = {
            // Two short paragraphs: the pitch, then the alternative + where to change it. The blank
            // line keeps the block from reading as one dense wall on a small screen.
            Text(
                stringResource(R.string.root_voice_body_intro, sizeMb) + "\n\n" +
                    stringResource(R.string.root_voice_body_system) + " " +
                    stringResource(R.string.root_voice_body_outro),
            )
        },
    )
}
