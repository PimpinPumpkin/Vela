package app.vela.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.SavedPlace
import app.vela.ui.RatingStars
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import app.vela.ui.nav.ManeuverBanner
import app.vela.ui.nav.NavControls
import app.vela.ui.nav.StepsSheet
import app.vela.ui.placeStatusColor
import app.vela.ui.place.PlaceSheet
import app.vela.ui.search.SearchBar
import java.util.Locale

@Composable
fun MapScreen(
    vm: MapViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Back hides the search results (so you can browse the map) before exiting.
    BackHandler(
        enabled = state.results.isNotEmpty() && state.selected == null &&
            !state.resultsCollapsed && !state.navigating,
    ) { vm.collapseResults() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) vm.startLocation()
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.startLocation()
        } else {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.startNav() }
    val onStartNav: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startNav()
        }
    }

    Box(Modifier.fillMaxSize()) {
        VelaMapView(
            styleUri = state.styleUri,
            myLocation = state.myLocation,
            myBearing = state.myBearing,
            cameraTarget = state.center,
            routePolyline = state.activeRoute?.polyline ?: emptyList(),
            markers = markersOf(state),
            frameMarkers = state.results.isNotEmpty() && state.selected == null,
            navMode = state.navigating,
            previewTarget = state.previewStepIndex?.let { state.activeRoute?.maneuvers?.getOrNull(it)?.location },
            onPoiTap = vm::onPoiTap,
            onMarkerTap = { i -> state.results.getOrNull(i)?.let(vm::selectPlace) },
            onCameraIdle = vm::onCameraIdle,
            modifier = Modifier.fillMaxSize(),
        )

        // --- top overlay: nav banner while navigating, else search ----------
        if (state.navigating) {
            ManeuverBanner(
                text = state.maneuverText,
                distanceMeters = state.nav.distanceToNextManeuver,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp),
            )
        } else {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp),
            ) {
                SearchBar(
                    query = state.query,
                    searching = state.searching,
                    onQueryChange = vm::onQueryChange,
                    onSearch = vm::search,
                    onOpenSettings = onOpenSettings,
                    onClear = vm::clearSearch,
                    onFocusChange = { searchFocused = it },
                )
                if (state.results.isNotEmpty() && state.selected == null && !state.resultsCollapsed) {
                    SearchResults(
                        results = state.results,
                        onPick = {
                            focusManager.clearFocus()
                            vm.selectPlace(it)
                        },
                        onCollapse = vm::collapseResults,
                    )
                } else if (state.results.isNotEmpty() && state.selected == null && state.resultsCollapsed) {
                    ElevatedAssistChip(
                        onClick = vm::expandResults,
                        label = { Text("${state.results.size} results") },
                        leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else if (searchFocused && state.query.isBlank() &&
                    (state.saved.isNotEmpty() || state.recents.isNotEmpty())
                ) {
                    SuggestionsPanel(
                        saved = state.saved,
                        recents = state.recents,
                        onPickSaved = {
                            focusManager.clearFocus()
                            vm.selectSaved(it)
                        },
                        onPickRecent = {
                            focusManager.clearFocus()
                            vm.searchRecent(it)
                        },
                        onClearRecents = vm::clearRecents,
                    )
                } else if (!searchFocused && state.selected == null) {
                    CategoryChips(onPick = vm::quickSearch)
                }
            }
        }

        if (state.navigating && state.fasterRoute != null) {
            FasterRouteCard(
                savingSeconds = state.fasterSavingSeconds,
                onSwitch = vm::acceptFasterRoute,
                onDismiss = vm::dismissFasterRoute,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 96.dp, start = 12.dp, end = 12.dp),
            )
        }

        if (!state.navigating && state.showSearchThisArea && state.selected == null) {
            ElevatedButton(
                onClick = vm::searchThisArea,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Search this area")
            }
        }

        // --- bottom overlay: nav controls / place sheet ---------------------
        when {
            state.showSteps -> StepsSheet(
                maneuvers = state.activeRoute?.maneuvers ?: emptyList(),
                etaSeconds = state.activeRoute?.let { it.durationInTrafficSeconds ?: it.durationSeconds } ?: 0.0,
                distanceMeters = state.activeRoute?.distanceMeters ?: 0.0,
                hasLiveTraffic = state.activeRoute?.hasLiveTraffic ?: false,
                previewIndex = state.previewStepIndex,
                currentStep = if (state.navigating) state.nav.stepIndex else null,
                onStep = vm::previewStep,
                onClose = vm::closeSteps,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )

            state.navigating -> NavControls(
                remainingDistanceMeters = state.nav.remainingDistance,
                remainingSeconds = state.nav.remainingDuration,
                offRoute = state.nav.offRoute,
                onStop = vm::stopNav,
                onSteps = vm::openSteps,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )

            state.selected != null -> PlaceSheet(
                place = state.selected!!,
                route = state.activeRoute,
                isSaved = state.saved.any { it.id == state.selected!!.id },
                currentMode = state.travelMode,
                onClose = vm::clearSelection,
                onToggleSave = vm::toggleSave,
                onModeSelected = vm::setTravelMode,
                onDirections = vm::routeToSelected,
                onStartNav = onStartNav,
                onSteps = if (state.activeRoute != null) vm::openSteps else null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        if (!state.navigating && state.selected == null) {
            FloatingActionButton(
                onClick = vm::recenter,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
            }
        }

        // --- transient surfaces --------------------------------------------
        if (state.showPsdsTip) {
            InfoCard(
                title = "Slow GPS lock?",
                body = "Enable PSDS in Settings → Location for a much faster fix " +
                    "(on GrapheneOS it routes through their proxy).",
                actionLabel = "Got it",
                onAction = vm::dismissPsdsTip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
        state.status?.let { msg ->
            InfoCard(
                title = "Heads up",
                body = msg,
                actionLabel = "Dismiss",
                onAction = vm::clearStatus,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 96.dp, start = 12.dp, end = 12.dp),
            )
        }
    }
}

private fun markersOf(state: MapUiState): List<MapMarker> =
    if (state.results.isNotEmpty()) {
        state.results.map { MapMarker(it.name, it.location) }
    } else {
        state.selected?.let { listOf(MapMarker(it.name, it.location)) } ?: emptyList()
    }

@Composable
private fun SearchResults(results: List<Place>, onPick: (Place) -> Unit, onCollapse: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column {
            // Swipe this header up (or press back) to hide the list and browse the map.
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { change, dy -> change.consume(); total += dy },
                            onDragEnd = { if (total < -40f) onCollapse() },
                        )
                    }
                    .clickable { onCollapse() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${results.size} results",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Hide results",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(results) { place ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(place) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(place.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    place.rating?.let { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                String.format(Locale.US, "%.1f", r),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            RatingStars(r, starSize = 12.dp, modifier = Modifier.padding(horizontal = 4.dp))
                            place.reviewCount?.let {
                                Text(
                                    "($it)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val sub = listOfNotNull(
                        place.category,
                        place.distanceMeters?.let { formatDistance(it) },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    place.statusText?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = placeStatusColor(status),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Divider()
            }
        }
        }
    }
}

@Composable
private fun CategoryChips(onPick: (String) -> Unit) {
    val categories = listOf("Restaurants", "Coffee", "Gas", "Groceries", "Hotels", "Pharmacy", "ATMs", "Parks")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { c ->
            ElevatedAssistChip(onClick = { onPick(c) }, label = { Text(c) })
        }
    }
}

@Composable
private fun SuggestionsPanel(
    saved: List<SavedPlace>,
    recents: List<String>,
    onPickSaved: (SavedPlace) -> Unit,
    onPickRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            saved.forEach { sp ->
                SuggestionRow(
                    icon = Icons.Default.Star,
                    tint = MaterialTheme.colorScheme.primary,
                    label = sp.name,
                    onClick = { onPickSaved(sp) },
                )
                Divider()
            }
            recents.forEach { q ->
                SuggestionRow(
                    icon = Icons.Default.History,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = q,
                    onClick = { onPickRecent(q) },
                )
                Divider()
            }
            if (recents.isNotEmpty()) {
                TextButton(onClick = onClearRecents, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Clear recent searches")
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun FasterRouteCard(
    savingSeconds: Double,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Faster route", fontWeight = FontWeight.SemiBold)
                Text(
                    "Saves ~${formatDuration(savingSeconds)} in current traffic",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) { Text("No") }
            Button(onClick = onSwitch) { Text("Switch") }
        }
    }
}
