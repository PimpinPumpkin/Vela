package app.vela.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.sections.AboutSettingsScreen
import app.vela.ui.settings.sections.AppearanceSettingsScreen
import app.vela.ui.settings.sections.DataPrivacySettingsScreen
import app.vela.ui.settings.sections.DiagnosticsSettingsScreen
import app.vela.ui.settings.sections.MapSettingsScreen
import app.vela.ui.settings.sections.NavigationSettingsScreen
import app.vela.ui.settings.sections.OfflineSettingsScreen
import app.vela.ui.settings.sections.PlacePagesSettingsScreen
import app.vela.ui.settings.sections.SavedPlacesSettingsScreen
import app.vela.ui.settings.sections.SearchSettingsScreen
import app.vela.ui.settings.sections.VoiceSettingsScreen

/** The Settings pages. HUB is the category list; everything else is one sub-screen (spoke). */
internal enum class SettingsSection {
    HUB, APPEARANCE, MAP, PLACE_PAGES, NAVIGATION, VOICE, SEARCH, OFFLINE, SAVED_PLACES,
    DATA_PRIVACY, DIAGNOSTICS, ABOUT,
}

/**
 * Settings is hub-and-spoke: a short category list (the hub) opening one small page per category,
 * instead of the old single extremely long scroll - a keypad user reaches any section in a few DOWN
 * presses, and every page stays shallow at a 240x320 feature-phone geometry. Plain hoisted state
 * (no nav library, matching the rest of the app); BACK peels spoke -> hub -> map.
 *
 * [openOffline]: the onboarding "set up offline" prompt deep-links straight into the Offline page
 * (this replaces the old measure-and-scroll-to-section dance on the single page).
 */
@Composable
fun SettingsScreen(vm: MapViewModel, onBack: () -> Unit, openOffline: Boolean = false, openVoiceLibrary: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val startSection = when {
        openOffline -> SettingsSection.OFFLINE
        openVoiceLibrary -> SettingsSection.VOICE
        else -> SettingsSection.HUB
    }
    var section by rememberSaveable { mutableStateOf(startSection) }
    // The spoke last left, so the hub restores focus to the row you came back from (Compose's own
    // recovery is nondeterministic when a focused tree unmounts - docs/dpad.md).
    var cameFrom by rememberSaveable { mutableStateOf(startSection) }
    val toHub = {
        cameFrom = section
        section = SettingsSection.HUB
    }
    // System back mirrors the top-bar Back button: spoke -> hub, hub -> map.
    BackHandler { if (section == SettingsSection.HUB) onBack() else toHub() }
    when (section) {
        SettingsSection.HUB -> SettingsHub(
            state = state,
            returnTo = cameFrom.takeIf { it != SettingsSection.HUB },
            onOpen = { section = it },
            onBack = onBack,
        )
        SettingsSection.APPEARANCE -> AppearanceSettingsScreen(vm, onBack = toHub)
        SettingsSection.MAP -> MapSettingsScreen(onBack = toHub)
        SettingsSection.PLACE_PAGES -> PlacePagesSettingsScreen(onBack = toHub)
        SettingsSection.NAVIGATION -> NavigationSettingsScreen(vm, onBack = toHub)
        SettingsSection.VOICE -> VoiceSettingsScreen(vm, onBack = toHub, openLibrary = openVoiceLibrary)
        SettingsSection.SEARCH -> SearchSettingsScreen(vm, onBack = toHub)
        SettingsSection.OFFLINE -> OfflineSettingsScreen(vm, onBack = toHub, onCloseSettings = onBack)
        SettingsSection.SAVED_PLACES -> SavedPlacesSettingsScreen(vm, onBack = toHub)
        SettingsSection.DATA_PRIVACY -> DataPrivacySettingsScreen(vm, onBack = toHub)
        SettingsSection.DIAGNOSTICS -> DiagnosticsSettingsScreen(vm, onBack = toHub, onCloseSettings = onBack)
        SettingsSection.ABOUT -> AboutSettingsScreen(vm, onBack = toHub)
    }
}
