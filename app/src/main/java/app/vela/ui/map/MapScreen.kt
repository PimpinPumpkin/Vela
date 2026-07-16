package app.vela.ui.map

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
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
import app.vela.core.model.distanceTo
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
// D-pad-only operation (docs/dpad.md) - kept as one import block so upstream merges stay clean.
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
import app.vela.ui.toggleItem
import app.vela.ui.rememberDpadAutoFocus // D-pad-first initial focus (docs/dpad.md)
import app.vela.ui.rememberDpadMode
import app.vela.ui.rememberDpadFirstDevice
import app.vela.ui.VelaMenu // D-pad-first menu (docs/dpad.md)
import app.vela.ui.item

// Basemap provider. Keyless OpenFreeMap (loaded by URL - the setup that always
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
    // Street View pose [lat, lng, compassYaw] while the half-screen pano viewer is up - drives the
    // rotating view cone + camera hold on the map underneath. Cleared when the viewer closes.
    var svPose by remember { mutableStateOf<DoubleArray?>(null) }
    LaunchedEffect(state.streetView == null && !state.streetViewLoading) {
        if (state.streetView == null && !state.streetViewLoading) svPose = null
    }
    // Push the optical centre up so the place sheet / directions panel doesn't sit on
    // top of the pin or the route (the directions panel is tall - fit the route above it).
    // The route chooser reports its minimized state up: with only the Start bar left on
    // screen the route fit gets nearly the whole map back, so it re-frames closer instead
    // of staying at the zoomed-out framing the full-height panel needed (upstream a17eded6).
    var dirMinimized by remember { mutableStateOf(false) }
    // Mirrored into the VM too: the route-through-here press is gated on the chooser being
    // minimized (stray building taps while the full picker covered the map added stops).
    LaunchedEffect(dirMinimized) { vm.onDirectionsCollapsed(dirMinimized) }
    LaunchedEffect(state.directionsOpen) { if (!state.directionsOpen) dirMinimized = false }
    val cameraBottomInset = when {
        // Street View pane up: the sheet yields, so no bottom inset (the SV top inset takes over).
        state.streetView != null || state.streetViewLoading -> 0
        placeSheetUp -> (screenHeightPx * 0.56f).toInt()
        state.directionsOpen && !state.navigating ->
            (screenHeightPx * (if (dirMinimized) 0.14f else 0.58f)).toInt()
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
    // requestFocus lands) - the cold-open bare map deliberately does not auto-focus it (docs/dpad.md).
    val mapFocusRequester = remember { FocusRequester() }
    // D-pad (docs/dpad.md): under touch the overlay tracks field focus (blur = close), but
    // under D-pad focus must be able to WALK the overlay's rows without it snapping shut the
    // instant the field blurs - AND back must be able to definitively close it. A derived
    // focus-latch could do the first but got STUCK on the second (focus never fully left the
    // overlay tree, so it never closed - the "no way to go back from search" bug). So the
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
    // Free-drive follow (Google's "the map tracks you as you drive, no route needed"). On by
    // default so an open, unobstructed map glides to your fix; a user pan drops it and the locate
    // tap raises it again. Suppressed whenever a focus surface owns the camera (search, a place,
    // directions, the results list) so it never fights that framing. Nav has its own follow.
    var followMe by remember { mutableStateOf(true) }
    var layersOpen by remember { mutableStateOf(false) } // the top-right layers panel
    // A programmatic camera jump far from the fix (a recents pick, a search hit, a pasted
    // coordinate, a deep link) means the user went to look somewhere else - drop follow exactly
    // like a pan would. Without this, follow was only SUSPENDED while the place sheet owned the
    // camera, so closing the sheet resumed it and the map glided all the way home. A POI tapped
    // near the fix keeps follow; 1 km is the "somewhere else" line.
    LaunchedEffect(state.center) {
        val c = state.center ?: return@LaunchedEffect
        val me = state.myLocation ?: return@LaunchedEffect
        if (followMe && c.distanceTo(me) > 1_000.0) followMe = false
    }
    val driveFollowing = followMe && !state.navigating && !resultsShown && state.selected == null &&
        !state.directionsOpen && !state.showSteps && !searchOpen && state.pickOnMap == null
    // The posted-limit ("Speed B") overlay is armed by ACTUAL MOTION, never by the bare browse map.
    // driveFollowing alone is true whenever you're just looking at the map (followMe defaults on), and
    // keying the overlay off it mounted the maxspeed PMTiles sources on the browse map - native tile
    // fetch + tessellation on every pan/zoom - AND ripped them out of the style MID-GESTURE the moment
    // a pan dropped followMe. That style churn is a panning-jank regression; motion arms it instantly,
    // stillness disarms after 2 min so stoplights/parking don't churn sources either.
    var speedOverlayArmed by remember { mutableStateOf(false) }
    val movingNow = state.navigating || (state.mySpeed ?: 0f) > 3f
    LaunchedEffect(movingNow) {
        if (movingNow) speedOverlayArmed = true
        else if (speedOverlayArmed) {
            kotlinx.coroutines.delay(120_000)
            speedOverlayArmed = false
        }
    }
    // Expanded detent of the results bottom sheet, hoisted here so the BACK gesture can step it
    // one detent (expanded -> peek) before collapsing to the minimized bar (user 2026-07-09).
    var resultsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.results) { if (state.results.isEmpty()) resultsExpanded = false }
    // The results sheet minimized to its short bottom bar - the chrome shows again then, but
    // lifted above the bar so the FAB / scale bar / Search this area never sit on top of it.
    val resultsMinimized = state.results.isNotEmpty() && state.selected == null && !searchOpen && state.resultsCollapsed
    val chromeLift = if (resultsMinimized) 76.dp else 0.dp
    var metersPerPixel by remember { mutableStateOf(0.0) }
    // Measured screen-Y of the maneuver banner's bottom edge → so VelaMapView can sit the compass just below
    // it during nav (the banner's height varies with lane guidance + a "then" row, so it can't be guessed).
    var navBannerBottomPx by remember { mutableStateOf(0) }
    // The endpoints card's measured bottom edge, so the route fit can clear the card
    // instead of framing the start of the route exactly behind it.
    var topCardBottomPx by remember { mutableStateOf(0) }
    // In-nav search-along-route: the map search FAB arms a panel (text field + chips) above
    // the bar. Reset when nav ends so a stale-open panel can't greet the next drive.
    var navSearchOpen by remember { mutableStateOf(false) }
    var navSearchQuery by remember { mutableStateOf("") }
    // The open panel's measured bottom edge: the nav follow-camera pads the puck below it
    // so the panel can never cover the arrow (user 2026-07-15), on any screen height.
    var navPanelBottomPx by remember { mutableStateOf(0) }
    LaunchedEffect(state.navigating) {
        if (!state.navigating) { navSearchOpen = false; navSearchQuery = "" }
    }
    // Measured height of the nav BOTTOM bar (ETA + End) → everything stacked above it (speedometer,
    // speed-limit sign, re-center FAB, GPS-lost chip) offsets from the REAL height instead of a fixed
    // 132dp guess. The bar grows with the system font size, and at a larger font scale the fixed offset
    // left the speedo half-covered by the bar (GitHub issue #2). Falls back to the old constant until
    // the first layout pass measures it.
    var navBarHeightPx by remember { mutableStateOf(0) }
    val navBarClearance = with(LocalDensity.current) {
        // bar height + its 16dp bottom padding + a 16dp gap - reproduces the old 132dp at default font scale
        if (navBarHeightPx > 0) navBarHeightPx.toDp() + 32.dp else 132.dp
    }
    val focusManager = LocalFocusManager.current

    // Back peels one layer at a time - steps → navigation → route preview →
    // place sheet → results list - so it behaves like Google Maps instead of
    // dropping straight out of the app. Only the bare map (or collapsed pins,
    // which a back already peeled down to) lets the system handle back and exit.
    // ONE back handler (docs/dpad.md): folding the D-pad "disengage map" case in here
    // (rather than a second BackHandler) keeps a single, well-ordered precedence - a
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
            state.editingStops -> vm.closeStopsEditor()
            state.showSteps -> vm.closeSteps()
            // In-nav search: BACK peels the results list / the chip row before it can end the
            // whole drive - ending nav because you browsed gas stations would be brutal.
            state.navigating && state.results.isNotEmpty() -> vm.clearSearch()
            state.navigating && navSearchOpen -> { navSearchOpen = false; focusManager.clearFocus() }
            state.navigating -> vm.stopNav()
            state.directionsOpen || state.activeRoute != null || state.routes.isNotEmpty() ||
                state.transit.isNotEmpty() || state.transitLoading -> vm.clearRoute()
            state.selected != null -> vm.clearSelection()
            // Results sheet: back steps ONE detent (expanded -> peek -> minimized -> cleared),
            // never straight out of the app (a back on the minimized bar must not exit Vela).
            resultsExpanded -> resultsExpanded = false
            !state.resultsCollapsed -> vm.collapseResults()
            else -> vm.clearSearch()
        }
    }
    // The map target is the focus surface ONLY when the map is primary - hidden while any
    // list/sheet/panel/search owns the screen (those own focus; a crosshair over them stole
    // DOWN traversal into their rows). Nav keeps the map primary (the banner is an overlay).
    // EXCEPTION: "Choose on map" (pickOnMap) - the crosshair pick REQUIRES the map be
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
    // The search bar is the landing focus, not the engaged map (which would force a BACK press to
    // move). Compose won't let us programmatically pre-place focus on the
    // SEARCH BAR on the app's opening screen (verified ~13 ways: requestFocus no-ops with no prior
    // focus; moveFocus lands only on the centre map target; moveFocus(Up)/Enter and synthetic
    // KeyEvents don't take), so instead nothing is focused on open and the user's first arrow
    // lands on the search bar - Compose's real-first-key initial focus picks the first focusable,
    // which IS the search bar (measured). Net: no map engage, no BACK, one arrow reaches search.
    // This is the ONE screen that intentionally opens un-focused (the map is ambient; the first key
    // isn't wasted - it goes straight to search). (Was: auto-engage the map - user report 2026-07-08.)
    // Toggling the Flock layer in Settings refetches for the current view right away (otherwise the
    // cameras wouldn't appear until the next pan). Clears the layer when turned off.
    LaunchedEffect(app.vela.ui.Flock.on.value) { vm.refreshFlockNow() }

    // Choose-on-map (pickOnMap) is the EXCEPTION to the exception: there the whole task IS moving the
    // map to place a pin, so we DO auto-focus + engage the map target the moment pick mode opens, so
    // arrows pan immediately and OK confirms (the crosshair/pill are suppressed in pick mode because
    // the ChooseOnMapOverlay draws the pin + "Move the map" banner). This is entered mid-session (from
    // the search entry), so focus already exists and requestFocus lands - unlike the cold-open bare map.
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

    // Voice search has TWO paths (see VoiceSearch): tier-1 records + transcribes on-device with Vela's
    // own Whisper model (needs RECORD_AUDIO, asked at point of use); tier-2 hands off to an installed
    // voice-input app via the RECOGNIZE_SPEECH intent (that app records, so no mic permission). Which
    // runs is the engine pref, resolved against what's actually available; NONE hides the mic.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                // Providers write prose too ("Okay."): strip the trailing sentence punctuation the
                // same way the on-device path does, so no voice query searches with a period.
                ?.trim()?.trimEnd('.', '!', '?', ',', ';', ':')?.trim()
            if (!heard.isNullOrEmpty()) {
                focusManager.clearFocus()
                vm.onQueryChange(heard)
                vm.search()
            }
        }
    }
    // Tier-1 capture state: the listening dialog, live loudness, an early-stop flag (Done) and an
    // abort flag (Back/cancel -> don't apply the partial transcript).
    val voiceScope = rememberCoroutineScope()
    var voiceListening by remember { mutableStateOf(false) }
    var voiceStarted by remember { mutableStateOf(false) }
    var voiceLevel by remember { mutableStateOf(0f) }
    var voiceStop by remember { mutableStateOf(false) }
    var voiceAbort by remember { mutableStateOf(false) }
    fun startLocalVoice() {
        voiceStop = false; voiceAbort = false; voiceStarted = false; voiceLevel = 0f; voiceListening = true
        voiceScope.launch {
            val text = vm.voiceListen(
                onLevel = { voiceLevel = it },
                onListening = { voiceStarted = true },
                cancelled = { voiceStop },
            )
            voiceListening = false
            if (!voiceAbort && !text.isNullOrBlank()) {
                focusManager.clearFocus()
                vm.applyVoiceQuery(text)
            }
        }
    }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startLocalVoice() }

    val voiceProviderAvailable = remember { app.vela.ui.VoiceSearch.hasProvider(context) }
    val voicePrompt = stringResource(R.string.search_voice_prompt)
    // Reactive resolve (mirrors VoiceSearch.resolvedMode but keyed on vm state so the mic reflects a
    // just-downloaded model without a relaunch): enabled/engine are mutableState, local rides state.
    val micMode = when {
        !app.vela.ui.VoiceSearch.enabled.value -> app.vela.ui.VoiceSearch.Mode.NONE
        app.vela.ui.VoiceSearch.engine.value == app.vela.ui.VoiceSearch.Engine.LOCAL ->
            if (state.asrInstalled) app.vela.ui.VoiceSearch.Mode.LOCAL else app.vela.ui.VoiceSearch.Mode.NONE
        app.vela.ui.VoiceSearch.engine.value == app.vela.ui.VoiceSearch.Engine.SYSTEM ->
            if (voiceProviderAvailable) app.vela.ui.VoiceSearch.Mode.SYSTEM else app.vela.ui.VoiceSearch.Mode.NONE
        state.asrInstalled -> app.vela.ui.VoiceSearch.Mode.LOCAL       // Auto: on-device wins
        voiceProviderAvailable -> app.vela.ui.VoiceSearch.Mode.SYSTEM
        else -> app.vela.ui.VoiceSearch.Mode.NONE
    }
    // With nothing installed the mic still shows (when the toggle is on) and tapping it OFFERS the
    // Vela voice download - a hidden mic made the whole feature undiscoverable on a fresh install.
    var showAsrOffer by remember { mutableStateOf(false) }
    val onMic: (() -> Unit)? = if (app.vela.ui.VoiceSearch.enabled.value) {
        {
            when (micMode) {
                app.vela.ui.VoiceSearch.Mode.NONE -> showAsrOffer = true
                app.vela.ui.VoiceSearch.Mode.SYSTEM -> {
                    val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        // Pin the voice app the user picked in Settings; with no pick, defer to
                        // Android's own default app, and only pin the first installed one when
                        // Android has no default either (else its chooser interrupts dictation).
                        app.vela.ui.VoiceSearch.launchComponent(context)?.let { component = it }
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, app.vela.ui.AppLocale.effective().toLanguageTag())
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                    }
                    // The resolver check can go stale (provider uninstalled since launch); catch so a
                    // tap can never crash - it just does nothing.
                    runCatching { voiceLauncher.launch(intent) }
                }
                app.vela.ui.VoiceSearch.Mode.LOCAL ->
                    if (vm.voiceMicGranted()) startLocalVoice()
                    else recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    } else {
        null
    }
    if (showAsrOffer) {
        app.vela.ui.VelaDialog(
            onDismissRequest = { showAsrOffer = false },
            title = stringResource(R.string.map_asr_offer_title),
            confirmText = stringResource(R.string.settings_voice_search_download, app.vela.voice.AsrModel.SIZE_MB),
            onConfirm = { showAsrOffer = false; vm.downloadAsrModel() },
            dismissText = stringResource(R.string.root_not_now),
            onDismiss = { showAsrOffer = false },
            text = { Text(stringResource(R.string.map_asr_offer_body)) },
        )
    }
    // Reflect whether the on-device model is present, so the mic + Settings update without a relaunch.
    LaunchedEffect(Unit) { vm.refreshAsr() }
    if (voiceListening) {
        app.vela.ui.VoiceCaptureDialog(
            level = voiceLevel,
            listening = voiceStarted,
            onDone = { voiceStop = true },                    // stop early, keep + search what was heard
            onCancel = { voiceAbort = true; voiceStop = true }, // Back/outside: abort, don't apply
        )
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
            cameraTargetZoom = state.centerZoom,
            recenterTick = state.recenterTick,
            cameraBottomInsetPx = cameraBottomInset,
            // The endpoints card's measured bottom edge: the route fit frames start/end in the
            // strip between the card and the chooser instead of hiding either behind chrome.
            cameraTopInsetPx = if (state.directionsOpen && !state.navigating) topCardBottomPx else 0,
            navTopOverlayPx = if (state.navigating && navSearchOpen && state.results.isEmpty()) navPanelBottomPx else 0,
            routePolyline = state.activeRoute?.polyline ?: emptyList(),
            routeColor = routeTrafficColor(state.activeRoute),
            routeDashed = state.travelMode == app.vela.core.model.TravelMode.WALK ||
                state.travelMode == app.vela.core.model.TravelMode.BICYCLE,
            routeTrafficSpans = routeTrafficSpans(state.activeRoute),
            // Greyed, tappable alternates (Google-style) - only off-nav, with a chooser up.
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
            // Numbered stop pins while the trip UI is active (chooser, editor or the drive itself).
            stopPins = if (state.directionsOpen || state.navigating) state.directionsWaypoints.map { it.location } else emptyList(),
            navMode = state.navigating,
            navFollowing = !state.navCameraDetached,
            driveFollowing = driveFollowing,
            onMapTap = {
                // Tapping the map with the along-route panel up dismisses it, same as a pan
                // (upstream b7eb0777).
                if (navSearchOpen) { navSearchOpen = false; focusManager.clearFocus() }
            },
            onUserPan = {
                // Grabbing the map is an explicit "let me look around" - stop tracking until the
                // locate tap re-arms it (Google drops follow the moment you pan).
                followMe = false
                // Grabbing the map also dismisses the along-route search panel (upstream aaa13d5d).
                if (navSearchOpen) { navSearchOpen = false; focusManager.clearFocus() }
            },
            parkingSpot = state.parkingSpot,
            onParkingTap = { vm.showParkedCar(context.getString(R.string.map_parked_car)) },
            svPose = svPose,
            svTopInsetPx = (screenHeightPx * 0.55f).toInt(),
            onSvMapTap = vm::moveStreetViewTo,
            onNavPanned = {
                vm.onNavPanned()
                // The fork routes NAV pans here (browse pans go through onUserPan), so the
                // along-route panel's grab-to-dismiss must live in this path too.
                if (navSearchOpen) { navSearchOpen = false; focusManager.clearFocus() }
            },
            onScaleChanged = { metersPerPixel = it },
            darkTheme = darkTheme,
            applyKeylessTheme = !hasMapTiler,
            // Off-nav: the whole-map raster when the user toggles it on. During nav we
            // DON'T wash the whole map - the user asked for traffic on "just the road
            // we're on, not all of it", so the route line itself is coloured per-segment
            // from the directions traffic spans (VelaMapView.routeGradientStops /
            // DirectionsParser.parseTrafficSpans); the whole-map overlay stays off unless
            // the user explicitly enables it in Settings → Map.
            trafficOn = Traffic.on.value,
            transitOn = app.vela.ui.TransitLayer.on.value,
            satelliteOn = app.vela.ui.SatelliteLayer.on.value,
            topographyOn = app.vela.ui.Topography.on.value,
            previewTarget = state.previewStepIndex?.let { state.activeRoute?.maneuvers?.getOrNull(it)?.location },
            onPoiTap = vm::onPoiTap,
            onMarkerTap = { i -> displayedPlaces(state).getOrNull(i)?.let(vm::selectPlace) },
            ambientPois = ambientMarkersOf(state),
            buildingOverlays = state.buildingOverlays,
            addressOverlays = state.addressOverlays,
            maxspeedOverlays = state.maxspeedOverlays,
            onRoadLimitKmh = vm::onOverlayRoadLimit,
            speedOverlayOn = speedOverlayArmed, // motion-armed with hysteresis - NEVER on the parked browse map
            trafficControls = state.trafficControls,
            flockCameras = state.flockCameras,
            // Hide the tapped stop's own badge while it is selected - the red selected-place pin
            // drops at the same coordinate and the two bus glyphs stacked read as a glitch
            // (user 2026-07-13). Structural list equality keeps the identity gate quiet.
            transitStops = state.transitStops.filterNot { st -> state.selected?.id == "gtfs:${st.stopId}" },
            onTransitStopTap = vm::onTransitStopTap,
            navBannerBottomPx = if (state.navigating) navBannerBottomPx else 0,
            // Index into the SHOWN list (the same one ambientMarkersOf uploads), not the raw
            // pool - while a place is open the shown list drops the selected place's copy, so
            // raw-pool indices would be off by one past it.
            onAmbientTap = { i -> ambientShownOf(state).getOrNull(i)?.let(vm::selectPlace) },
            onCameraIdle = vm::onCameraIdle,
            onMapLongPress = vm::onMapLongPress,
            onAddressLabelTap = vm::onAddressLabelTap,
            onViewport = vm::onViewport,
            dpadController = mapDpad,
            modifier = Modifier.fillMaxSize(),
        )

        // --- D-pad map target (docs/dpad.md) -------------------------------
        // TWO-STAGE so the chrome stays reachable (v1 trapped focus on the map):
        //  · FOCUSED (ring + "OK" pill): a normal focus stop - arrows traverse to the
        //    search bar / chips / zoom buttons / FABs like any other element; OK engages.
        //  · ENGAGED (crosshair + edge ring): arrows pan, OK "taps" the crosshair (or
        //    confirms a Choose-on-map pick), holding OK long-presses (pin / direct pick),
        //    +/−/zoom keys zoom, BACK disengages (focus stays on the target).
        // Shown only when the MAP is the primary surface - with a list/sheet/panel open the
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
                    // In Choose-on-map mode draw NOTHING here - the ChooseOnMapOverlay
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
        // The along-route panel REPLACES the banner while open (Google covers nav chrome during
        // in-nav search too): on a 320dp-tall screen banner + panel + bar left no strip at all
        // for the position arrow, and search is a transient the next tap dismisses. NB nav-time
        // must NEVER fall into the else branch below - it composes the browse search column
        // (endpoints card + search bar), which leaked over the drive when the banner first hid.
        if (state.navigating && navSearchOpen && state.results.isEmpty()) {
            // The panel (rendered later in this Box) owns the top slot; no banner, no search chrome.
        } else if (state.navigating) {
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
                // distanceMeters is the travel AFTER it (Route.kt convention) - showing it here
                // put the leg-after on the previewed step's headline ("3.1 mi - Turn right onto
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
                // the travel AFTER it, to the next maneuver - both OSRM and the Google parser use that
                // convention). Passing next.distanceMeters was the next→next-next gap: it made "then
                // Arrive" (ARRIVE has 0 after it) show permanently while approaching the final turn, and
                // suppressed true exit-then-merge compounds whose merge had a long following leg.
                nextDistanceMeters = shown?.distanceMeters,
                destName = state.arrivedLabel,
                destAddress = state.navDestAddress,
                // Speed-scaled approach gate for lanes + the "then" row: identity at city speeds
                // (≤ ~60 mph), ~1 km ≈ 30 s at highway speed - Google's cadence.
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
                    // Directions mode: the search bar swaps for the Google-style endpoints card
                    // (origin / stops / destination, swap, back) - the rows that used to sit in
                    // the bottom chooser. Hidden while the search overlay is up (picking an
                    // origin/stop runs the normal search page) and while the steps preview or
                    // stops editor own the screen.
                    if (state.directionsOpen && !searchOpen && state.pickOnMap == null && !state.showSteps && !state.editingStops) {
                        app.vela.ui.place.RouteTopCard(
                            modifier = Modifier.onGloballyPositioned {
                                topCardBottomPx = (it.positionInRoot().y + it.size.height).roundToInt()
                            },
                            originName = if (state.directionsReversed) (state.selected?.name ?: stringResource(R.string.mapscreen_place))
                            else (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location)),
                            originIsMe = !state.directionsReversed && state.directionsOrigin == null,
                            destinationName = if (state.directionsReversed) (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location))
                            else (state.selected?.name ?: stringResource(R.string.mapscreen_destination)),
                            stops = state.directionsWaypoints.map { it.name },
                            showStopControls = state.travelMode != app.vela.core.model.TravelMode.TRANSIT,
                            onEditOrigin = if (state.directionsReversed) null else vm::beginPickOrigin,
                            onEditDestination = if (state.directionsReversed) vm::beginPickOrigin else null,
                            onEditStops = vm::openStopsEditor,
                            onAddStop = vm::openStopsEditor,
                            onSwap = vm::swapDirections,
                            onClose = vm::clearRoute,
                        )
                    }
                    // The search bar hides while the endpoints card above replaces it in
                    // directions mode.
                    if (!(state.directionsOpen && !searchOpen)) {
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
                            // D-pad, blur must NOT close (focus walks the rows) - BACK /
                            // a run search / a pick close it instead.
                            if (it) searchExpanded = true else if (!dpadMode) searchExpanded = false
                        },
                        onBack = if (searchOpen) ({ searchExpanded = false; focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickStop() }) else null,
                        dpadMode = dpadMode,
                        offline = state.offline,
                        onMic = onMic,
                    )
                    }
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
                    // bare map - the same state the chips show in - so it never trails a results list.
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

        // The along-route search panel lives at the TOP, under the turn banner where the
        // heads-up cards go (upstream b7eb0777): one stable position - the keyboard can never
        // cover it (no focus-driven move), and it can't collide with the FAB stack or the
        // bottom bar. Transient heads-up cards may draw over it; they're rare and short-lived.
        if (state.navigating && navSearchOpen && state.results.isEmpty()) {
            app.vela.ui.nav.NavSearchChips(
                query = navSearchQuery,
                onQueryChange = { navSearchQuery = it },
                onPick = { q ->
                    navSearchOpen = false
                    navSearchQuery = ""
                    focusManager.clearFocus()
                    vm.searchAlongRoute(q)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                    .onGloballyPositioned { navPanelBottomPx = (it.positionInRoot().y + it.size.height).roundToInt() },
            )
        }

        // Right-edge nav FAB stack: volume + search live ON THE MAP (the bottom bar was
        // cramming four controls - user 2026-07-14; Google floats these there too), with the
        // re-center button joining the stack when panned away / previewing a step. Hidden
        // while the along-route results own the bottom slot.
        if (state.navigating && state.results.isEmpty() && !navSearchOpen) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = navBarClearance),
            ) {
                if (state.navCameraDetached || state.previewStepIndex != null) {
                    FloatingActionButton(
                        onClick = vm::recenterNav,
                        modifier = Modifier.dpadHighlight(RoundedCornerShape(16.dp)),
                    ) { Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.mapscreen_recenter)) }
                }
                FloatingActionButton(
                    onClick = {
                        navSearchOpen = !navSearchOpen
                        if (!navSearchOpen) focusManager.clearFocus()
                    },
                    modifier = Modifier.dpadHighlight(RoundedCornerShape(16.dp)),
                ) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.place_search_along_route)) }
            }
        }

        // "Searching for GPS" chip - the banner distance/ETA freeze silently on signal loss
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

        // Unified speed readout (Google-style), bottom-left while MOVING - during nav AND during a
        // free-drive (browsing without a route, moving on the clean map). Always shows the current
        // speed; the posted limit (on-device graph maxspeed, else the streamed "Speed B" overlay)
        // tucks into the SAME box when known, and it still reads clean as just a speed when no limit
        // is available. Over the limit -> amber. In free-drive the scale bar hides (below) so the box
        // owns the corner, like Google's driving view.
        val movingFree = !state.navigating && (state.mySpeed ?: 0f) > 3f &&
            !searchOpen && state.selected == null && !state.directionsOpen && !state.showSteps && !resultsShown
        val postedLimitKmh = state.speedLimitKmh ?: state.speedLimitOverlayKmh
        // Hidden while the along-route panel is up: the panel occupies the same band on short
        // screens and the two stacked over each other (user 2026-07-15); it returns on dismiss.
        val navPanelUp = state.navigating && navSearchOpen && state.results.isEmpty()
        if (((state.navigating && state.mySpeed != null) || movingFree) && !navPanelUp) {
            SpeedWidget(
                speedMps = state.mySpeed,
                limitKmh = postedLimitKmh,
                imperial = Units.imperial.value,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = navBarClearance),
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

        // Resume-navigation prompt - a drive was cut off by a process-kill (GrapheneOS reaping the
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

            // While an in-nav search has results, the results branch below takes the bottom
            // slot (Google's in-nav list does the same); clearing it brings the bar back.
            state.navigating && state.results.isEmpty() -> Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NavControls(
                    remainingDistanceMeters = state.nav.remainingDistance,
                    remainingSeconds = state.nav.remainingDuration,
                    offRoute = state.nav.offRoute,
                    onStop = vm::stopNav,
                    onSteps = vm::openSteps,
                    voiceMuted = state.voiceMuted,
                    onToggleVoice = vm::toggleVoice,
                    trafficRatio = state.activeRoute?.trafficRatio,
                    // Measured AFTER the padding → the bar surface itself; navBarClearance adds the
                    // padding + gap back. Everything stacked above the bar keys off this.
                    modifier = Modifier.onGloballyPositioned { navBarHeightPx = it.size.height },
                )
            }

            // The dedicated stops editor covers the directions panel while open (drag to
            // reorder, remove, add; one reroute on Done).
            state.editingStops && state.directionsOpen && !searchOpen && state.pickOnMap == null -> app.vela.ui.place.StopsEditorSheet(
                originName = if (state.directionsReversed) (state.selected?.name ?: stringResource(R.string.mapscreen_place))
                else (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location)),
                destinationName = if (state.directionsReversed) (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location))
                else (state.selected?.name ?: stringResource(R.string.mapscreen_destination)),
                stops = state.directionsWaypoints,
                onApply = vm::applyStops,
                onAddStop = vm::beginPickStop,
                onDismiss = vm::closeStopsEditor,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Tapping "Directions" opens a dedicated panel (popup) - mode tabs, the
            // route option(s) with traffic-aware ETAs, selectable alternates, Start -
            // instead of burying it at the bottom of the place sheet.
            // Hidden while the search overlay is up (e.g. picking a custom origin) so
            // the panel doesn't render over it.
            state.directionsOpen && !searchOpen && state.pickOnMap == null -> DirectionsPanel(
                flockOnRoute = state.flockOnRoute,
                destinationName = if (state.directionsReversed) (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location))
                else (state.selected?.name ?: stringResource(R.string.mapscreen_destination)),
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
                onTimeSelected = vm::setDirectionsTime,
                onCollapsedChange = { dirMinimized = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // The place sheet yields while Street View is up - the pano takes the top half and the
            // bottom half must stay pure map (the pose cone), not a sheet.
            state.selected != null && !searchOpen && state.pickOnMap == null &&
                state.streetView == null && !state.streetViewLoading -> PlaceSheet(
                place = state.selected!!,
                isSaved = state.saved.any { it.id == state.selected!!.id },
                reviews = state.reviews,
                reviewsLoading = state.reviewsLoading,
                reviewsFound = state.reviewsFound,
                photosLoading = state.photosLoading,
                detailsLoading = state.loadingDetails,
                // Board renders ONLY on the place it was fetched for (upstream d4f68a03).
                stopDepartures = state.stopDepartures
                    ?.takeIf { state.stopDeparturesFor != null && state.stopDeparturesFor == state.selected?.id },
                stopDeparturesLoading = state.stopDeparturesLoading &&
                    state.stopDeparturesFor != null && state.stopDeparturesFor == state.selected?.id,
                onTapRoute = vm::openRouteDetail,
                placesHere = state.placesHere,
                onClose = vm::clearSelection,
                onToggleSave = vm::toggleSave,
                onDirections = vm::routeToSelected,
                onStreetView = { vm.openStreetView(state.selected!!) },
                onOpenPlace = vm::selectPlace,
                onOpenSimilar = vm::openSimilar,
                onSetShortcut = vm::setSelectedAsShortcut,
                onRetryReviews = vm::retryReviews,
                onClearParking = {
                    vm.clearParkingSpot()
                    vm.clearSelection()
                    Toast.makeText(context, context.getString(R.string.map_parking_cleared), Toast.LENGTH_SHORT).show()
                },
                // No navigationBarsPadding here: the sheet's background should reach
                // the screen bottom (no map peeking through under the nav bar); the
                // sheet pads its own content for the nav bar instead.
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Search results as a BOTTOM sheet, Google-style - same detent family as the place
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

        // Full-screen transit step-by-step guidance (Moovit-style) - covers everything while active.
        state.transitNav?.let { tn ->
            app.vela.ui.place.TransitNavSheet(
                nav = tn,
                onNext = vm::advanceTransitNav,
                onBack = vm::backTransitNav,
                onEnd = vm::endTransitNav,
                onWalkDirections = vm::walkDirections,
            )
        }

        // In-app Street View (keyless pano tiles on a GL sphere). HALF-SCREEN over the map by
        // default (Google-style: the map underneath shows a rotating view cone at the pano and
        // eases along as you walk), with a corner button to go full screen.
        if (state.streetView != null || state.streetViewLoading) {
            app.vela.ui.place.StreetViewScreen(
                pano = state.streetView,
                bitmap = state.streetViewBitmap,
                loading = state.streetViewLoading,
                shownYear = state.streetViewShownYear,
                shownMonth = state.streetViewShownMonth,
                historical = state.streetViewHistorical,
                onClose = vm::closeStreetView,
                onMove = vm::moveStreetView,
                onTimeTravel = vm::timeTravelStreetView,
                onPose = { la, ln, yaw -> svPose = doubleArrayOf(la, ln, yaw.toDouble()) },
            )
        }

        // "Choose on map" crosshair - the map is visible; a fixed pin marks screen centre. Move the
        // map under it (or long-press) and Confirm to set the start/stop from that point (Google-style).
        // Full-screen tap-through route timeline off a stop's departure board.
        if (state.routeDetail != null || state.routeDetailLoading) {
            app.vela.ui.place.RouteDetailSheet(
                step = state.routeDetail,
                title = state.routeDetailTitle,
                loading = state.routeDetailLoading,
                onClose = vm::closeRouteDetail,
                onStopTap = vm::openRouteStop,
            )
        }

        state.pickOnMap?.let { target ->
            ChooseOnMapOverlay(
                target = target,
                onConfirm = vm::confirmMapPick,
                onCancel = vm::cancelChooseOnMap,
            )
        }

        // D-pad zoom buttons (docs/dpad.md): pinch has no key equivalent, so give zoom a
        // first-class on-screen control while the UI is key-driven. Shown ONLY while
        // browsing the map with no list/sheet/panel over it - the mid-right buttons sit in
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
        // (Settings → Simulate driving) is meant to look like real nav - its own "End" button stops
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
                onClick = { followMe = true; vm.recenter() }, // locate tap = "track me again" (re-arms free-drive follow)
                modifier = Modifier
                    .dpadHighlight(RoundedCornerShape(16.dp))
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .padding(bottom = chromeLift),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.mapscreen_center_on_my_location))
            }
            // Parking button, its OWN control above the locate FAB. TAP with NO spot -> save here
            // (the one-tap "I parked" path). TAP with a spot set (teal) -> a small hub menu (Find my
            // car / Move parking here / Earlier spots / Clear) so re-parking is one obvious choice
            // instead of a clear-then-tap-again dance. LONG-PRESS jumps straight to history. D-pad:
            // the box is focusable with a ring; OK does what a tap does; the hub is a VelaMenu
            // (auto-focusing under D-pad - a bare DropdownMenu cannot be, docs/dpad.md).
            val parkingSavedMsg = stringResource(R.string.map_parking_saved)
            val parkingNoFixMsg = stringResource(R.string.map_parking_no_fix)
            val parkingMovedMsg = stringResource(R.string.map_parking_moved)
            val parkingClearedMsg = stringResource(R.string.map_parking_cleared)
            val parkedCarLabel = stringResource(R.string.map_parked_car)
            val parkingSet = state.parkingSpot != null
            var showParkingHistory by remember { mutableStateOf(false) }
            var showParkingMenu by remember { mutableStateOf(false) }
            val parkingTapAction = {
                if (parkingSet) {
                    showParkingMenu = true
                } else {
                    val msg = if (vm.saveParkingSpot()) parkingSavedMsg else parkingNoFixMsg
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (parkingSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (parkingSet) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = chromeLift + 92.dp)
                    .dpadHighlight(RoundedCornerShape(12.dp)),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .onKeyEvent { ev ->
                            if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                                parkingTapAction(); true
                            } else {
                                false
                            }
                        }
                        .focusable()
                        .pointerInput(parkingSet, state.parkingHistory.size) {
                            detectTapGestures(
                                onTap = { parkingTapAction() },
                                onLongPress = {
                                    if (state.parkingHistory.isNotEmpty()) showParkingHistory = true
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.LocalParking,
                        contentDescription = stringResource(
                            if (parkingSet) R.string.map_parked_car else R.string.map_parking_save,
                        ),
                    )
                    // The parking hub, anchored to the button. Only reachable when a spot is set.
                    app.vela.ui.VelaMenu(expanded = showParkingMenu, onDismissRequest = { showParkingMenu = false }) {
                        item(stringResource(R.string.map_parking_find), Icons.Default.DirectionsCar) { showParkingMenu = false; vm.showParkedCar(parkedCarLabel) }
                        // "Move parking here" overwrites the current spot with your live fix; the old
                        // one is not lost - saveParkingSpot archives it to history. Hidden with no fix.
                        if (state.myLocation != null) {
                            item(stringResource(R.string.map_parking_move_here), Icons.Default.MyLocation) {
                                showParkingMenu = false
                                val msg = if (vm.saveParkingSpot()) parkingMovedMsg else parkingNoFixMsg
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (state.parkingHistory.size > 1) {
                            item(stringResource(R.string.map_parking_earlier), Icons.Default.History) { showParkingMenu = false; showParkingHistory = true }
                        }
                        item(stringResource(R.string.map_parking_clear), Icons.Default.Delete) {
                            showParkingMenu = false
                            vm.clearParkingSpot()
                            Toast.makeText(context, parkingClearedMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            if (showParkingHistory) {
                ParkingHistorySheet(
                    history = state.parkingHistory,
                    currentAtMillis = state.parkedAtMillis,
                    onRestore = { vm.restoreParkingFromHistory(it); showParkingHistory = false },
                    onDelete = { vm.deleteParkingHistoryEntry(it) },
                    onClearAll = { vm.clearParkingHistory(); showParkingHistory = false },
                    onDismiss = { showParkingHistory = false },
                )
            }
            // (The live-traffic overlay toggle lives in Settings → Map - it's a
            // niche browse-only layer, and nav shows per-segment route traffic,
            // so it doesn't belong on the map.)
            // Scale bar, bottom-left just past the attribution ⓘ. Hidden only while ACTUALLY
            // free-driving (moving, speed box on screen) - `!driveFollowing` alone hid it on the
            // whole browse map, since follow is armed by default.
            // Layers button, top-right under the search bar + chips (browse map only): satellite,
            // traffic, transit and terrain in one Google-style panel. Filled tint = any layer on.
            if (app.vela.ui.LayersButton.on.value && state.selected == null && !searchOpen &&
                !state.navigating && !state.replaying && state.results.isEmpty()
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val anyLayerOn = app.vela.ui.SatelliteLayer.on.value || Traffic.on.value ||
                    app.vela.ui.TransitLayer.on.value || app.vela.ui.Topography.on.value
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 128.dp, end = 14.dp),
                ) {
                    Surface(
                        color = SheetPalette.bg(darkTheme).copy(alpha = 0.9f),
                        shape = CircleShape,
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(42.dp),
                    ) {
                        IconButton(onClick = { layersOpen = true }) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = stringResource(R.string.map_layers),
                                tint = if (anyLayerOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(2.dp),
                            )
                        }
                    }
                    // The layers panel, Google-style: SATELLITE swaps the base look, the rest are
                    // overlays. Same holders Settings flips, so the two stay in sync. VelaMenu, not
                    // a bare DropdownMenu - the D-pad rule (docs/dpad.md): a Popup can't be
                    // pre-focused, so key-first devices get the auto-focusing chooser dialog.
                    app.vela.ui.VelaMenu(
                        expanded = layersOpen,
                        onDismissRequest = { layersOpen = false },
                    ) {
                        toggleItem(stringResource(R.string.map_satellite_toggle), app.vela.ui.SatelliteLayer.on.value) {
                            app.vela.ui.SatelliteLayer.set(ctx, it)
                            vm.onSatelliteToggled()
                        }
                        toggleItem(stringResource(R.string.settings_live_traffic), Traffic.on.value) {
                            Traffic.set(ctx, it)
                        }
                        toggleItem(stringResource(R.string.settings_transit_layer), app.vela.ui.TransitLayer.on.value) {
                            app.vela.ui.TransitLayer.set(ctx, it)
                        }
                        toggleItem(stringResource(R.string.settings_topography), app.vela.ui.Topography.on.value) {
                            app.vela.ui.Topography.set(ctx, it)
                        }
                    }
                }
            }
            // Required Esri attribution while the imagery is on - bottom center, clear of the
            // scale bar and FABs, the year centered on its own line under the credit.
            if (app.vela.ui.SatelliteLayer.on.value) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp + chromeLift),
                ) {
                    Text(
                        stringResource(R.string.map_satellite_attribution),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (darkTheme) Color(0xFFB8C2CC) else Color(0xFF4A4A4A),
                    )
                    state.imageryYear?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (darkTheme) Color(0xFFB8C2CC) else Color(0xFF4A4A4A),
                        )
                    }
                }
            }
            if (!(driveFollowing && speedOverlayArmed)) {
                ScaleBar(
                    metersPerPixel = metersPerPixel,
                    dark = darkTheme,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 46.dp, bottom = 16.dp + chromeLift),
                )
            }
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
                    .then(
                        // In directions mode the endpoints card is taller than the search bar the
                        // 96dp constant was tuned for, so heads-up cards printed over it - sit
                        // them under the measured card instead (upstream cc691c0f).
                        if (state.directionsOpen && !searchOpen && topCardBottomPx > 0) {
                            Modifier.padding(
                                top = with(LocalDensity.current) { topCardBottomPx.toDp() } + 10.dp,
                                start = 12.dp, end = 12.dp,
                            )
                        } else {
                            Modifier.statusBarsPadding().padding(top = 96.dp, start = 12.dp, end = 12.dp)
                        },
                    ),
                // A voice problem carries its fix. Normally a pill straight to Vela's voice library;
                // for a language with no Vela voice (Japanese, Hebrew) it opens the phone's own voice
                // settings instead, where the user can add a system voice.
                pillLabel = when {
                    state.statusOpensTtsSettings -> stringResource(R.string.mapscreen_system_voices)
                    state.statusVoiceAction -> stringResource(R.string.mapscreen_get_voice)
                    else -> null
                },
                onPill = when {
                    state.statusOpensTtsSettings -> {
                        {
                            vm.clearStatus()
                            runCatching {
                                context.startActivity(
                                    Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }
                    }
                    state.statusVoiceAction -> { { vm.clearStatus(); onOpenSettings() } }
                    else -> null
                },
            )
        }
        // Pushed notices (signed calibration channel) + the voice-download progress card - on the
        // bare map only, so they don't cover the nav banner / search / a place sheet. The download
        // card makes the ONBOARDING one-tap voice install visible, rather than running invisibly
        // with progress only in Settings.
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
                // A region (state/country) download: the routing graph first, then its place pack -
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
 * live traffic-aware time runs meaningfully over the typical time. Walk/bike and
 * traffic-less routes stay the default blue. */
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
 * converting Google's metre offsets to fractions of the route length - drives the
 * route line's per-segment colour (Google-style). Empty when there's no live data. */
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
 * the right [Place]). Search results win; else the opened place; else the ambient Google POIs
 * shown on the bare browse map. Dead POIs are dropped from the pins (Google-style). */
