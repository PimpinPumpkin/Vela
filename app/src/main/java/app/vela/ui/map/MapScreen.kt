package app.vela.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import app.vela.ui.theme.isAppInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Place
import app.vela.core.model.SavedPlace
import app.vela.ui.RatingStars
import app.vela.ui.SheetPalette
import app.vela.ui.formatDistance
import app.vela.ui.formatSpeed
import app.vela.ui.formatDuration
import app.vela.ui.nav.ArrivalSummary
import app.vela.ui.nav.ManeuverBanner
import app.vela.ui.nav.NavControls
import app.vela.ui.nav.StepsSheet
import app.vela.ui.placeStatusColor
import app.vela.ui.Traffic
import app.vela.ui.place.DirectionsPanel
import app.vela.ui.place.PlaceSheet
import app.vela.ui.search.SearchBar
import java.util.Locale

// Basemap provider. Keyless OpenFreeMap (loaded by URL — the setup that always
// worked) is active; POI markers + colours are applied at runtime. Flip to true
// for MapTiler Streets (needs the MAPTILER_KEY secret). Both paths stay wired.
private const val USE_MAPTILER = false

@Composable
fun MapScreen(
    vm: MapViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val darkTheme = isAppInDarkTheme()
    val hasMapTiler = USE_MAPTILER && BuildConfig.MAPTILER_KEY.isNotBlank()
    // When the place sheet is the active bottom UI it covers ~the bottom 56% of the
    // screen, so push the map's optical centre up by that much to keep the focused
    // pin visible above it.
    val screenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val placeSheetUp = state.selected != null && !state.directionsOpen && !state.navigating
    val cameraBottomInset = if (placeSheetUp) (screenHeightPx * 0.56f).toInt() else 0
    // MapTiler (when a key is built in) gives the Google-like look + its own
    // light/dark styles; otherwise fall back to the keyless OpenFreeMap basemap
    // with our own dark/light recolour.
    val mapStyleUri = if (hasMapTiler) {
        val variant = if (darkTheme) "streets-v2-dark" else "streets-v2"
        "https://api.maptiler.com/maps/$variant/style.json?key=${BuildConfig.MAPTILER_KEY}"
    } else {
        state.styleUri
    }
    val context = LocalContext.current
    var searchFocused by remember { mutableStateOf(false) }
    var downloadTick by remember { mutableStateOf(0) }
    val focusManager = LocalFocusManager.current

    // Back peels one layer at a time — steps → navigation → route preview →
    // place sheet → results list — so it behaves like Google Maps instead of
    // dropping straight out of the app. Only the bare map (or collapsed pins,
    // which a back already peeled down to) lets the system handle back and exit.
    BackHandler(
        enabled = searchFocused || state.showSteps || state.navigating ||
            state.directionsOpen || state.activeRoute != null || state.routes.isNotEmpty() ||
            state.selected != null ||
            (state.results.isNotEmpty() && !state.resultsCollapsed),
    ) {
        when {
            searchFocused -> focusManager.clearFocus()
            state.showSteps -> vm.closeSteps()
            state.navigating -> vm.stopNav()
            state.directionsOpen || state.activeRoute != null || state.routes.isNotEmpty() ||
                state.transit.isNotEmpty() || state.transitLoading -> vm.clearRoute()
            state.selected != null -> vm.clearSelection()
            else -> vm.collapseResults()
        }
    }

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
            styleUri = mapStyleUri,
            myLocation = state.myLocation,
            myBearing = state.myBearing,
            cameraTarget = state.center,
            cameraBottomInsetPx = cameraBottomInset,
            routePolyline = state.activeRoute?.polyline ?: emptyList(),
            routeColor = routeTrafficColor(state.activeRoute),
            markers = markersOf(state),
            frameMarkers = state.results.isNotEmpty() && state.selected == null,
            navMode = state.navigating,
            navFollowing = !state.navCameraDetached,
            onNavPanned = vm::onNavPanned,
            darkTheme = darkTheme,
            applyKeylessTheme = !hasMapTiler,
            trafficOn = Traffic.on.value,
            previewTarget = state.previewStepIndex?.let { state.activeRoute?.maneuvers?.getOrNull(it)?.location },
            onPoiTap = vm::onPoiTap,
            onMarkerTap = { i -> state.results.getOrNull(i)?.let(vm::selectPlace) },
            onCameraIdle = vm::onCameraIdle,
            onMapLongPress = vm::onMapLongPress,
            downloadTick = downloadTick,
            onDownloadStatus = vm::showStatus,
            onDownloadArea = vm::downloadOfflinePois,
            modifier = Modifier.fillMaxSize(),
        )

        // --- top overlay: nav banner while navigating, else search ----------
        if (state.navigating) {
            val mans = state.activeRoute?.maneuvers
            val liveStep = state.nav.stepIndex
            val previewing = state.previewStepIndex != null
            // Show the previewed step when swiping ahead, else the live maneuver.
            val shownIdx = (state.previewStepIndex ?: liveStep).coerceIn(0, mans?.lastIndex ?: 0)
            val shown = mans?.getOrNull(shownIdx)
            val next = mans?.getOrNull(shownIdx + 1)
            ManeuverBanner(
                text = if (previewing) (shown?.instruction.orEmpty()) else state.maneuverText,
                distanceMeters = if (previewing) (shown?.distanceMeters ?: 0.0) else state.nav.distanceToNextManeuver,
                type = shown?.type ?: ManeuverType.STRAIGHT,
                laneHint = shown?.laneHint,
                nextText = next?.instruction,
                nextType = next?.type,
                previewing = previewing,
                onPreviewNext = { vm.previewStep((shownIdx + 1).coerceAtMost(mans?.lastIndex ?: liveStep)) },
                onPreviewPrev = { if (shownIdx - 1 <= liveStep) vm.clearPreview() else vm.previewStep(shownIdx - 1) },
                onExitPreview = vm::clearPreview,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp),
            )
        } else {
            // While the search box is focused the whole thing becomes a full-screen
            // page (recent searches over an opaque background, like Google Maps);
            // otherwise it's the floating bar over the map. Running a search clears
            // focus, which drops back to the map + results-list + red pins.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        if (searchFocused) {
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    ),
            ) {
                Column(Modifier.statusBarsPadding().padding(12.dp)) {
                    SearchBar(
                        query = state.query,
                        searching = state.searching,
                        onQueryChange = vm::onQueryChange,
                        onSearch = {
                            focusManager.clearFocus()
                            vm.search()
                        },
                        onOpenSettings = onOpenSettings,
                        onClear = vm::clearSearch,
                        onFocusChange = { searchFocused = it },
                        onBack = if (searchFocused) ({ focusManager.clearFocus() }) else null,
                    )
                    when {
                        searchFocused -> SearchEntryContent(
                            suggestions = state.suggestions,
                            saved = state.saved,
                            recents = state.recents,
                            onPickSuggestion = {
                                focusManager.clearFocus()
                                vm.selectPlace(it)
                            },
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

                        state.results.isNotEmpty() && state.selected == null && !state.resultsCollapsed ->
                            SearchResults(
                                results = state.results,
                                onPick = {
                                    focusManager.clearFocus()
                                    vm.selectPlace(it)
                                },
                                onCollapse = vm::collapseResults,
                            )

                        state.results.isNotEmpty() && state.selected == null && state.resultsCollapsed ->
                            ElevatedAssistChip(
                                onClick = vm::expandResults,
                                label = { Text("${state.results.size} results") },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                                modifier = Modifier.padding(top = 8.dp),
                            )

                        state.selected == null -> CategoryChips(onPick = vm::quickSearch)
                    }
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

        // After panning away during nav, a Re-center button reattaches the
        // follow-camera (Google-style); it's hidden while the camera is following.
        if (state.navigating && state.navCameraDetached) {
            ExtendedFloatingActionButton(
                onClick = vm::recenterNav,
                icon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                text = { Text("Re-center") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 104.dp),
            )
        }

        // Speedometer (Google-style) — current GPS speed, bottom-left during nav.
        val speedMps = state.mySpeed
        if (state.navigating && speedMps != null) {
            val (value, unit) = formatSpeed(speedMps)
            val dark = isAppInDarkTheme()
            Surface(
                shape = CircleShape,
                color = SheetPalette.bg(dark),
                contentColor = SheetPalette.ink(dark),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 104.dp)
                    .size(60.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(unit, style = MaterialTheme.typography.labelSmall, color = SheetPalette.dim(dark))
                }
            }
        }

        if (!state.navigating && state.showSearchThisArea && state.selected == null && !searchFocused) {
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

        // --- bottom overlay: arrival summary / nav controls / place sheet ---
        when {
            state.arrived -> ArrivalSummary(
                destinationLabel = state.arrivedLabel,
                tripSeconds = state.arrivedSeconds,
                tripDistanceMeters = state.arrivedDistanceMeters,
                onDone = vm::finishNav,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )

            state.showSteps -> StepsSheet(
                maneuvers = state.activeRoute?.maneuvers ?: emptyList(),
                etaSeconds = state.activeRoute?.let { it.durationInTrafficSeconds ?: it.durationSeconds } ?: 0.0,
                distanceMeters = state.activeRoute?.distanceMeters ?: 0.0,
                hasLiveTraffic = state.activeRoute?.hasLiveTraffic ?: false,
                previewIndex = state.previewStepIndex,
                currentStep = if (state.navigating) state.nav.stepIndex else null,
                onStep = vm::previewStep,
                onClose = vm::closeSteps,
                // Background fills to the bottom; StepsSheet pads its own content.
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            state.navigating -> NavControls(
                remainingDistanceMeters = state.nav.remainingDistance,
                remainingSeconds = state.nav.remainingDuration,
                offRoute = state.nav.offRoute,
                onStop = vm::stopNav,
                onSteps = vm::openSteps,
                voiceMuted = state.voiceMuted,
                onToggleVoice = vm::toggleVoice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )

            // Tapping "Directions" opens a dedicated panel (popup) — mode tabs, the
            // route option(s) with traffic-aware ETAs, selectable alternates, Start —
            // instead of burying it at the bottom of the place sheet.
            state.directionsOpen -> DirectionsPanel(
                destinationName = state.selected?.name ?: "Destination",
                currentMode = state.travelMode,
                routes = state.routes,
                activeRoute = state.activeRoute,
                transit = state.transit,
                transitLoading = state.transitLoading,
                onModeSelected = vm::setTravelMode,
                onSelectRoute = vm::selectRoute,
                onStartNav = onStartNav,
                onSteps = if (state.activeRoute != null) vm::openSteps else null,
                onSearchAlongRoute = vm::searchAlongRoute,
                onClose = vm::clearRoute,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            state.selected != null -> PlaceSheet(
                place = state.selected!!,
                isSaved = state.saved.any { it.id == state.selected!!.id },
                reviews = state.reviews,
                reviewsLoading = state.reviewsLoading,
                placesHere = state.placesHere,
                onClose = vm::clearSelection,
                onToggleSave = vm::toggleSave,
                onDirections = vm::routeToSelected,
                onOpenPlace = vm::selectPlace,
                // No navigationBarsPadding here: the sheet's background should reach
                // the screen bottom (no map peeking through under the nav bar); the
                // sheet pads its own content for the nav bar instead.
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (!state.navigating && state.selected == null && !searchFocused) {
            FloatingActionButton(
                onClick = vm::recenter,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
            }
            // Download the visible area for offline use (renders later with no network).
            SmallFloatingActionButton(
                onClick = { downloadTick++ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 84.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download this area for offline use")
            }
            // Toggle Google's live-traffic overlay; highlighted when on.
            val trafficCtx = LocalContext.current
            SmallFloatingActionButton(
                onClick = { Traffic.set(trafficCtx, !Traffic.on.value) },
                containerColor = if (Traffic.on.value) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 144.dp),
            ) {
                Icon(Icons.Default.Traffic, contentDescription = "Toggle live traffic")
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

/** Route line colour by congestion: blue when free-flowing, amber/red when the
 *  live traffic-aware time runs meaningfully over the typical time. Walk/bike and
 *  traffic-less routes stay the default blue. */
private fun routeTrafficColor(route: app.vela.core.model.Route?): String =
    when (val ratio = route?.trafficRatio) {
        null -> "#1F6FEB"
        else -> when {
            ratio > 1.4 -> "#D93838"  // heavy
            ratio > 1.15 -> "#E8923D" // moderate
            else -> "#1F6FEB"          // light / free-flowing
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
    val expandedState = remember { mutableStateOf(false) }
    var openOnly by remember { mutableStateOf(false) }
    var topRated by remember { mutableStateOf(false) }
    val screenH = LocalConfiguration.current.screenHeightDp
    // Opens as a tall list (≈half screen) and expands to nearly full-screen, like
    // Google's results page; drag the handle / tap the chevron.
    val maxH by animateDpAsState(
        if (expandedState.value) (screenH * 0.94f).dp else (screenH * 0.52f).dp,
        label = "resultsHeight",
    )
    // The panel hangs from the top (under the search bar), so it follows a
    // top-sheet model: pull DOWN to grow, push UP to retract. A nested-scroll
    // handler lets a down-overscroll at the top of the list expand the panel
    // ("pull to see more"); hiding is the upward gesture on the handle below.
    val listState = rememberLazyListState()
    val onCollapseUpdated = rememberUpdatedState(onCollapse)
    val dismissConn = remember {
        object : NestedScrollConnection {
            private var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop && !expandedState.value) {
                    acc += available.y
                    if (acc > 120f) { expandedState.value = true; acc = 0f }
                    return available
                }
                if (available.y <= 0f) acc = 0f
                return Offset.Zero
            }
        }
    }
    // Google-style filters: currently open, and 4.0★+.
    val shown = results
        .let { list -> if (openOnly) list.filter { it.openNow == true } else list }
        .let { list -> if (topRated) list.filter { (it.rating ?: 0.0) >= 4.0 } else list }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column {
            // Top-sheet handle: swipe DOWN to expand the list, swipe UP to retract
            // it — first shrinking an expanded list, then hiding it back to the
            // "N results" pill (the panel lives at the top, so up = away).
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { change, dy -> change.consume(); total += dy },
                            onDragEnd = {
                                when {
                                    total > 40f -> expandedState.value = true
                                    total < -40f && expandedState.value -> expandedState.value = false
                                    total < -40f -> onCollapse()
                                }
                            },
                        )
                    },
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${shown.size} result${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = openOnly,
                        onClick = { openOnly = !openOnly },
                        label = { Text("Open now") },
                        leadingIcon = if (openOnly) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                    FilterChip(
                        selected = topRated,
                        onClick = { topRated = !topRated },
                        label = { Text("4.0★") },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    IconButton(onClick = { expandedState.value = !expandedState.value }) {
                        Icon(
                            if (expandedState.value) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expandedState.value) "Shrink list" else "Expand list",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Divider()
            LazyColumn(Modifier.nestedScroll(dismissConn).heightIn(max = maxH), state = listState) {
                items(shown) { place ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(place) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    // Bigger, more legible rows (the address/category line read too
                    // small before): name at titleMedium, the secondary lines bumped
                    // from bodySmall→bodyMedium with a touch more breathing room.
                    Text(place.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    place.rating?.let { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 3.dp),
                        ) {
                            Text(
                                String.format(Locale.US, "%.1f", r),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            RatingStars(r, starSize = 14.dp, modifier = Modifier.padding(horizontal = 4.dp))
                            place.reviewCount?.let {
                                Text(
                                    "($it)",
                                    style = MaterialTheme.typography.bodyMedium,
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
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    // Full address (city/state/zip) to disambiguate similar names
                    // and identical-looking residential addresses.
                    place.address?.let { addr ->
                        Text(
                            addr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    place.statusText?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = placeStatusColor(status),
                            modifier = Modifier.padding(top = 3.dp),
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
    val categories = listOf(
        "Restaurants" to Icons.Default.Restaurant,
        "Coffee" to Icons.Default.LocalCafe,
        "Gas" to Icons.Default.LocalGasStation,
        "Groceries" to Icons.Default.LocalGroceryStore,
        "Hotels" to Icons.Default.Hotel,
        "Pharmacy" to Icons.Default.LocalPharmacy,
        "ATMs" to Icons.Default.LocalAtm,
        "Parks" to Icons.Default.Park,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { (label, icon) ->
            ElevatedAssistChip(
                onClick = { onPick(label) },
                label = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** Full-screen search page body: saved places + recent searches, shown over an
 *  opaque background while the search box is focused (Google-style). */
@Composable
private fun SearchEntryContent(
    suggestions: List<Place>,
    saved: List<SavedPlace>,
    recents: List<String>,
    onPickSuggestion: (Place) -> Unit,
    onPickSaved: (SavedPlace) -> Unit,
    onPickRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
) {
    // While typing, live place suggestions take over the page (Google-style);
    // with an empty box it's the saved + recents shortlist.
    if (suggestions.isNotEmpty()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
        ) {
            suggestions.forEach { p ->
                SuggestionRow(
                    icon = Icons.Default.Search,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = p.name,
                    sublabel = p.address ?: p.category,
                    onClick = { onPickSuggestion(p) },
                )
                Divider()
            }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp),
    ) {
        if (saved.isNotEmpty()) {
            SectionLabel("Saved")
            saved.forEach { sp ->
                SuggestionRow(
                    icon = Icons.Default.Star,
                    tint = MaterialTheme.colorScheme.primary,
                    label = sp.name,
                    onClick = { onPickSaved(sp) },
                )
                Divider()
            }
        }
        if (recents.isNotEmpty()) {
            SectionLabel("Recent")
            recents.forEach { q ->
                SuggestionRow(
                    icon = Icons.Default.History,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = q,
                    onClick = { onPickRecent(q) },
                )
                Divider()
            }
            TextButton(onClick = onClearRecents, modifier = Modifier.padding(start = 8.dp)) {
                Text("Clear recent searches")
            }
        }
        if (saved.isEmpty() && recents.isEmpty()) {
            Text(
                "Search for places, addresses and categories.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
    sublabel: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp), tint = tint)
        if (sublabel == null) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
