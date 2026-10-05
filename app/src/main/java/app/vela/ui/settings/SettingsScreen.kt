package app.vela.ui.settings

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.ui.map.MapViewModel
import app.vela.ui.settings.sections.AboutSettingsScreen
import app.vela.ui.settings.sections.AppearanceSettingsScreen
import app.vela.ui.settings.sections.PrivacySettingsScreen
import app.vela.ui.settings.sections.PerformanceSettingsScreen
import app.vela.ui.settings.sections.DiagnosticsSettingsScreen
import app.vela.ui.settings.sections.MapSettingsScreen
import app.vela.ui.settings.sections.NavigationSettingsScreen
import app.vela.ui.settings.sections.OfflineSettingsScreen
import app.vela.ui.settings.sections.PlacesSettingsScreen
import app.vela.ui.settings.sections.SavedPlacesSettingsScreen
import app.vela.ui.settings.sections.SearchSettingsScreen
import app.vela.ui.settings.sections.VoiceSettingsScreen

// A page preview must not steal keypad focus from the page the user is leaving.
internal val LocalSettingsPageActive = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun SettingsScreen(vm: MapViewModel, navController: androidx.navigation.NavHostController) {
    val state by vm.state.collectAsStateWithLifecycle()
    var cameFrom by rememberSaveable { mutableStateOf(SettingsSection.HUB) }
    val currentEntry by navController.currentBackStackEntryAsState()
    androidx.compose.runtime.LaunchedEffect(currentEntry) {
        val route = currentEntry?.destination?.route
        if (route == MAP_ROUTE) cameFrom = SettingsSection.HUB
        else SettingsSection.entries.firstOrNull { it.route == route && it != SettingsSection.HUB }
            ?.let { cameFrom = it }
    }
    val animated = app.vela.ui.PageTransitions.enabled.value
    val direction = if (androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
    val close: () -> Unit = { navController.popBackStack(MAP_ROUTE, false) }
    val toHub: () -> Unit = { navController.popBackStack(SettingsSection.HUB.route, false) }
    androidx.navigation.compose.NavHost(
        navController = navController,
        startDestination = MAP_ROUTE,
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        enterTransition = {
            if (animated) slideInHorizontally(tween(250)) { direction * it } else EnterTransition.None
        },
        exitTransition = {
            if (animated) slideOutHorizontally(tween(250)) { -direction * it / 4 } else ExitTransition.None
        },
        popEnterTransition = {
            if (animated) slideInHorizontally(tween(250)) { -direction * it / 4 } else EnterTransition.None
        },
        popExitTransition = {
            if (animated) slideOutHorizontally(tween(250)) { direction * it } else ExitTransition.None
        },
    ) {
        composable(MAP_ROUTE) { /* The persistent map is underneath this transparent destination. */ }
        SettingsSection.entries.forEach { section ->
            composable(section.route) { entry ->
                val highlight = entry.savedStateHandle.get<String>("highlight")
                val openVoiceLibrary = entry.savedStateHandle.get<Boolean>("openLibrary") == true
                val open: (SettingsSection, String?) -> Unit = { target, label ->
                    cameFrom = target
                    navController.navigate(target.route) {
                        popUpTo(SettingsSection.HUB.route)
                        launchSingleTop = true
                    }
                    navController.currentBackStackEntry?.savedStateHandle?.set("highlight", label)
                }
                val lifecycleState by entry.lifecycle.currentStateAsState()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalSettingsHighlight provides highlight,
                    LocalSettingsPageActive provides (lifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED),
                ) {
                    when (section) {
                        SettingsSection.HUB -> SettingsHub(
                            state = state,
                            returnTo = cameFrom.takeIf { it != SettingsSection.HUB },
                            onOpen = open,
                            onBack = close,
                        )
                        SettingsSection.APPEARANCE -> AppearanceSettingsScreen(vm, onBack = toHub)
                        SettingsSection.MAP -> MapSettingsScreen(onBack = toHub)
                        SettingsSection.PLACES -> PlacesSettingsScreen(onBack = toHub)
                        SettingsSection.NAVIGATION -> NavigationSettingsScreen(vm, onBack = toHub)
                        SettingsSection.VOICE -> VoiceSettingsScreen(vm, onBack = toHub, openLibrary = openVoiceLibrary)
                        SettingsSection.SEARCH -> SearchSettingsScreen(vm, onBack = toHub)
                        SettingsSection.OFFLINE -> OfflineSettingsScreen(vm, onBack = toHub, onCloseSettings = close, onOpenVoice = { open(SettingsSection.VOICE, null) })
                        SettingsSection.SAVED_PLACES -> SavedPlacesSettingsScreen(vm, onBack = toHub, onCloseSettings = close)
                        SettingsSection.PRIVACY -> PrivacySettingsScreen(vm, onBack = toHub)
                        SettingsSection.PERFORMANCE -> PerformanceSettingsScreen(onBack = toHub)
                        SettingsSection.DIAGNOSTICS -> DiagnosticsSettingsScreen(vm, onBack = toHub, onCloseSettings = close)
                        SettingsSection.ABOUT -> AboutSettingsScreen(vm, onBack = toHub)
                    }
                }
            }
        }
    }
}