private fun displayedPlaces(state: MapUiState): List<Place> = when {
    state.results.isNotEmpty() -> state.results.filterNot { it.permanentlyClosed }
    state.selected != null -> listOf(state.selected)
    else -> emptyList() // ambient Google POIs render as category dots (their own layer), not pins
}

/** The ambient Google POIs actually shown: the bare browse map AND while a single place sheet
 * is open. Wiping them on select emptied the whole ambient source, so tapping a POI made every
 * icon around it vanish and closing the sheet re-placed the entire layer (upstream e3992d88) -
 * Google keeps the area's icons up around an opened place. Only the opened place's own ambient
 * copy is dropped, so its icon and label don't double-draw under the red selection pin. Still
 * hidden while results / a route preview / nav / replay own the map. Used by BOTH the marker
 * upload and the tap handler, so the index a tap reports always indexes THIS list. */
private fun ambientShownOf(state: MapUiState): List<Place> =
    if (state.results.isEmpty() && !state.navigating && !state.replaying && state.activeRoute == null) {
        val sel = state.selected ?: return state.ambientPois
        state.ambientPois.filterNot {
            it.name.equals(sel.name, ignoreCase = true) && it.location.distanceTo(sel.location) < 150.0
        }
    } else {
        emptyList()
    }

private fun ambientMarkersOf(state: MapUiState): List<MapMarker> =
    ambientShownOf(state).map { MapMarker(it.name, it.location, it.category, app.vela.core.data.google.ambientProminence(it)) }

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
            // Fling phase closes every drag (even at zero velocity) - the gesture boundary
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
    // "Open now" falls back to the WEEKLY HOURS when Google sent no live status (openNow == null) -
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
                        androidx.compose.ui.res.pluralStringResource(R.plurals.mapscreen_results_count, shown.size, shown.size),
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
                // (a subtle tint when off, solid teal when on) so they read modern on the sheet -
                // the default outlined M3 chip looked "old" against the filled category chips
                // (user 2026-07-08). No border; a check icon marks an active toggle.
                // OPAQUE container colours: these are ELEVATED chips, and a translucent container
                // let the elevation SHADOW show through the pill - invisible on the dark sheet but
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
                } // if (!collapsed) - chips
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
            } // if (!collapsed) - list
        }
    }
}

