package app.vela.ui.map

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import app.vela.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.core.config.Notice
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Place
import app.vela.core.model.SavedPlace
import app.vela.core.model.ShortcutKind
import app.vela.ui.RatingStars
import app.vela.ui.SheetPalette
import app.vela.ui.formatDistance
import app.vela.ui.formatSpeed
import app.vela.ui.formatSpeedLimit
import app.vela.ui.formatDuration
import app.vela.ui.Units
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
    // Push the optical centre up so the place sheet / directions panel doesn't sit on
    // top of the pin or the route (the directions panel is tall — fit the route above it).
    val cameraBottomInset = when {
        placeSheetUp -> (screenHeightPx * 0.56f).toInt()
        state.directionsOpen && !state.navigating -> (screenHeightPx * 0.58f).toInt()
        else -> 0
    }
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

    // Keep the display awake during turn-by-turn so a driver glancing at the next
    // turn never has to tap to wake it. Gated by the "Keep screen on while
    // navigating" toggle (Settings → Navigation, default on); the flag is cleared
    // the instant nav ends, the setting is turned off, or this screen leaves
    // composition, so the screen sleeps normally again everywhere else.
    val keepAwakeOn = remember(state.navigating) {
        state.navigating &&
            context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("keep_screen_on_nav", true)
    }
    val activityWindow = remember(context) {
        var c: android.content.Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window
    }
    DisposableEffect(keepAwakeOn, activityWindow) {
        if (keepAwakeOn) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var searchFocused by remember { mutableStateOf(false) }
    // The search overlay is open when the field is focused OR we're picking a custom
    // directions origin (which opens the same overlay WITHOUT focusing the field — so
    // we can't rely on clearFocus() to close it; pick-mode is reset explicitly instead).
    val searchOpen = searchFocused || state.pickingOrigin || state.pickingStop
    var metersPerPixel by remember { mutableStateOf(0.0) }
    val focusManager = LocalFocusManager.current

    // Back peels one layer at a time — steps → navigation → route preview →
    // place sheet → results list — so it behaves like Google Maps instead of
    // dropping straight out of the app. Only the bare map (or collapsed pins,
    // which a back already peeled down to) lets the system handle back and exit.
    BackHandler(
        enabled = searchOpen || state.showSteps || state.navigating ||
            state.directionsOpen || state.activeRoute != null || state.routes.isNotEmpty() ||
            state.selected != null ||
            (state.results.isNotEmpty() && !state.resultsCollapsed),
    ) {
        when {
            searchOpen -> { focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickStop() }
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
            mySpeed = state.mySpeed,
            mySpeedRaw = state.mySpeedRaw,
            replaySpeedup = if (state.replaying) MapViewModel.REPLAY_SPEEDUP else 1f,
            compassHeading = state.compassHeading,
            locationStale = state.myLocationStale,
            cameraTarget = state.center,
            recenterTick = state.recenterTick,
            cameraBottomInsetPx = cameraBottomInset,
            routePolyline = state.activeRoute?.polyline ?: emptyList(),
            routeColor = routeTrafficColor(state.activeRoute),
            routeDashed = state.travelMode == app.vela.core.model.TravelMode.WALK ||
                state.travelMode == app.vela.core.model.TravelMode.BICYCLE,
            routeTrafficSpans = routeTrafficSpans(state.activeRoute),
            // Greyed, tappable alternates (Google-style) — only off-nav, with a chooser up.
            alternates = if (state.navigating) emptyList() else run {
                val activeIdx = state.routes.indexOf(state.activeRoute)
                state.routes.mapIndexedNotNull { i, r ->
                    if (i != activeIdx && r.polyline.size >= 2) i to r.polyline else null
                }
            },
            altColor = if (darkTheme) "#C8CDD4" else "#9AA0A6",
            onSelectAlternate = vm::selectRoute,
            markers = markersOf(state),
            frameMarkers = state.results.isNotEmpty() && state.selected == null,
            navMode = state.navigating,
            navFollowing = !state.navCameraDetached,
            onNavPanned = vm::onNavPanned,
            onScaleChanged = { metersPerPixel = it },
            darkTheme = darkTheme,
            applyKeylessTheme = !hasMapTiler,
            // Off-nav: the whole-map raster when the user toggles it on. During nav we
            // DON'T wash the whole map — the user asked for traffic on "just the road
            // we're on, not all of it", so the route line itself is coloured per-segment
            // from the directions traffic spans (VelaMapView.routeGradientStops /
            // DirectionsParser.parseTrafficSpans); the whole-map overlay stays off unless
            // the user explicitly enables it in Settings → Map.
            trafficOn = Traffic.on.value,
            previewTarget = state.previewStepIndex?.let { state.activeRoute?.maneuvers?.getOrNull(it)?.location },
            onPoiTap = vm::onPoiTap,
            onMarkerTap = { i -> displayedPlaces(state).getOrNull(i)?.let(vm::selectPlace) },
            ambientPois = ambientMarkersOf(state),
            onAmbientTap = { i -> state.ambientPois.getOrNull(i)?.let(vm::selectPlace) },
            onCameraIdle = vm::onCameraIdle,
            onMapLongPress = vm::onMapLongPress,
            onViewport = vm::onViewport,
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
                // The headline distance is the APPROACH to the shown maneuver. A maneuver's own
                // distanceMeters is the travel AFTER it (Route.kt convention) — showing it here
                // put the leg-after on the previewed step's headline ("3.1 mi — Turn right onto
                // Elm St" for a turn 500 ft after the previous one). The approach leg is the
                // PREVIOUS maneuver's after-distance.
                distanceMeters = if (previewing) {
                    mans?.getOrNull(shownIdx - 1)?.distanceMeters ?: state.nav.distanceToNextManeuver
                } else {
                    state.nav.distanceToNextManeuver
                },
                type = shown?.type ?: ManeuverType.STRAIGHT,
                ref = shown?.ref,
                laneHint = shown?.laneHint,
                lanes = shown?.lanes.orEmpty(),
                nextText = next?.instruction,
                nextType = next?.type,
                nextRef = next?.ref,
                // The shown→next gap is the SHOWN maneuver's step length (a maneuver's distanceMeters is
                // the travel AFTER it, to the next maneuver — both OSRM and the Google parser use that
                // convention). Passing next.distanceMeters was the next→next-next gap: it made "then
                // Arrive" (ARRIVE has 0 after it) show permanently while approaching the final turn, and
                // suppressed true exit-then-merge compounds whose merge had a long following leg.
                nextDistanceMeters = shown?.distanceMeters,
                // Speed-scaled approach gate for lanes + the "then" row: identity at city speeds
                // (≤ ~60 mph), ~1 km ≈ 30 s at highway speed — Google's cadence.
                laneShowM = maxOf(800.0, (state.mySpeed ?: 0f).toDouble() * 30.0),
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
                        if (searchOpen) {
                            // Same fixed sheet grey as the place sheet / results rows,
                            // not the wallpaper-tinted Material surface (which read as a
                            // slightly different shade).
                            Modifier.fillMaxSize().background(SheetPalette.bg(darkTheme))
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
                        onBack = if (searchOpen) ({ focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickStop() }) else null,
                    )
                    when {
                        // While picking an origin/stop the overlay stays latched open (pickingOrigin/
                        // pickingStop, not field focus) — but a typed IME submit or a recent-search tap
                        // fills state.results with the field UNfocused. Fall through to the results list
                        // then (its taps flow into selectPlace, whose pick-mode guards consume them),
                        // else the results would be invisible behind the entry page: a dead-end.
                        searchOpen && (searchFocused || state.results.isEmpty()) -> SearchEntryContent(
                            suggestions = state.suggestions,
                            saved = state.saved,
                            recents = state.recents,
                            recentPlaces = state.recentPlaces,
                            home = state.home,
                            work = state.work,
                            assigning = state.assigningShortcut,
                            pickingOrigin = state.pickingOrigin,
                            pickingStop = state.pickingStop,
                            onCancelPickStop = vm::cancelPickStop,
                            onUseMyLocation = vm::useMyLocationAsOrigin,
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
                            onPickRecentPlace = {
                                focusManager.clearFocus()
                                vm.selectSaved(it)
                            },
                            onClearRecents = vm::clearRecents,
                            onPickShortcut = {
                                focusManager.clearFocus()
                                vm.openShortcut(it)
                            },
                            onAssignShortcut = vm::beginAssignShortcut,
                            onClearShortcut = vm::clearShortcut,
                            onCancelAssign = vm::cancelAssign,
                            onPinSavedAs = vm::pinSavedAs,
                            onRemoveSaved = vm::removeSaved,
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
                                label = { Text(stringResource(R.string.mapscreen_results_count, state.results.size)) },
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

        // After panning away during nav — or swiping the banner ahead to preview a
        // later step — a Re-center button reattaches the follow-camera AND snaps the
        // banner back to the current step (Google-style); hidden while following live.
        if (state.navigating && (state.navCameraDetached || state.previewStepIndex != null)) {
            // Icon-only, tucked to the right and lifted clear of the bottom bar.
            FloatingActionButton(
                onClick = vm::recenterNav,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 132.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.mapscreen_recenter)) }
        }

        // "Searching for GPS" chip — the banner distance/ETA freeze silently on signal loss
        // (tunnel, garage, Location toggled off); a confident-looking frozen arrow with no hint
        // it's stale was the audit's "GPS loss is completely invisible" finding. The dot/puck
        // already greys via the same flag.
        if (state.navigating && (state.myLocationStale || state.navStarved)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shadowElevation = 4.dp,
                // bottom = 200dp clears the speedo/FAB band (132-192dp); width-capped +
                // single-line so long translations can't collide with either.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 200.dp, start = 90.dp, end = 90.dp),
            ) {
                Text(
                    stringResource(R.string.nav_gps_lost),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Speedometer (Google-style) — bottom-left during nav. The DISPLAYED value is smoothed
        // (Google shows the fused estimate, not each raw doppler sample — the raw 1 Hz readout
        // flickered 59/60/61 at a steady cruise), with a small deadband so a stop reads a
        // clean 0 instead of 1 mph jitter.
        val speedMps = state.mySpeed
        if (state.navigating && speedMps != null) {
            val shownSpeed by animateFloatAsState(
                targetValue = if (speedMps < 0.4f) 0f else speedMps,
                animationSpec = tween(durationMillis = 600),
                label = "speedo",
            )
            val (value, unit) = formatSpeed(shownSpeed)
            val dark = isAppInDarkTheme()
            Surface(
                shape = CircleShape,
                color = SheetPalette.bg(dark),
                contentColor = SheetPalette.ink(dark),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 132.dp)
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

        // Posted speed-limit sign — sits just above the speedometer during nav, when the on-device
        // graph knows the current road's OSM maxspeed (hidden otherwise; sparse OSM coverage = often blank).
        if (state.navigating && state.speedLimitKmh != null) {
            SpeedLimitSign(
                limitKmh = state.speedLimitKmh!!,
                speedMps = state.mySpeed,
                imperial = Units.imperial.value,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 200.dp),
            )
        }

        if (!state.navigating && state.showSearchThisArea && state.selected == null && !searchOpen) {
            ElevatedButton(
                onClick = vm::searchThisArea,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.mapscreen_search_this_area))
            }
        }

        // Resume-navigation prompt — a drive was cut off by a process-kill (GrapheneOS reaping the
        // backgrounded nav process); offer to pick it back up (re-routes from the current fix).
        if (state.resumeNavLabel != null && !state.navigating && state.selected == null && !searchOpen) {
            val dark = isAppInDarkTheme()
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SheetPalette.bg(dark),
                contentColor = SheetPalette.ink(dark),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            R.string.mapscreen_resume_nav,
                            state.resumeNavLabel!!.ifBlank { stringResource(R.string.mapscreen_your_destination) },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(Modifier.align(Alignment.End).padding(top = 8.dp)) {
                        TextButton(onClick = vm::dismissResume) { Text(stringResource(R.string.mapscreen_dismiss)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = vm::resumeNav) { Text(stringResource(R.string.mapscreen_resume)) }
                    }
                }
            }
        }

        // --- bottom overlay: arrival summary / nav controls / place sheet ---
        when {
            state.arrived && !state.replaying -> ArrivalSummary(
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
                trafficRatio = state.activeRoute?.trafficRatio,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )

            // Tapping "Directions" opens a dedicated panel (popup) — mode tabs, the
            // route option(s) with traffic-aware ETAs, selectable alternates, Start —
            // instead of burying it at the bottom of the place sheet.
            // Hidden while the search overlay is up (e.g. picking a custom origin) so
            // the panel doesn't render over it.
            state.directionsOpen && !searchOpen -> DirectionsPanel(
                originName = if (state.directionsReversed) (state.selected?.name ?: stringResource(R.string.mapscreen_place))
                else (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location)),
                destinationName = if (state.directionsReversed) (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location))
                else (state.selected?.name ?: stringResource(R.string.mapscreen_destination)),
                // Tap the custom endpoint to route to/from somewhere other than your
                // location — the "From" row normally, or the "To" row when reversed (that's
                // where the editable endpoint sits). Both open the search to pick a place.
                onEditOrigin = if (state.directionsReversed) null else vm::beginPickOrigin,
                onEditDestination = if (state.directionsReversed) vm::beginPickOrigin else null,
                stops = state.directionsWaypoints.map { it.name },
                onAddStop = vm::beginPickStop,
                onRemoveStop = vm::removeStop,
                onMoveStop = vm::moveStop,
                onSwap = vm::swapDirections,
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

            state.selected != null && !searchOpen -> PlaceSheet(
                place = state.selected!!,
                isSaved = state.saved.any { it.id == state.selected!!.id },
                reviews = state.reviews,
                reviewsLoading = state.reviewsLoading,
                reviewsFound = state.reviewsFound,
                photosLoading = state.photosLoading,
                detailsLoading = state.loadingDetails,
                placesHere = state.placesHere,
                onClose = vm::clearSelection,
                onToggleSave = vm::toggleSave,
                onDirections = vm::routeToSelected,
                onOpenPlace = vm::selectPlace,
                onOpenSimilar = vm::openSimilar,
                onSetShortcut = vm::setSelectedAsShortcut,
                onRetryReviews = vm::retryReviews,
                // No navigationBarsPadding here: the sheet's background should reach
                // the screen bottom (no map peeking through under the nav bar); the
                // sheet pads its own content for the nav bar instead.
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Replaying a recorded trip drives the dot + camera like a live drive; give the
        // user an explicit way out (its tap stops the replay and resumes live GPS).
        if (state.replaying) {
            ElevatedButton(
                onClick = vm::stopReplay,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.mapscreen_stop_replay))
            }
        }

        if (!state.navigating && state.selected == null && !searchOpen && state.resumeNavLabel == null) {
            FloatingActionButton(
                onClick = vm::recenter,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.mapscreen_center_on_my_location))
            }
            // (The live-traffic overlay toggle moved to Settings → Map — it's a
            // niche browse-only layer, and nav now shows per-segment route traffic,
            // so it no longer earns a spot on the map.)
            // Scale bar, bottom-left just past the attribution ⓘ.
            ScaleBar(
                metersPerPixel = metersPerPixel,
                dark = darkTheme,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 46.dp, bottom = 16.dp),
            )
        }

        // --- transient surfaces --------------------------------------------
        if (state.showPsdsTip) {
            InfoCard(
                title = stringResource(R.string.mapscreen_psds_tip_title),
                body = stringResource(R.string.mapscreen_psds_tip_body),
                actionLabel = stringResource(R.string.mapscreen_got_it),
                onAction = vm::dismissPsdsTip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
        state.status?.let { msg ->
            InfoCard(
                title = stringResource(R.string.mapscreen_heads_up),
                body = msg,
                actionLabel = stringResource(R.string.mapscreen_dismiss),
                onAction = vm::clearStatus,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 96.dp, start = 12.dp, end = 12.dp),
            )
        }
        // Pushed notices (signed calibration channel) — on the bare map only, so they
        // don't cover the nav banner / search / a place sheet.
        if (!state.navigating && state.selected == null && !searchOpen && state.notices.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 84.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.notices.forEach { n ->
                    NoticeCard(n, onDismiss = { vm.dismissNotice(n.id) })
                }
            }
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

/** Per-segment live traffic as (startFraction, endFraction, level) along the route,
 *  converting Google's metre offsets to fractions of the route length — drives the
 *  route line's per-segment colour (Google-style). Empty when there's no live data. */
private fun routeTrafficSpans(route: app.vela.core.model.Route?): List<Triple<Float, Float, Int>> {
    val dist = route?.distanceMeters ?: return emptyList()
    if (dist <= 0.0) return emptyList()
    return route.trafficSpans.map { sp ->
        val s = (sp.startMeters / dist).toFloat().coerceIn(0f, 1f)
        val e = ((sp.startMeters + sp.lengthMeters) / dist).toFloat().coerceIn(0f, 1f)
        Triple(s, e, sp.level)
    }
}

/** The places currently pinned on the map, in marker-index order (so a marker tap maps back to
 *  the right [Place]). Search results win; else the opened place; else the ambient Google POIs
 *  shown on the bare browse map. Dead POIs are dropped from the pins (Google-style). */
private fun displayedPlaces(state: MapUiState): List<Place> = when {
    state.results.isNotEmpty() -> state.results.filterNot { it.permanentlyClosed }
    state.selected != null -> listOf(state.selected)
    else -> emptyList() // ambient Google POIs render as category dots (their own layer), not pins
}

/** Ambient Google POIs to draw as category dots — only on the bare browse map (off during search,
 *  an open place, a route preview, nav, or replay). */
private fun ambientMarkersOf(state: MapUiState): List<MapMarker> =
    if (state.results.isEmpty() && state.selected == null && !state.navigating &&
        !state.replaying && state.activeRoute == null
    ) {
        state.ambientPois.map { MapMarker(it.name, it.location, it.category, app.vela.core.data.google.ambientProminence(it)) }
    } else {
        emptyList()
    }

private fun markersOf(state: MapUiState): List<MapMarker> =
    displayedPlaces(state).map { MapMarker(it.name, it.location) }

@Composable
private fun SearchResults(results: List<Place>, onPick: (Place) -> Unit, onCollapse: () -> Unit) {
    val expandedState = remember { mutableStateOf(false) }
    var openOnly by remember { mutableStateOf(false) }
    var topRated by remember { mutableStateOf(false) }
    // 0 = off; else the max price level to show (1=$ … 4=$$$$). Tapping the chip cycles.
    var priceMax by remember { mutableStateOf(0) }
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
    // Google-style filters: currently open, 4.0★+, and price (≤ the chosen level).
    val shown = results
        .let { list -> if (openOnly) list.filter { it.openNow == true } else list }
        .let { list -> if (topRated) list.filter { (it.rating ?: 0.0) >= 4.0 } else list }
        .let { list -> if (priceMax > 0) list.filter { (it.priceLevel ?: Int.MAX_VALUE) <= priceMax } else list }
    // Same fixed sheet grey as the place sheet, not the wallpaper-tinted Material card.
    val dark = isAppInDarkTheme()
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark), contentColor = SheetPalette.ink(dark)),
    ) {
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
                            .background(SheetPalette.dim(dark).copy(alpha = 0.4f)),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${shown.size} result${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = SheetPalette.dim(dark),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { expandedState.value = !expandedState.value }) {
                        Icon(
                            if (expandedState.value) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expandedState.value) stringResource(R.string.mapscreen_shrink_list) else stringResource(R.string.mapscreen_expand_list),
                            tint = SheetPalette.dim(dark),
                        )
                    }
                }
                // Filter chips on their own horizontally-scrollable row, so a third (or
                // future) chip never crowds the header or clips on a narrow screen.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = openOnly,
                        onClick = { openOnly = !openOnly },
                        label = { Text(stringResource(R.string.mapscreen_filter_open_now)) },
                        leadingIcon = if (openOnly) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                    FilterChip(
                        selected = topRated,
                        onClick = { topRated = !topRated },
                        label = { Text(stringResource(R.string.mapscreen_filter_top_rated)) },
                    )
                    // Price: tap to cycle off → ≤$ → ≤$$ → ≤$$$ → ≤$$$$ → off.
                    FilterChip(
                        selected = priceMax > 0,
                        onClick = { priceMax = (priceMax + 1) % 5 },
                        label = { Text(if (priceMax == 0) stringResource(R.string.mapscreen_filter_price) else "≤ " + "$".repeat(priceMax)) },
                    )
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
                    Text(place.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = SheetPalette.ink(dark))
                    place.rating?.let { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 3.dp),
                        ) {
                            Text(
                                String.format(Locale.US, "%.1f", r),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SheetPalette.dim(dark),
                            )
                            RatingStars(r, starSize = 14.dp, modifier = Modifier.padding(horizontal = 4.dp))
                            place.reviewCount?.let {
                                Text(
                                    "($it)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SheetPalette.dim(dark),
                                )
                            }
                        }
                    }
                    val sub = listOfNotNull(
                        place.priceText,
                        place.category,
                        place.distanceMeters?.let { formatDistance(it) },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheetPalette.dim(dark),
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    // Full address (city/state/zip) to disambiguate similar names
                    // and identical-looking residential addresses.
                    place.address?.let { addr ->
                        Text(
                            addr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheetPalette.dim(dark),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    if (place.permanentlyClosed) {
                        Text(
                            stringResource(R.string.mapscreen_permanently_closed),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = SheetPalette.TrafficRed,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    } else place.statusText?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = placeStatusColor(status, place.openNow),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Divider()
            }
        }
            // The panel hangs from the TOP, so the natural "close" is a bar at its BOTTOM edge —
            // clearer than the top handle alone (which reads backwards). Tap to retract to the
            // "N results" pill; the back gesture still works too.
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable { onCollapse() }.padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = SheetPalette.dim(dark))
                Text(
                    stringResource(R.string.mapscreen_hide_results),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = SheetPalette.dim(dark),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(onPick: (String) -> Unit) {
    // (localized label, STABLE English search query, icon) — the query is the logic key sent to Google
    // search (works in any locale), the label is what the user sees, so the chips localize without
    // changing what's searched.
    val categories = listOf(
        Triple(R.string.cat_restaurants, "Restaurants", Icons.Default.Restaurant),
        Triple(R.string.cat_coffee, "Coffee", Icons.Default.LocalCafe),
        Triple(R.string.cat_gas, "Gas", Icons.Default.LocalGasStation),
        Triple(R.string.cat_groceries, "Groceries", Icons.Default.LocalGroceryStore),
        Triple(R.string.cat_hotels, "Hotels", Icons.Default.Hotel),
        Triple(R.string.cat_pharmacy, "Pharmacy", Icons.Default.LocalPharmacy),
        Triple(R.string.cat_atms, "ATMs", Icons.Default.LocalAtm),
        Triple(R.string.cat_parks, "Parks", Icons.Default.Park),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { (labelRes, query, icon) ->
            ElevatedAssistChip(
                onClick = { onPick(query) },
                label = { Text(stringResource(labelRes)) },
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
    recentPlaces: List<SavedPlace>,
    home: SavedPlace?,
    work: SavedPlace?,
    assigning: ShortcutKind?,
    pickingOrigin: Boolean = false,
    pickingStop: Boolean = false,
    onCancelPickStop: () -> Unit = {},
    onUseMyLocation: () -> Unit = {},
    onPickSuggestion: (Place) -> Unit,
    onPickSaved: (SavedPlace) -> Unit,
    onPickRecent: (String) -> Unit,
    onPickRecentPlace: (SavedPlace) -> Unit,
    onClearRecents: () -> Unit,
    onPickShortcut: (ShortcutKind) -> Unit,
    onAssignShortcut: (ShortcutKind) -> Unit,
    onClearShortcut: (ShortcutKind) -> Unit,
    onCancelAssign: () -> Unit,
    onPinSavedAs: (SavedPlace, ShortcutKind) -> Unit,
    onRemoveSaved: (SavedPlace) -> Unit,
) {
    // While typing, live place suggestions take over the page (Google-style);
    // with an empty box it's the Home/Work + saved + recents shortlist.
    if (suggestions.isNotEmpty()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
        ) {
            if (assigning != null) AssignBanner(assigning, onCancelAssign)
            if (pickingStop) PickStopBanner(onCancelPickStop)
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
        if (assigning != null) AssignBanner(assigning, onCancelAssign)
        if (pickingStop) PickStopBanner(onCancelPickStop)
        // When picking a directions origin, offer "Your location" at the very top to
        // reset back to live GPS (Google-style From picker).
        if (pickingOrigin) {
            SuggestionRow(
                icon = Icons.Default.MyLocation,
                tint = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.mapscreen_your_location),
                onClick = onUseMyLocation,
            )
            Divider()
        }
        // Pinned Home / Work shortcuts (Google-style), above Saved.
        ShortcutRow(ShortcutKind.HOME, home, onPickShortcut, onAssignShortcut, onClearShortcut)
        Divider()
        ShortcutRow(ShortcutKind.WORK, work, onPickShortcut, onAssignShortcut, onClearShortcut)
        Divider()
        if (saved.isNotEmpty()) {
            SectionLabel(stringResource(R.string.mapscreen_section_saved))
            saved.forEach { sp ->
                SavedRow(sp, onPickSaved, onPinSavedAs, onRemoveSaved)
                Divider()
            }
        }
        // Recently-opened places (pin icon) — one tap back to a place you just viewed.
        if (recentPlaces.isNotEmpty()) {
            SectionLabel(stringResource(R.string.mapscreen_section_recent))
            recentPlaces.forEach { rp ->
                SuggestionRow(
                    icon = Icons.Default.Place,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = rp.name,
                    onClick = { onPickRecentPlace(rp) },
                )
                Divider()
            }
        }
        if (recents.isNotEmpty()) {
            SectionLabel(stringResource(R.string.mapscreen_section_recent_searches))
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
                Text(stringResource(R.string.mapscreen_clear_recent_searches))
            }
        }
        if (saved.isEmpty() && recents.isEmpty() && recentPlaces.isEmpty()) {
            Text(
                stringResource(R.string.mapscreen_search_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** A pinned Home/Work shortcut row: opens the place, or arms assign when unset;
 *  a ⋮ menu (Change / Remove) when set. */
@Composable
private fun ShortcutRow(
    kind: ShortcutKind,
    place: SavedPlace?,
    onPick: (ShortcutKind) -> Unit,
    onAssign: (ShortcutKind) -> Unit,
    onClear: (ShortcutKind) -> Unit,
) {
    val icon = if (kind == ShortcutKind.HOME) Icons.Default.Home else Icons.Default.Work
    // Fixed sheet palette (not the theme's on-surface, which renders dark/black on our
    // fixed grey under some Material-You themes / light mode).
    val dark = isAppInDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (place != null) onPick(kind) else onAssign(kind) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (place != null) MaterialTheme.colorScheme.primary else SheetPalette.dim(dark),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(kind.label, style = MaterialTheme.typography.bodyLarge, color = SheetPalette.ink(dark))
            Text(
                place?.name ?: stringResource(R.string.mapscreen_set_shortcut_address, kind.label.lowercase()),
                style = MaterialTheme.typography.bodySmall,
                color = SheetPalette.dim(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (place != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.mapscreen_edit_shortcut, kind.label))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mapscreen_menu_change)) },
                        onClick = { menu = false; onAssign(kind) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mapscreen_menu_remove)) },
                        onClick = { menu = false; onClear(kind) },
                    )
                }
            }
        }
    }
}

