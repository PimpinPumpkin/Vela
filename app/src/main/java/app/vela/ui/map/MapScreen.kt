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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PublicOff
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
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChipDefaults
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
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
// D-pad-only operation (docs/dpad.md) — kept as one import block so upstream merges stay clean.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.vela.ui.dpadHighlight
import app.vela.ui.rememberDpadMode
import app.vela.ui.rememberDpadFirstDevice
import app.vela.ui.VelaMenu // D-pad-first menu (docs/dpad.md)
import app.vela.ui.item

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
        // Results bottom sheet at peek covers ~the bottom half: frame the result pins
        // in the visible top half, not behind the sheet.
        state.results.isNotEmpty() && state.selected == null && !state.resultsCollapsed &&
            !state.navigating -> (screenHeightPx * 0.50f).toInt()
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

    // --- D-pad-only operation (docs/dpad.md) -------------------------------------
    // dpadMode = user is driving with keys right now (always true with no touchscreen);
    // the map gets a focusable centre target (arrows pan, OK selects, hold-OK = pin) and
    // on-screen zoom buttons. mapDpad is the key→camera seam into VelaMapView.
    val dpadMode = rememberDpadMode()
    val dpadFirst = rememberDpadFirstDevice()
    val mapDpad = remember { MapDpadController() }
    var mapFocused by remember { mutableStateOf(false) }
    var mapEngaged by remember { mutableStateOf(false) } // arrows pan only while engaged (docs/dpad.md)
    // Focuses the centre map target. Used ONLY for Choose-on-map (entered mid-session, so
    // requestFocus lands) — the cold-open bare map deliberately does not auto-focus it (docs/dpad.md).
    val mapFocusRequester = remember { FocusRequester() }
    // D-pad (docs/dpad.md): under touch the overlay tracks field focus (blur = close), but
    // under D-pad focus must be able to WALK the overlay's rows without it snapping shut the
    // instant the field blurs — AND back must be able to definitively close it. A derived
    // focus-latch could do the first but got STUCK on the second (focus never fully left the
    // overlay tree, so it never closed — the "no way to go back from search" bug). So the
    // entry overlay is an EXPLICIT boolean instead: opened on field focus, closed on
    // touch-blur / BACK / once a search runs or a place is picked (the effect below).
    var searchExpanded by remember { mutableStateOf(false) }
    // A search producing results, or any place selection, ends the entry page in BOTH input
    // modes (under touch clearFocus already did; under D-pad clearFocus leaves focus in the
    // tree, so close it here). Pick-mode keeps the overlay up (handled via searchOpen below).
    LaunchedEffect(state.results.size, state.selected, state.pickingOrigin, state.pickingStop) {
        if (!state.pickingOrigin && !state.pickingStop &&
            (state.results.isNotEmpty() || state.selected != null)
        ) {
            searchExpanded = false
        }
    }
    // The search overlay is open when the entry page is expanded OR we're picking a custom
    // directions origin/stop (that opens the same overlay WITHOUT focusing the field).
    val searchOpen = searchExpanded || state.pickingOrigin || state.pickingStop
    // The results panel is open (not collapsed to the "N results" pill) → hide the bottom map
    // chrome (scale bar / locate FAB / Search this area) so it never draws on top of the list at
    // ANY size, not just full screen. The panel and the chrome are siblings in the same Box and the
    // chrome is declared later, so it stacks above the panel unless gated out (user 2026-07-08).
    val resultsShown = state.results.isNotEmpty() && state.selected == null && !searchOpen && !state.resultsCollapsed
    // Expanded detent of the results bottom sheet, hoisted here so the BACK gesture can step it
    // one detent (expanded -> peek) before collapsing to the minimized bar (user 2026-07-09).
    var resultsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.results) { if (state.results.isEmpty()) resultsExpanded = false }
    // The results sheet minimized to its short bottom bar — the chrome shows again then, but
    // lifted above the bar so the FAB / scale bar / Search this area never sit on top of it.
    val resultsMinimized = state.results.isNotEmpty() && state.selected == null && !searchOpen && state.resultsCollapsed
    val chromeLift = if (resultsMinimized) 76.dp else 0.dp
    var metersPerPixel by remember { mutableStateOf(0.0) }
    // Measured screen-Y of the maneuver banner's bottom edge → so VelaMapView can sit the compass just below
    // it during nav (the banner's height varies with lane guidance + a "then" row, so it can't be guessed).
    var navBannerBottomPx by remember { mutableStateOf(0) }
    // Measured height of the nav BOTTOM bar (ETA + End) → everything stacked above it (speedometer,
    // speed-limit sign, re-center FAB, GPS-lost chip) offsets from the REAL height instead of a fixed
    // 132dp guess. The bar grows with the system font size, and at a larger font scale the fixed offset
    // left the speedo half-covered by the bar (GitHub issue #2). Falls back to the old constant until
    // the first layout pass measures it.
    var navBarHeightPx by remember { mutableStateOf(0) }
    val navBarClearance = with(LocalDensity.current) {
        // bar height + its 16dp bottom padding + a 16dp gap — reproduces the old 132dp at default font scale
        if (navBarHeightPx > 0) navBarHeightPx.toDp() + 32.dp else 132.dp
    }
    val focusManager = LocalFocusManager.current

    // Back peels one layer at a time — steps → navigation → route preview →
    // place sheet → results list — so it behaves like Google Maps instead of
    // dropping straight out of the app. Only the bare map (or collapsed pins,
    // which a back already peeled down to) lets the system handle back and exit.
    // ONE back handler (docs/dpad.md): folding the D-pad "disengage map" case in here
    // (rather than a second BackHandler) keeps a single, well-ordered precedence — a
    // separate handler would win by registration order and could swallow BACK while the
    // search overlay is up over an engaged map. Order: cancel map-pick → disengage map →
    // close search → peel nav/route/place/results.
    BackHandler(
        enabled = mapEngaged || searchOpen || state.showSteps || state.navigating || state.transitNav != null ||
            state.directionsOpen || state.activeRoute != null || state.routes.isNotEmpty() ||
            state.selected != null ||
            state.results.isNotEmpty(),
    ) {
        when {
            state.transitNav != null -> vm.endTransitNav()
            state.pickOnMap != null -> vm.cancelChooseOnMap()
            // Disengage map control only when nothing more prominent is open (a sheet /
            // search / route sitting on top should peel first).
            mapEngaged && !searchOpen && !state.showSteps && !state.navigating &&
                !state.directionsOpen && state.activeRoute == null && state.routes.isEmpty() &&
                state.selected == null &&
                (state.results.isEmpty() || state.resultsCollapsed) -> mapEngaged = false
            searchOpen -> { searchExpanded = false; focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickStop() }
            state.showSteps -> vm.closeSteps()
            state.navigating -> vm.stopNav()
            state.directionsOpen || state.activeRoute != null || state.routes.isNotEmpty() ||
                state.transit.isNotEmpty() || state.transitLoading -> vm.clearRoute()
            state.selected != null -> vm.clearSelection()
            // Results sheet: back steps ONE detent (expanded -> peek -> minimized -> cleared),
            // never straight out of the app (a back on the minimized bar used to exit Vela).
            resultsExpanded -> resultsExpanded = false
            !state.resultsCollapsed -> vm.collapseResults()
            else -> vm.clearSearch()
        }
    }
    // The map target is the focus surface ONLY when the map is primary — hidden while any
    // list/sheet/panel/search owns the screen (those own focus; a crosshair over them stole
    // DOWN traversal into their rows). Nav keeps the map primary (the banner is an overlay).
    // EXCEPTION: "Choose on map" (pickOnMap) — the crosshair pick REQUIRES the map be
    // pannable (arrows) so the user can position the pin, so the map target stays active even
    // though directionsOpen is still true underneath (measured: without this, arrows only
    // moved focus to the cancel X and the pin couldn't be moved). OK then confirms the pick.
    val mapTargetHidden = state.pickOnMap == null && (
        searchOpen || state.selected != null || state.directionsOpen ||
            state.showSteps || state.arrived ||
            (state.results.isNotEmpty() && !state.resultsCollapsed && state.selected == null)
        )
    // Reset engagement the moment a panel takes over (the target unmounts under it).
    LaunchedEffect(mapTargetHidden) { if (mapTargetHidden) mapEngaged = false }
    // D-pad-first, the bare map (docs/dpad.md): the map does NOT auto-focus or auto-engage on open.
    // The user asked for the search bar to be the landing focus, not the engaged map (which used to
    // force a BACK press to move). Compose won't let us programmatically pre-place focus on the
    // SEARCH BAR on the app's opening screen (verified ~13 ways: requestFocus no-ops with no prior
    // focus; moveFocus lands only on the centre map target; moveFocus(Up)/Enter and synthetic
    // KeyEvents don't take), so instead nothing is focused on open and the user's first arrow
    // lands on the search bar — Compose's real-first-key initial focus picks the first focusable,
    // which IS the search bar (measured). Net: no map engage, no BACK, one arrow reaches search.
    // This is the ONE screen that intentionally opens un-focused (the map is ambient; the first key
    // isn't wasted — it goes straight to search). (Was: auto-engage the map — user report 2026-07-08.)
    // Choose-on-map (pickOnMap) is the EXCEPTION to the exception: there the whole task IS moving the
    // map to place a pin, so we DO auto-focus + engage the map target the moment pick mode opens, so
    // arrows pan immediately and OK confirms (the crosshair/pill are suppressed in pick mode because
    // the ChooseOnMapOverlay draws the pin + "Move the map" banner). This is entered mid-session (from
    // the search entry), so focus already exists and requestFocus lands — unlike the cold-open bare map.
    val pickingOnMap = state.pickOnMap != null
    LaunchedEffect(pickingOnMap, dpadFirst) {
        if (dpadFirst && pickingOnMap) {
            repeat(20) {
                if (runCatching { mapFocusRequester.requestFocus() }.isSuccess) { mapEngaged = true; return@LaunchedEffect }
                kotlinx.coroutines.delay(50)
            }
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
            frameMarkers = state.results.isNotEmpty() && state.selected == null && !state.resultsCollapsed,
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
            transitOn = app.vela.ui.TransitLayer.on.value,
            previewTarget = state.previewStepIndex?.let { state.activeRoute?.maneuvers?.getOrNull(it)?.location },
            onPoiTap = vm::onPoiTap,
            onMarkerTap = { i -> displayedPlaces(state).getOrNull(i)?.let(vm::selectPlace) },
            ambientPois = ambientMarkersOf(state),
            buildingOverlays = state.buildingOverlays,
            addressOverlays = state.addressOverlays,
            trafficControls = state.trafficControls,
            navBannerBottomPx = if (state.navigating) navBannerBottomPx else 0,
            onAmbientTap = { i -> state.ambientPois.getOrNull(i)?.let(vm::selectPlace) },
            onCameraIdle = vm::onCameraIdle,
            onMapLongPress = vm::onMapLongPress,
            onAddressLabelTap = vm::onAddressLabelTap,
            onViewport = vm::onViewport,
            dpadController = mapDpad,
            modifier = Modifier.fillMaxSize(),
        )

        // --- D-pad map target (docs/dpad.md) -------------------------------
        // TWO-STAGE so the chrome stays reachable (v1 trapped focus on the map):
        //  · FOCUSED (ring + "OK" pill): a normal focus stop — arrows traverse to the
        //    search bar / chips / zoom buttons / FABs like any other element; OK engages.
        //  · ENGAGED (crosshair + edge ring): arrows pan, OK "taps" the crosshair (or
        //    confirms a Choose-on-map pick), holding OK long-presses (pin / direct pick),
        //    +/−/zoom keys zoom, BACK disengages (focus stays on the target).
        // Shown only when the MAP is the primary surface — with a list/sheet/panel open the
        // panel owns focus (a centre crosshair + focus stop over the results list stole DOWN
        // traversal into the rows). Closing a panel returns to the bare map un-engaged (the first
        // arrow reaches the search bar); only Choose-on-map auto-engages the target (see above).
        if (dpadMode && !mapTargetHidden) {
            if (mapEngaged) {
                // Screen-edge ring: unmistakable "the MAP has the keys now" signal.
                Box(
                    Modifier
                        .matchParentSize()
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                )
            }
            val centerHeld = remember { booleanArrayOf(false) } // long-press fired for the held OK
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(140.dp)
                    .focusRequester(mapFocusRequester)
                    .onFocusChanged {
                        mapFocused = it.isFocused
                        if (!it.isFocused) mapEngaged = false
                    }
                    .onKeyEvent { ev ->
                        if (!mapFocused) return@onKeyEvent false
                        val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                        if (!mapEngaged) {
                            // Plain focus stop: only OK does anything (engage); arrows fall
                            // through to normal focus traversal so the chrome is reachable.
                            return@onKeyEvent when {
                                isOk && ev.type == KeyEventType.KeyDown -> true
                                isOk && ev.type == KeyEventType.KeyUp -> { mapEngaged = true; true }
                                else -> false
                            }
                        }
                        val pan = 0.22f // fraction of the view per press; holds auto-repeat
                        when (ev.type) {
                            KeyEventType.KeyDown -> when (ev.key) {
                                Key.DirectionUp -> { mapDpad.panBy(0f, -pan); true }
                                Key.DirectionDown -> { mapDpad.panBy(0f, pan); true }
                                Key.DirectionLeft -> { mapDpad.panBy(-pan, 0f); true }
                                Key.DirectionRight -> { mapDpad.panBy(pan, 0f); true }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    val n = ev.nativeKeyEvent
                                    if (n.repeatCount == 0) {
                                        centerHeld[0] = false
                                    } else if (!centerHeld[0] && n.eventTime - n.downTime >= 500) {
                                        centerHeld[0] = true
                                        mapDpad.longPressAtCenter()
                                    }
                                    true
                                }
                                Key.ZoomIn, Key.Plus, Key.Equals -> { mapDpad.zoomBy(1.0); true }
                                Key.ZoomOut, Key.Minus -> { mapDpad.zoomBy(-1.0); true }
                                else -> false
                            }
                            KeyEventType.KeyUp -> when (ev.key) {
                                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                                Key.ZoomIn, Key.Plus, Key.Equals, Key.ZoomOut, Key.Minus,
                                -> true
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    if (!centerHeld[0]) {
                                        // In Choose-on-map mode OK = the crosshair confirm;
                                        // otherwise it's a tap at the crosshair.
                                        if (state.pickOnMap != null) vm.confirmMapPick() else mapDpad.selectAtCenter()
                                    }
                                    centerHeld[0] = false
                                    true
                                }
                                else -> false
                            }
                            else -> false
                        }
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Crosshair only while the map is key-driven; Choose-on-map draws its own.
                    mapEngaged && state.pickOnMap == null -> {
                        val crossColor = MaterialTheme.colorScheme.primary
                        Canvas(Modifier.size(36.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            drawCircle(Color.White, radius = size.minDimension * 0.30f, style = Stroke(7f))
                            drawCircle(crossColor, radius = size.minDimension * 0.30f, style = Stroke(3.5f))
                            drawLine(crossColor, Offset(cx, 0f), Offset(cx, cy - 8f), 3.5f)
                            drawLine(crossColor, Offset(cx, cy + 8f), Offset(cx, size.height), 3.5f)
                            drawLine(crossColor, Offset(0f, cy), Offset(cx - 8f, cy), 3.5f)
                            drawLine(crossColor, Offset(cx + 8f, cy), Offset(size.width, cy), 3.5f)
                        }
                    }
                    // Focused but not engaged: a visible stop + how to enter map control.
                    // In Choose-on-map mode draw NOTHING here — the ChooseOnMapOverlay
                    // supplies the pin + "Move the map to set…" banner, and the target is
                    // auto-engaged so arrows already pan; the "OK: move the map" pill would
                    // be wrong there (OK confirms the pick, it doesn't enter map control).
                    mapFocused && state.pickOnMap == null -> Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 4.dp,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Text(
                            stringResource(R.string.mapscreen_dpad_engage),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

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
                destName = state.arrivedLabel,
                destAddress = state.navDestAddress,
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
                    .padding(12.dp)
                    // Report the banner's bottom edge so the compass can drop just below it (any height).
                    .onGloballyPositioned { navBannerBottomPx = (it.positionInRoot().y + it.size.height).roundToInt() },
            )
        } else if (state.pickOnMap == null) {
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
                        onFocusChange = {
                            searchFocused = it
                            // Focus opens the entry page; a touch blur closes it. Under
                            // D-pad, blur must NOT close (focus walks the rows) — BACK /
                            // a run search / a pick close it instead.
                            if (it) searchExpanded = true else if (!dpadMode) searchExpanded = false
                        },
                        onBack = if (searchOpen) ({ searchExpanded = false; focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickStop() }) else null,
                        offline = state.offline,
                        dpadMode = dpadMode,
                    )
                    when {
                        // Show the entry page (Your location, Choose on map, Home/Work, saved, recents)
                        // when the field is focused, when there are no results yet, OR while picking an
                        // origin/stop with a blank query. That last case matters: tapping "From" when the
                        // destination search still had results (plus a place selected) matched NO branch,
                        // so the picker was BLANK and "Choose on map" was unreachable. Typing a query then
                        // fills the entry page with suggestions as usual.
                        searchOpen && (
                            searchFocused || state.results.isEmpty() ||
                                ((state.pickingOrigin || state.pickingStop) && state.query.isBlank())
                            ) -> SearchEntryContent(
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
                            onChooseOnMap = {
                                focusManager.clearFocus()
                                if (state.pickingOrigin) vm.chooseOriginOnMap() else vm.chooseStopOnMap()
                            },
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

                        // Results now live in a BOTTOM sheet (rendered with the other bottom
                        // surfaces below, Google-style); the top bar keeps only the category
                        // chips, and only on the bare map.
                        state.selected == null && state.results.isEmpty() -> CategoryChips(onPick = vm::quickSearch)
                    }

                    // Quiet offline marker: a small globe-with-a-slash chip tucked just under the category
                    // chips, near the search box (pairs with the greyed "Offline" in the bar). Only on the
                    // bare map — the same state the chips show in — so it never trails a results list.
                    if (state.offline && !searchOpen && !state.navigating && !state.replaying &&
                        state.selected == null && state.results.isEmpty()
                    ) {
                        Surface(
                            color = SheetPalette.bg(darkTheme).copy(alpha = 0.82f),
                            shape = CircleShape,
                            shadowElevation = 2.dp,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp).size(34.dp),
                        ) {
                            Icon(
                                Icons.Default.PublicOff,
                                contentDescription = stringResource(R.string.search_offline),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(7.dp),
                            )
                        }
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
                    .dpadHighlight(RoundedCornerShape(16.dp))
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = navBarClearance),
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
                // Clears the speedo/FAB band above the MEASURED bar; width-capped +
                // single-line so long translations can't collide with either.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = navBarClearance + 68.dp, start = 90.dp, end = 90.dp),
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
                    .padding(start = 16.dp, bottom = navBarClearance)
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
                    .padding(start = 16.dp, bottom = navBarClearance + 68.dp), // above the 60dp speedo + 8dp gap
            )
        }

        if (!state.navigating && state.showSearchThisArea && state.selected == null && !searchOpen && !resultsShown) {
            ElevatedButton(
                onClick = vm::searchThisArea,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp + chromeLift),
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
                destinationAddress = state.navDestAddress,
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
                // Arrive-row destination lines: the live nav state while navigating; pre-nav (the
                // Steps preview from the directions panel) the selected place, unless the trip is
                // reversed (the destination is you, so a place line would be wrong).
                destName = when {
                    state.navigating -> state.arrivedLabel
                    !state.directionsReversed -> state.selected?.name
                    else -> null
                },
                destAddress = when {
                    state.navigating -> state.navDestAddress
                    !state.directionsReversed -> state.selected?.address
                    else -> null
                },
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
                    .padding(16.dp)
                    // Measured AFTER the padding → the bar surface itself; navBarClearance adds the
                    // padding + gap back. Everything stacked above the bar keys off this.
                    .onGloballyPositioned { navBarHeightPx = it.size.height },
            )

            // Tapping "Directions" opens a dedicated panel (popup) — mode tabs, the
            // route option(s) with traffic-aware ETAs, selectable alternates, Start —
            // instead of burying it at the bottom of the place sheet.
            // Hidden while the search overlay is up (e.g. picking a custom origin) so
            // the panel doesn't render over it.
            state.directionsOpen && !searchOpen && state.pickOnMap == null -> DirectionsPanel(
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
                onWalkDirections = vm::walkDirections,
                onStartTransit = vm::startTransitNav,
                onClose = vm::clearRoute,
                onTimeSelected = vm::setDirectionsTime,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            state.selected != null && !searchOpen && state.pickOnMap == null -> PlaceSheet(
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

            // Search results as a BOTTOM sheet, Google-style — same detent family as the place
            // sheet (minimized bar ↔ peek ↔ expanded). Reached only when nothing above matched,
            // so a selected place / directions / nav always win the bottom slot.
            state.results.isNotEmpty() && !searchOpen && state.pickOnMap == null -> SearchResults(
                results = state.results,
                collapsed = state.resultsCollapsed,
                expanded = resultsExpanded,
                onExpandedChange = { resultsExpanded = it },
                onPick = {
                    focusManager.clearFocus()
                    vm.selectPlace(it)
                },
                onMinimize = vm::collapseResults,
                onExpand = vm::expandResults,
                onClose = vm::clearSearch,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Full-screen transit step-by-step guidance (Moovit-style) — covers everything while active.
        state.transitNav?.let { tn ->
            app.vela.ui.place.TransitNavSheet(
                nav = tn,
                onNext = vm::advanceTransitNav,
                onBack = vm::backTransitNav,
                onEnd = vm::endTransitNav,
                onWalkDirections = vm::walkDirections,
            )
        }

        // "Choose on map" crosshair — the map is visible; a fixed pin marks screen centre. Move the
        // map under it (or long-press) and Confirm to set the start/stop from that point (Google-style).
        state.pickOnMap?.let { target ->
            ChooseOnMapOverlay(
                target = target,
                onConfirm = vm::confirmMapPick,
                onCancel = vm::cancelChooseOnMap,
            )
        }

        // D-pad zoom buttons (docs/dpad.md): pinch has no key equivalent, so give zoom a
        // first-class on-screen control while the UI is key-driven. Shown ONLY while
        // browsing the map with no list/sheet/panel over it — the mid-right buttons sit in
        // the vertical focus path of the results list / place sheet and would intercept
        // DOWN traversal into their rows (measured: DOWN from the results header jumped to
        // the zoom + button instead of the first result). During those, the map is behind
        // a panel anyway; zoom the map via the engaged crosshair after closing the panel.
        val zoomButtonsVisible = dpadMode && !searchOpen && !state.navigating &&
            state.selected == null && !state.directionsOpen && !state.showSteps &&
            state.activeRoute == null && state.routes.isEmpty() &&
            (state.results.isEmpty() || state.resultsCollapsed)
        if (zoomButtonsVisible) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { mapDpad.zoomBy(1.0) },
                    modifier = Modifier.dpadHighlight(RoundedCornerShape(12.dp)),
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mapscreen_zoom_in)) }
                SmallFloatingActionButton(
                    onClick = { mapDpad.zoomBy(-1.0) },
                    modifier = Modifier.dpadHighlight(RoundedCornerShape(12.dp)),
                ) { Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.mapscreen_zoom_out)) }
            }
        }

        // Replaying a recorded trip drives the dot + camera like a live drive; give the
        // user an explicit way out (its tap stops the replay and resumes live GPS). A DEMO drive
        // (Settings → Simulate driving) is meant to look like real nav — its own "End" button stops
        // it (stopNav cancels the demo), so don't show the replay pill over the nav chrome.
        if (state.replaying && !state.demoDriving) {
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

        if (!state.navigating && state.selected == null && !searchOpen && state.resumeNavLabel == null && !resultsShown) {
            FloatingActionButton(
                onClick = vm::recenter,
                modifier = Modifier
                    .dpadHighlight(RoundedCornerShape(16.dp))
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .padding(bottom = chromeLift),
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
                    .padding(start = 46.dp, bottom = 16.dp + chromeLift),
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
        // Pushed notices (signed calibration channel) + the voice-download progress card — on the
        // bare map only, so they don't cover the nav banner / search / a place sheet. The download
        // card makes the ONBOARDING one-tap voice install visible (it used to run invisibly after
        // the prompt dismissed — progress only existed in Settings; user 2026-07-07).
        val downloadingVoiceId = state.voiceDownloadingId
        val downloadingRegion = state.routingDownloadingId != null || state.poiPackDownloadingId != null
        if (!state.navigating && state.selected == null && !searchOpen &&
            (state.notices.isNotEmpty() || downloadingVoiceId != null || downloadingRegion || state.updateInfo != null)
        ) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 84.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (downloadingVoiceId != null) {
                    VoiceDownloadCard(installing = state.voiceInstalling, pct = state.kokoroDownloadPct ?: 0f)
                }
                // A region (state/country) download: the routing graph first, then its place pack —
                // same progress card treatment as the voice download, so a Settings-started state
                // download stays visible after backing out to the map.
                if (downloadingRegion) {
                    RegionDownloadCard(
                        name = state.regionDownloadName ?: "",
                        places = state.poiPackDownloadingId != null,
                        pct = if (state.poiPackDownloadingId != null) state.poiPackDownloadPct else state.routingDownloadPct,
                    )
                }
                // A newer release on GitHub (self-updater; the check is a Settings toggle).
                state.updateInfo?.let { u ->
                    UpdateCard(
                        versionName = u.versionName,
                        downloadPct = state.updateDownloadPct,
                        onUpdate = { vm.downloadUpdate() },
                        onDismiss = { vm.dismissUpdate() },
                    )
                }
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
private fun SearchResults(
    results: List<Place>,
    collapsed: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (Place) -> Unit,
    onMinimize: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A BOTTOM sheet, Google-style, sharing the place sheet's detent grammar:
    // MINIMIZED (a short "N results" bar, = the VM's resultsCollapsed so back agrees)
    // ↔ PEEK (~0.42) ↔ EXPANDED (~0.82, hoisted to MapScreen so BACK steps it first).
    // Drag the handle (or the list at its top) DOWN to shrink a detent, UP to grow
    // one; tap the handle to step up. The X exits the search entirely (results + query),
    // same as backing all the way out.
    var openOnly by remember { mutableStateOf(false) }
    var topRated by remember { mutableStateOf(false) }
    // 0 = off; else the max price level to show (1=$ … 4=$$$$). Tapping the chip cycles.
    var priceMax by remember { mutableStateOf(0) }
    val screenH = LocalConfiguration.current.screenHeightDp
    val listMaxH by animateDpAsState(
        if (expanded) (screenH * 0.82f).dp else (screenH * 0.42f).dp,
        label = "resultsListHeight",
    )
    // Bottom-sheet nested scroll, mirroring PlaceSheet.dismissConn: ONE detent step per
    // gesture (re-armed at the fling boundary), a down-drag at the list top shrinks a
    // detent (expanded → peek → minimized), an up-drag into the content grows to full.
    val listState = rememberLazyListState()
    val minimize = rememberUpdatedState(onMinimize)
    val isExpanded = rememberUpdatedState(expanded)
    val setExpanded = rememberUpdatedState(onExpandedChange)
    val dismissConn = remember(collapsed) {
        object : NestedScrollConnection {
            private var acc = 0f
            private var steppedThisGesture = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    acc += available.y
                    if (!steppedThisGesture) {
                        when {
                            isExpanded.value && acc > 90f -> { setExpanded.value(false); steppedThisGesture = true; acc = 0f }
                            !isExpanded.value && !collapsed && acc > 150f -> { steppedThisGesture = true; acc = 0f; minimize.value() }
                        }
                    }
                    return available
                }
                if (available.y < 0f) {
                    acc = 0f
                    if (!isExpanded.value) setExpanded.value(true)
                }
                return Offset.Zero
            }
            // Fling phase closes every drag (even at zero velocity) — the gesture boundary
            // that re-arms stepping for the next swipe, exactly like the place sheet.
            override suspend fun onPreFling(available: Velocity): Velocity {
                acc = 0f
                steppedThisGesture = false
                return Velocity.Zero
            }
        }
    }
    // Sort: 0 = relevance (Google's order), 1 = rating, 2 = distance. Tapping the chip cycles.
    var sortMode by remember { mutableStateOf(0) }
    // Google-style filters: currently open, 4.0★+, and price (≤ the chosen level).
    // "Open now" falls back to the WEEKLY HOURS when Google sent no live status (openNow == null) —
    // the multi-result response often omits the status string, and dropping those places made the
    // filter read as broken ("open places disappear"); the place sheet already computes the same
    // fallback. A place with no status AND no parseable hours still drops (can't confirm open).
    val nowForHours = remember(openOnly) { java.time.LocalDateTime.now() }
    val shown = results
        .let { list ->
            if (!openOnly) list else list.filter { p ->
                p.openNow ?: (app.vela.core.util.OpeningHours.statusAt(p.hours, nowForHours)?.open == true)
            }
        }
        .let { list -> if (topRated) list.filter { (it.rating ?: 0.0) >= 4.0 } else list }
        .let { list -> if (priceMax > 0) list.filter { (it.priceLevel ?: Int.MAX_VALUE) <= priceMax } else list }
        .let { list ->
            when (sortMode) {
                1 -> list.sortedByDescending { it.rating ?: -1.0 }
                2 -> list.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
                else -> list
            }
        }
    // Same fixed sheet grey as the place sheet, not the wallpaper-tinted Material card.
    val dark = isAppInDarkTheme()
    Card(
        // statusBarsPadding caps the sheet's growth below the status bar, so the handle pill
        // never slides under the clock / camera cutout when expanded (user 2026-07-09).
        modifier.statusBarsPadding().padding(top = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark), contentColor = SheetPalette.ink(dark)),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            // Handle, the place sheet's exact grammar for a bottom sheet: TAP steps one
            // detent UP (minimized→peek, peek↔expanded). Drag UP grows a detent, drag
            // DOWN shrinks one (expanded→peek→minimized). No hide button; the minimized
            // bar IS the collapsed state.
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(collapsed, expanded) {
                        detectTapGestures(onTap = {
                            if (collapsed) onExpand() else onExpandedChange(!expanded)
                        })
                    }
                    .pointerInput(collapsed, expanded) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { change, dy -> change.consume(); total += dy },
                            onDragEnd = {
                                when {
                                    total < -40f && collapsed -> onExpand()
                                    total < -40f -> onExpandedChange(true)
                                    total > 40f && expanded -> onExpandedChange(false)
                                    total > 40f && !collapsed -> onMinimize()
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
                        .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = if (collapsed) 8.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${shown.size} result${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = SheetPalette.dim(dark),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (collapsed) onExpand() else onExpandedChange(!expanded) }) {
                        Icon(
                            if (!collapsed && expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (!collapsed && expanded) stringResource(R.string.mapscreen_shrink_list) else stringResource(R.string.mapscreen_expand_list),
                            tint = SheetPalette.dim(dark),
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.mapscreen_close_results),
                            tint = SheetPalette.dim(dark),
                        )
                    }
                }
                if (!collapsed) {
                // Filter chips on their own horizontally-scrollable row, so a third (or
                // future) chip never crowds the header or clips on a narrow screen. Filled pills
                // (a subtle tint when off, solid teal when on) so they read modern on the sheet —
                // the default outlined M3 chip looked "old" against the filled category chips
                // (user 2026-07-08). No border; a check icon marks an active toggle.
                // OPAQUE container colours: these are ELEVATED chips, and a translucent container
                // let the elevation SHADOW show through the pill — invisible on the dark sheet but
                // a muddy near-black blob on the light one (user report 2026-07-08). The solids are
                // the translucent values composited over each sheet colour.
                val chipColors = FilterChipDefaults.elevatedFilterChipColors(
                    containerColor = if (dark) Color(0xFF333539) else Color(0xFFF1F3F4),
                    labelColor = SheetPalette.ink(dark),
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ElevatedFilterChip(
                        selected = openOnly,
                        onClick = { openOnly = !openOnly },
                        label = { Text(stringResource(R.string.mapscreen_filter_open_now)) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = chipColors,
                        border = null,
                        leadingIcon = if (openOnly) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                    ElevatedFilterChip(
                        selected = topRated,
                        onClick = { topRated = !topRated },
                        label = { Text(stringResource(R.string.mapscreen_filter_top_rated)) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = chipColors,
                        border = null,
                        leadingIcon = if (topRated) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                    // Price: tap to cycle off → ≤$ → ≤$$ → ≤$$$ → ≤$$$$ → off.
                    ElevatedFilterChip(
                        selected = priceMax > 0,
                        onClick = { priceMax = (priceMax + 1) % 5 },
                        label = { Text(if (priceMax == 0) stringResource(R.string.mapscreen_filter_price) else "≤ " + "$".repeat(priceMax)) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = chipColors,
                        border = null,
                    )
                    // Sort: tap to cycle relevance (Google's order) → rating → distance.
                    ElevatedFilterChip(
                        selected = sortMode > 0,
                        onClick = { sortMode = (sortMode + 1) % 3 },
                        label = {
                            Text(
                                when (sortMode) {
                                    1 -> stringResource(R.string.mapscreen_sort_rating)
                                    2 -> stringResource(R.string.mapscreen_sort_distance)
                                    else -> stringResource(R.string.mapscreen_sort)
                                },
                            )
                        },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = chipColors,
                        border = null,
                    )
                }
                } // if (!collapsed) — chips
            }
            if (!collapsed) {
            Divider()
            LazyColumn(Modifier.nestedScroll(dismissConn).heightIn(max = listMaxH), state = listState) {
                items(shown) { place ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dpadHighlight(RoundedCornerShape(6.dp))
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
            } // if (!collapsed) — list
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
                modifier = Modifier.dpadHighlight(RoundedCornerShape(8.dp)),
                label = { Text(stringResource(labelRes)) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                // Full pill, Google-style — the M3 default 8dp corners read dated on a map chip row.
                shape = androidx.compose.foundation.shape.CircleShape,
                // MONOCHROME glyphs (user 2026-07-06): the M3 default tints the leading icon with the
                // theme primary (teal), Google's chips are single-ink — icon matches the label colour.
                colors = androidx.compose.material3.AssistChipDefaults.elevatedAssistChipColors(
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/** "Choose on map" mode: a full-screen overlay over the live map with a centre crosshair, a hint
 *  banner and a Confirm button. Empty areas carry no gesture modifiers, so map pan/zoom pass straight
 *  through to the MapLibre view below; only the banner and button consume touches. */
@Composable
private fun ChooseOnMapOverlay(
    target: MapPick,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Text(
                    stringResource(
                        if (target == MapPick.ORIGIN) R.string.mapscreen_choose_origin_hint
                        else R.string.mapscreen_choose_stop_hint,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.mapscreen_cancel))
                }
            }
        }
        // Pin whose tip points at the exact map centre (offset up by ~half its height).
        Icon(
            Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .offset(y = (-22).dp),
        )
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(
                stringResource(
                    if (target == MapPick.ORIGIN) R.string.mapscreen_choose_set_start
                    else R.string.mapscreen_choose_set_stop,
                ),
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
    onChooseOnMap: () -> Unit = {},
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
        // "Choose on map" — leave the search overlay and set this endpoint by moving a crosshair
        // over the live map (or long-pressing), Google-style. Offered for both origin and stop.
        if (pickingOrigin || pickingStop) {
            SuggestionRow(
                icon = Icons.Default.Place,
                tint = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.mapscreen_choose_on_map),
                onClick = onChooseOnMap,
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
            .dpadHighlight(RoundedCornerShape(6.dp))
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
                VelaMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    item(stringResource(R.string.mapscreen_menu_change)) { menu = false; onAssign(kind) }
                    item(stringResource(R.string.mapscreen_menu_remove)) { menu = false; onClear(kind) }
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
            .dpadHighlight(RoundedCornerShape(6.dp))
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
            VelaMenu(expanded = menu, onDismissRequest = { menu = false }) {
                item(stringResource(R.string.mapscreen_set_as_home)) { menu = false; onPinAs(place, ShortcutKind.HOME) }
                item(stringResource(R.string.mapscreen_set_as_work)) { menu = false; onPinAs(place, ShortcutKind.WORK) }
                item(stringResource(R.string.mapscreen_menu_remove)) { menu = false; onRemove(place) }
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
        Modifier.fillMaxWidth().dpadHighlight(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
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

/** Voice-download progress over the map — makes the onboarding one-tap install visible (it used to
 *  run with no surface outside Settings). Reads the SAME state the Settings row does, so it also
 *  shows when a Settings-started download is still running after backing out to the map. The bar
 *  includes the extract phase (KokoroInstaller maps untar into the tail), so it no longer parks at
 *  ~98% while the archive unpacks. */
@Composable
private fun VoiceDownloadCard(installing: Boolean, pct: Float, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (installing) stringResource(R.string.map_voice_installing)
                else stringResource(R.string.map_voice_downloading, (pct * 100).toInt()),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            // Determinate while downloading; the unpack step can't report a meaningful %, so it goes
            // indeterminate under the "Installing…" label rather than crawling a frozen-looking bar.
            if (installing) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { pct.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Progress card for a region (state/country) offline download — the routing graph, then the
 *  region's place pack. Mirrors [VoiceDownloadCard] so a Settings-started download stays visible
 *  on the map. */
@Composable
private fun RegionDownloadCard(name: String, places: Boolean, pct: Int, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (places) stringResource(R.string.map_region_places_downloading, name, pct)
                else stringResource(R.string.map_region_downloading, name, pct),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A notice pushed through the signed calibration channel — level-tinted, with an
 *  optional "Learn more" link and a per-id Dismiss. */
/** "A newer Vela is out" card (self-updater): download with progress, then the system
 *  installer takes over. "Not now" silences this version until a newer one appears. */
@Composable
private fun UpdateCard(
    versionName: String,
    downloadPct: Int?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.update_available_title, versionName), fontWeight = FontWeight.SemiBold)
            if (downloadPct != null) {
                Text(stringResource(R.string.update_downloading, downloadPct), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { downloadPct / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                    TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_install)) }
                }
            }
        }
    }
}

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
                // Cap a pushed notice's body so a long one can't grow the card past a small screen and
                // shove the Dismiss/Learn-more buttons off the bottom (the notice overlay doesn't scroll).
                Text(notice.body, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
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