@Composable
private fun CategoryChips(onPick: (String) -> Unit) {
    // (localized label, STABLE English search query, icon) - the query is the logic key sent to Google
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
                // Full pill, Google-style - the M3 default 8dp corners read dated on a map chip row.
                shape = androidx.compose.foundation.shape.CircleShape,
                // MONOCHROME glyphs (user 2026-07-06): the M3 default tints the leading icon with the
                // theme primary (teal), Google's chips are single-ink - icon matches the label colour.
                colors = androidx.compose.material3.AssistChipDefaults.elevatedAssistChipColors(
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/** "Choose on map" mode: a full-screen overlay over the live map with a centre crosshair, a hint
 * banner and a Confirm button. Empty areas carry no gesture modifiers, so map pan/zoom pass straight
 * through to the MapLibre view below; only the banner and button consume touches. */
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
 * opaque background while the search box is focused (Google-style). */
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
        // "Choose on map" - leave the search overlay and set this endpoint by moving a crosshair
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
        // Recently-opened places (pin icon) - one tap back to a place you just viewed.
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
 * a ⋮ menu (Change / Remove) when set. */
@Composable
private fun ShortcutRow(
    kind: ShortcutKind,
    place: SavedPlace?,
    onPick: (ShortcutKind) -> Unit,
    onAssign: (ShortcutKind) -> Unit,
    onClear: (ShortcutKind) -> Unit,
) {
    val icon = if (kind == ShortcutKind.HOME) Icons.Default.Home else Icons.Default.Work
    // Localized display label (the ShortcutKind.label enum value stays the stable "Home"/"Work" key).
    val label = stringResource(if (kind == ShortcutKind.HOME) R.string.shortcut_home else R.string.shortcut_work)
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
            Text(label, style = MaterialTheme.typography.bodyLarge, color = SheetPalette.ink(dark))
            Text(
                place?.name ?: stringResource(R.string.mapscreen_set_shortcut_address, label.lowercase()),
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
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.mapscreen_edit_shortcut, label))
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
            // Explicit colour: the search page is a plain background()-Box, not a Surface, so
            // LocalContentColor is NOT set for it - a colourless Text falls back to BLACK and
            // vanishes on the dark sheet. Same convention as SuggestionRow.
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.mapscreen_cancel)) }
    }
}