/** A saved-place row: tap to open, ⋮ menu to pin it as Home/Work or remove it. */
@Composable
private fun SavedRow(
    place: SavedPlace,
    onPick: (SavedPlace) -> Unit,
    onPinAs: (SavedPlace, ShortcutKind) -> Unit,
    onRemove: (SavedPlace) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(place) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(place.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.mapscreen_saved_place_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mapscreen_set_as_home)) },
                    onClick = { menu = false; onPinAs(place, ShortcutKind.HOME) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mapscreen_set_as_work)) },
                    onClick = { menu = false; onPinAs(place, ShortcutKind.WORK) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mapscreen_menu_remove)) },
                    onClick = { menu = false; onRemove(place) },
                )
            }
        }
    }
}

/** A slim banner while picking a place to pin as Home/Work. */
@Composable
private fun AssignBanner(kind: ShortcutKind, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (kind == ShortcutKind.HOME) Icons.Default.Home else Icons.Default.Work,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.mapscreen_assign_shortcut_hint, kind.label.lowercase()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.mapscreen_cancel)) }
    }
}

/** A slim banner while picking a place to add as a directions stop — without it the Add-stop
 *  picker is visually identical to plain search (no hint you're in a mode, no way out but Back). */
@Composable
private fun PickStopBanner(onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.AddLocationAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.mapscreen_pick_stop_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.mapscreen_cancel)) }
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
    // Fixed sheet palette so this banner reads as the same grey as the place sheet
    // and results list, not a wallpaper-tinted Material card.
    val dark = isAppInDarkTheme()
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = SheetPalette.ink(dark))
                Text(body, style = MaterialTheme.typography.bodySmall, color = SheetPalette.dim(dark))
            }
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A notice pushed through the signed calibration channel — level-tinted, with an
 *  optional "Learn more" link and a per-id Dismiss. */
