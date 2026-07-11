package app.vela.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vela.R
import app.vela.ui.place.PlaceOverlays
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
    var settingsOpenVoice by rememberSaveable { mutableStateOf(false) }
    // Location permission launcher for onboarding. The map no longer fires the raw system dialog on
    // its own (see MapScreen); this owns the first ask. A grant starts location immediately (coarse-
    // only works too, via the NETWORK provider); a denial just moves on and leaves search/browse
    // working, with the locate FAB re-asking later.
    // A coarse-only grant is easy to pick without meaning to, and it looks broken later (a wide
    // circle for a dot, navigation that can't start). Say what it means ONCE, right when it happens;
    // "Allow precise" re-runs the request, which Android shows as its approximate-to-precise choice.
    var approxPrompt by rememberSaveable { mutableStateOf(false) }
    var approxAsked by rememberSaveable { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) vm.startLocation()
        if (!fine && coarse && !approxAsked) {
            approxAsked = true
            approxPrompt = true
        } else {
            Onboarding.dismissLocationPrompt(context)
        }
    }
    // Ask for location the moment onboarding reaches this step - no separate rationale screen, since
    // the welcome screen is context enough for a maps app (one less thing to tap through). Fires once
    // as the block enters composition; the result arms the notification step next.
    if (Onboarding.showLocationPrompt.value) {
        LaunchedEffect(Unit) {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
    // Notifications come right after location: turn-by-turn keeps your next move in a notification,
    // so asking during setup means the first navigation just works. Graceful on a no - a denial gets
    // one plain-words are-you-sure, and skipping never blocks anything (the nav start re-asks).
    var notifReasked by rememberSaveable { mutableStateOf(false) }
    var notifSure by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || notifReasked) Onboarding.dismissNotifPrompt(context) else notifSure = true
    }
    var notifRequested by rememberSaveable { mutableStateOf(false) }
    if (Onboarding.showNotifPrompt.value && !notifRequested) {
        LaunchedEffect(Unit) {
            notifRequested = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (approxPrompt) {
        VelaDialog(
            onDismissRequest = { approxPrompt = false; Onboarding.dismissLocationPrompt(context) },
            title = stringResource(R.string.loc_approx_title),
            confirmText = stringResource(R.string.loc_approx_allow),
            onConfirm = {
                approxPrompt = false
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            dismissText = stringResource(R.string.loc_approx_keep),
            onDismiss = { approxPrompt = false; Onboarding.dismissLocationPrompt(context) },
            dismissLowEmphasis = true,
            text = { Text(stringResource(R.string.loc_approx_body)) },
        )
    }
    if (notifSure) {
        VelaDialog(
            onDismissRequest = { notifSure = false; Onboarding.dismissNotifPrompt(context) },
            title = stringResource(R.string.notif_ask_title),
            confirmText = stringResource(R.string.notif_ask_allow),
            onConfirm = {
                notifSure = false
                notifReasked = true
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            dismissText = stringResource(R.string.notif_ask_skip),
            onDismiss = { notifSure = false; Onboarding.dismissNotifPrompt(context) },
            dismissLowEmphasis = true,
            text = { Text(stringResource(R.string.notif_ask_body)) },
        )
    }
    // Interface-size override (Settings -> Appearance): scale all Compose UI while the map
    // AndroidView keeps rendering at native size. Density scales dp AND sp together.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val uiScale = app.vela.ui.UiScale.factor.value
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            baseDensity.density * uiScale,
            baseDensity.fontScale,
        ),
    ) {
    Box {
        // MapScreen stays composed even while Settings is open, and Settings draws OVER it as an
        // opaque overlay. Swapping the two out instead disposed the remembered MapLibre MapView, so
        // returning from Settings rebuilt the map from scratch and it snapped back to the stale
        // center at the default zoom, losing the user's pan/zoom (a reported bug).
        MapScreen(
            vm = vm,
            onOpenSettings = { showSettings = true },
            // The no-voice heads-up's pill: straight into the voice library.
            onOpenVoiceSettings = { settingsOpenVoice = true; showSettings = true },
        )
        if (showSettings) {
            SettingsScreen(
                vm = vm,
                onBack = { showSettings = false; settingsOpenOffline = false; settingsOpenVoice = false },
                openOffline = settingsOpenOffline,
                openVoiceLibrary = settingsOpenVoice,
            )
        } else {
            // Location is asked via the system dialog fired above (no rationale screen). The voice
            // offer is the next step once that's answered.
            if (Onboarding.showVoicePrompt.value) {
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
        // Full-screen photo viewer + reviews page render HERE, in the activity's own edge-to-edge
        // window (not a child Dialog) — genuinely full-screen and rotation-safe. Last child = top
        // z-order, above the map + sheet + settings.
        PlaceOverlays()
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