/** A slim banner while picking a place to add as a directions stop - without it the Add-stop
 * picker is visually identical to plain search (no hint you're in a mode, no way out but Back). */
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
            // Explicit colour, same reason as AssignBanner: no Surface on the search page means
            // no LocalContentColor - a colourless Text renders BLACK on the dark sheet.
            color = MaterialTheme.colorScheme.onSurface,
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
    pillLabel: String? = null,   // optional PRIMARY action (e.g. "Get a voice" / "System voices")
    onPill: (() -> Unit)? = null,
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
            if (pillLabel != null && onPill != null) {
                TextButton(onClick = onPill, modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp))) {
                    Text(pillLabel, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = onAction, modifier = Modifier.dpadHighlight(RoundedCornerShape(20.dp))) { Text(actionLabel) }
        }
    }
}

/** Voice-download progress over the map - makes the onboarding one-tap install visible (it used to
 * run with no surface outside Settings). Reads the SAME state the Settings row does, so it also
 * shows when a Settings-started download is still running after backing out to the map. The bar
 * includes the extract phase (KokoroInstaller maps untar into the tail), so it no longer parks at
 * ~98% while the archive unpacks. */
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

/** Progress card for a region (state/country) offline download - the routing graph, then the
 * region's place pack. Mirrors [VoiceDownloadCard] so a Settings-started download stays visible
 * on the map. */
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