@Composable
private fun NoticeCard(notice: Notice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = when (notice.level) {
        Notice.LEVEL_ERROR -> MaterialTheme.colorScheme.errorContainer
        Notice.LEVEL_WARN -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when (notice.level) {
        Notice.LEVEL_ERROR -> MaterialTheme.colorScheme.onErrorContainer
        Notice.LEVEL_WARN -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Text(notice.title, fontWeight = FontWeight.SemiBold)
            if (notice.body.isNotBlank()) {
                Text(notice.body, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                notice.url?.let { url ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }) { Text(stringResource(R.string.mapscreen_learn_more)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.mapscreen_dismiss)) }
            }
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
                Text(stringResource(R.string.mapscreen_faster_route_title), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.mapscreen_faster_route_saves, formatDuration(savingSeconds)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mapscreen_no)) }
            Button(onClick = onSwitch) { Text(stringResource(R.string.mapscreen_switch)) }
        }
    }
}

/**
 * The posted speed-limit sign shown by the speedometer during nav — US MUTCD style (white rounded
 * rectangle, "SPEED LIMIT" + number) in imperial units, EU/RoW style (white disc, red ring, number)
 * in metric. The number turns red when the current GPS speed exceeds the limit by a tolerance (GPS
 * speed is noisy, so a plain > would flap). [limitKmh] is the OSM/GraphHopper value in km/h.
 */
@Composable
private fun SpeedLimitSign(
    limitKmh: Double,
    speedMps: Float?,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    val (limit, _) = formatSpeedLimit(limitKmh)
    val speedNow = speedMps?.let { if (imperial) it * 2.236936f else it * 3.6f }
    val tol = if (imperial) 3f else 5f
    val over = speedNow != null && speedNow > limit + tol
    val ink = Color(0xFF202124)
    val numberColor = if (over) Color(0xFFD32F2F) else ink
    if (imperial) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.White,
            shadowElevation = 4.dp,
            border = BorderStroke(2.dp, ink),
            modifier = modifier.width(54.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp),
            ) {
                Text("SPEED", color = ink, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, lineHeight = 9.sp)
                Text("LIMIT", color = ink, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, lineHeight = 9.sp)
                Text("$limit", color = numberColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 4.dp,
            border = BorderStroke(5.dp, Color(0xFFD32F2F)),
            modifier = modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("$limit", color = numberColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