/** A notice pushed through the signed calibration channel - level-tinted, with an
 * optional "Learn more" link and a per-id Dismiss. */
/** "A newer Vela is out" card (self-updater): download with progress, then the system
 * installer takes over. "Not now" silences this version until a newer one appears. */
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
                        app.vela.ui.ExternalLinks.open(context, url)
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
 * The Google-style speed display shown while moving (nav AND free-drive): TWO separate stacked
 * cards - the posted limit as a free-standing regulatory sign (US MUTCD "SPEED LIMIT" square in
 * imperial, EU/RoW red roundel in metric) WHEN known, the speedometer card below. Reads clean as just a speed when no limit
 * is available. The readout turns amber when the current GPS speed exceeds the limit by a tolerance
 * (GPS speed is noisy, so a plain > would flap). [limitKmh] is the OSM/GraphHopper value in km/h.
 */
@Composable
private fun SpeedWidget(
    speedMps: Float?,
    limitKmh: Double?,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    // Smooth the DISPLAYED speed (Google shows the fused estimate, not each raw doppler sample - the
    // raw 1 Hz readout flickered 59/60/61 at a steady cruise), with a small deadband so a stop reads
    // a clean 0 instead of 1 mph jitter.
    val shownSpeed by animateFloatAsState(
        targetValue = (speedMps ?: 0f).let { if (it < 0.4f) 0f else it },
        animationSpec = tween(durationMillis = 600),
        label = "speed",
    )
    val (value, unit) = formatSpeed(shownSpeed)
    val speedDisp = if (imperial) shownSpeed * 2.236936f else shownSpeed * 3.6f
    val limitDisp = limitKmh?.let { formatSpeedLimit(it).first }
    val tol = if (imperial) 3f else 5f
    val over = limitDisp != null && speedDisp > limitDisp + tol
    val overColor = Color(0xFFE8514A)
    val signInk = Color(0xFF202124)

    // TWO SEPARATE cards, stacked (user 2026-07-14, Google's own look): the regulatory sign is its
    // own free-standing card on top when a limit is known, and the speedometer is its own card
    // below - not one shared box (that read as a single mixed widget) and not a side-by-side row
    // (squat, eats horizontal map on the fork's narrow target screens).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        if (limitDisp != null) {
            if (imperial) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, signInk),
                    shadowElevation = 4.dp,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("SPEED", color = Color(0xFF3C4043), fontSize = 7.sp, fontWeight = FontWeight.SemiBold, lineHeight = 8.sp)
                        Text("LIMIT", color = Color(0xFF3C4043), fontSize = 7.sp, fontWeight = FontWeight.SemiBold, lineHeight = 8.sp)
                        Text("$limitDisp", color = if (over) overColor else signInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(3.5.dp, Color(0xFFD32F2F)),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("$limitDisp", color = if (over) overColor else signInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SheetPalette.bg(dark),
            contentColor = SheetPalette.ink(dark),
            shadowElevation = 4.dp,
            modifier = Modifier.widthIn(min = 58.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text("$value", fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp, color = if (over) overColor else SheetPalette.ink(dark))
                Text(unit, fontSize = 9.sp, color = SheetPalette.dim(dark), lineHeight = 10.sp)
            }
        }
    }
}


/** The parking history: every recent save (newest first), so an accidental overwrite is one tap
 *  from recovery. A raw Dialog (BACK exits); rows restore, the trash deletes, Clear all wipes.
 *  D-pad: opens focused on Clear all (rule: no surface opens unfocused), rows wear the ring. */
@Composable
private fun ParkingHistorySheet(
    history: List<app.vela.core.model.ParkedSpot>,
    currentAtMillis: Long,
    onRestore: (app.vela.core.model.ParkedSpot) -> Unit,
    onDelete: (app.vela.core.model.ParkedSpot) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp).widthIn(max = 420.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.parking_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    val autoFocus = rememberDpadAutoFocus(history.size)
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.focusRequester(autoFocus).dpadHighlight(RoundedCornerShape(20.dp)),
                    ) { Text(stringResource(R.string.parking_history_clear_all)) }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(history, key = { it.savedAtMillis }) { entry ->
                        val isCurrent = entry.savedAtMillis == currentAtMillis
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .dpadHighlight(RoundedCornerShape(8.dp))
                                .clickable { onRestore(entry) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.LocalParking,
                                contentDescription = null,
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                // DateUtils = localized relative age ("5 min ago" / "2 hours ago"),
                                // no hand-rolled English.
                                Text(
                                    android.text.format.DateUtils.getRelativeTimeSpanString(
                                        entry.savedAtMillis, System.currentTimeMillis(),
                                        android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                    ).toString(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                                        .format(java.util.Date(entry.savedAtMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isCurrent) {
                                Text(
                                    stringResource(R.string.parking_history_current),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                IconButton(onClick = { onDelete(entry) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.parking_history_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
