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
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import app.vela.R
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vela.BuildConfig
import app.vela.core.config.Notice
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import app.vela.core.model.ManeuverType
import app.vela.core.model.Place
import app.vela.core.data.RecentPlace
import app.vela.core.data.RecentQuery
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
import app.vela.ui.place.sheetDragGestures
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
import app.vela.ui.toggleItem
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
    onOpenVoiceSettings: () -> Unit = {},
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
    // top of the pin or the route (the directions panel is tall — fit the route above it).
    // Bumped by the in-nav Overview button; VelaMapView fits the whole route on each bump.
    var navOverviewTick by remember { mutableStateOf(0) }
    // The route chooser reports its minimized state up: with only the Start bar left on
    // screen the route fit gets nearly the whole map back, so it re-frames closer instead
    // of staying at the zoomed-out framing the full-height panel needed (user 2026-07-14).
    var dirMinimized by remember { mutableStateOf(false) }
    // Landscape (width > height): the browse top chrome collapses to ONE line (half-width search
    // bar + the chips beside it, Google's landscape layout) and the top-right corner stack
    // (layers button, compass) rises a row - on a phone's ~390dp landscape height the stacked
    // layout pushed the compass down into the parking/locate FABs (user 2026-07-15).
    val landscapeChrome = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
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
        // Liberty is swapped for the cached Roboto-glyph patch when MapFonts has it
        // ready (reactive - flips once the first refresh lands). Offline REGION
        // DEFINITIONS deliberately keep state.styleUri (see MapFonts).
        MapFonts.effective(state.styleUri)
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
    // The ribbon button's own menu: Your lists live here now, not inside the search page.
    var listsSheetOpen by remember { mutableStateOf(false) }

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
    LaunchedEffect(state.results.size, state.selected, state.pickingOrigin, state.pickingDest, state.pickingStop) {
        if (!state.pickingOrigin && !state.pickingDest && !state.pickingStop &&
            (state.results.isNotEmpty() || state.selected != null)
        ) {
            searchExpanded = false
        }
    }
    // The search overlay is open when the entry page is expanded OR we're picking a custom
    // directions origin/stop (that opens the same overlay WITHOUT focusing the field).
    val searchOpen = searchExpanded || state.pickingOrigin || state.pickingDest || state.pickingStop
    // The results panel is open (not collapsed to the "N results" pill) → hide the bottom map
    // chrome (scale bar / locate FAB / Search this area) so it never draws on top of the list at
    // ANY size, not just full screen. The panel and the chrome are siblings in the same Box and the
    // chrome is declared later, so it stacks above the panel unless gated out (user 2026-07-08).
    // Which result ids survive the results sheet's filters (null = filters off) - feeds the
    // map markers so filtered-out places lose their pins, not just their rows.
    var filteredResultIds by remember { mutableStateOf<Set<String>?>(null) }
    // True while the place sheet sits at its EXPANDED detent (covers the search bar).
    var placeSheetExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.selected?.id) { if (state.selected == null) placeSheetExpanded = false }
    LaunchedEffect(state.results) { filteredResultIds = null }
    // Street View gates the panel too: opening the viewer clears `selected`, which used to flip
    // this back ON and draw the results list over the bottom-half mini map (user 2026-07-18).
    val resultsShown = state.results.isNotEmpty() && state.selected == null && !searchOpen && !state.resultsCollapsed &&
        state.streetView == null && !state.streetViewLoading
    // Free-drive follow (Google's "the map tracks you as you drive, no route needed"). On by
    // default so an open, unobstructed map glides to your fix; a user pan drops it and the locate
    // tap raises it again. Suppressed whenever a focus surface owns the camera (search, a place,
    // directions, the results list) so it never fights that framing. Nav has its own follow.
    var followMe by remember { mutableStateOf(true) }
    // Roads already DRIVEN (entered by maneuvers up to the current step) + ref variants: the nav
    // label bubbles must never call out the road being driven (Google labels only streets you
    // cross), but the exclusion is per-step ON PURPOSE - the first cut excluded the WHOLE route,
    // which hid exactly the label that matters most: the road you are about to turn onto ("the
    // name of it needs to be right there", user 2026-07-16). Ref variants cover the basemap's
    // bare-number `ref` prop ("5") and the OSRM spelling ("I 5").
    val navLabelExclude = remember(state.activeRoute, state.navigating, state.nav.stepIndex) {
        if (!state.navigating) emptyList() else state.activeRoute?.maneuvers
            ?.take(state.nav.stepIndex.coerceAtLeast(0))
            ?.flatMap { m ->
                val refDigits = m.ref?.filter { it.isDigit() }
                listOfNotNull(m.road, m.ref, refDigits)
            }?.filter { it.isNotBlank() }?.distinct().orEmpty()
    }
    // The next two maneuvers' target roads are force-INCLUDED in the label pass: a turn target
    // often meets the route at a shared junction vertex, which a proper-crossing test can miss.
    val navUpcomingRoads = remember(state.activeRoute, state.navigating, state.nav.stepIndex) {
        if (!state.navigating) emptyList() else state.activeRoute?.maneuvers
            ?.drop(state.nav.stepIndex)?.take(2)
            ?.mapNotNull { m -> m.road?.takeIf { it.isNotBlank() } }.orEmpty()
    }
    var layersOpen by remember { mutableStateOf(false) } // the top-right layers panel
    // A programmatic camera jump far from the fix (a recents pick, a search hit, a pasted
    // coordinate, a deep link) means the user went to look somewhere else - drop follow exactly
    // like a pan would. Without this, follow was only SUSPENDED while the place sheet owned the
    // camera, so closing the sheet resumed it and the map glided all the way home (device report
    // 2026-07-13). A POI tapped near the fix keeps follow; 1 km is the "somewhere else" line.
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
    // a pan dropped followMe. That style churn was the post-0.4.542 "atrocious panning" regression
    // (device A/B 2026-07-13: 542 smooth, wave builds janky; 542 has no maxspeed overlay). Motion arms
    // it instantly; stillness disarms after 2 min so stoplights/parking don't churn sources either.
    var speedOverlayArmed by remember { mutableStateOf(false) }
    val movingNow = state.navigating || (state.mySpeed ?: 0f) > 3f
    LaunchedEffect(movingNow) {
        if (movingNow) speedOverlayArmed = true
        else if (speedOverlayArmed) {
            kotlinx.coroutines.delay(120_000)
            speedOverlayArmed = false
        }
    }
    // Bumped when the user grabs the map with a sheet open — each sheet glides down to its
    // minimized form on its bump (see onUserPan below).
    var sheetPanTick by remember { mutableStateOf(0) }
    var resultsPanTick by remember { mutableStateOf(0) }
    var dirPanTick by remember { mutableStateOf(0) }
    // Expanded detent of the results bottom sheet, hoisted here so the BACK gesture can step it
    // one detent (expanded -> peek) before collapsing to the minimized bar (user 2026-07-09).
    var resultsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.results) { if (state.results.isEmpty()) resultsExpanded = false }
    // The results sheet minimized to its short bottom bar — the chrome shows again then, but
    // lifted above the bar so the FAB / scale bar / Search this area never sit on top of it.
    val resultsMinimized = state.results.isNotEmpty() && state.selected == null && !searchOpen && state.resultsCollapsed
    val chromeLift = if (resultsMinimized) 76.dp else 0.dp
    var metersPerPixel by remember { mutableStateOf(0.0) }
    // Building-overlay debug badge: the last state VelaMapView's idle gate reported
    // ("none" / "hidden" / "drawing"). Only surfaced when the developer toggle is on.
    var overlayDebugState by remember { mutableStateOf("none") }
    // Measured screen-Y of the maneuver banner's bottom edge → so VelaMapView can sit the compass just below
    // it during nav (the banner's height varies with lane guidance + a "then" row, so it can't be guessed).
    var navBannerBottomPx by remember { mutableStateOf(0) }
    // The endpoints card's bottom edge, so the notification column can sit under it in
    // directions mode instead of printing over it (user 2026-07-13).
    var topCardBottomPx by remember { mutableStateOf(0) }
    // In-nav search-along-route: the map search FAB arms a panel (text field + chips) above
    // the bar. Reset when nav ends so a stale-open panel can't greet the next drive.
    var navSearchOpen by remember { mutableStateOf(false) }
    var navSearchQuery by remember { mutableStateOf("") }
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
            searchOpen -> { searchExpanded = false; focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickDestination(); vm.cancelPickStop() }
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
            // Results sheet: BACK exits the search outright - the drag gestures, the handle and
            // the X already cover stepping between sizes, so back-stepping detents just made
            // leaving take three presses (user 2026-07-11).
            else -> { resultsExpanded = false; vm.clearSearch() }
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

    // When the request comes back fully denied (including Android's instant deny after a permanent
    // "don't ask again"), the locate button used to just do nothing. Explain and point at settings.
    var showLocationOff by remember { mutableStateOf(false) }
    // A coarse-only grant looks broken later (a wide circle for a dot, navigation that can't start),
    // so the locate button's re-ask explains it ONCE when it happens; "Allow precise" re-runs the
    // request, which Android shows as its approximate-to-precise choice.
    var showApproxNotice by remember { mutableStateOf(false) }
    var approxNoticed by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fineNow = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseNow = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineNow || coarseNow) vm.startLocation() else showLocationOff = true
        if (!fineNow && coarseNow && !approxNoticed) {
            approxNoticed = true
            showApproxNotice = true
        }
    }
    // Only START location if it's already granted. The raw system dialog is NOT fired here anymore:
    // the onboarding rationale (VelaRoot) owns the first ask so it comes with an explanation, and the
    // locate FAB (below) re-asks for anyone who declined. Firing it unconditionally here was the
    // "permission pops the instant the map loads, no context" problem.
    fun hasLocation() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) {
        if (hasLocation()) vm.startLocation()
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
    // abort flag (Back/cancel → don't apply the partial transcript).
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
            dismissLowEmphasis = true,
            text = { Text(stringResource(R.string.map_asr_offer_body)) },
        )
    }
    // Reflect whether the on-device model is present, so the mic + Settings update without a relaunch.
    LaunchedEffect(Unit) { vm.refreshAsr() }
    // POI visibility prefs act immediately (clear + re-resolve), not on the next pan.
    LaunchedEffect(
        app.vela.ui.MapPoiPrefs.showPois.value,
        app.vela.ui.MapPoiPrefs.showTransit.value,
        app.vela.ui.MapPoiPrefs.showCivic.value,
    ) { vm.onPoiPrefsChanged() }
    if (voiceListening) {
        app.vela.ui.VoiceCaptureDialog(
            level = voiceLevel,
            listening = voiceStarted,
            onDone = { voiceStop = true },                    // stop early, keep + search what was heard
            onCancel = { voiceAbort = true; voiceStop = true }, // Back/outside: abort, don't apply
        )
    }

    // Tapping the locate button with no permission is an unambiguous "I want my location" - request
    // it (no rationale screen needed, the tap IS the intent). Granted → normal recenter.
    val onRecenter: () -> Unit = {
        if (hasLocation()) {
            followMe = true // re-arm free-drive follow: the locate tap means "track me again"
            vm.recenter()
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
    fun proceedStartNav() {
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
    fun fineGranted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    // Turn-by-turn follows you with GPS, which needs PRECISE location: coarse fixes are ~2 km and
    // the nav engine (correctly) refuses them, so starting nav without precise just sat forever at
    // "Searching for GPS" with no explanation. Gate the Start button on it instead - the request
    // doubles as Android's approximate-to-precise upgrade dialog. Demo drive simulates and skips this.
    var showPreciseNeeded by remember { mutableStateOf(false) }
    val finePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.startLocation()
            proceedStartNav()
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.nav_precise_toast), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val onStartNav: () -> Unit = {
        if (!fineGranted() && !vm.demoDriveOn()) showPreciseNeeded = true else proceedStartNav()
    }
    if (showPreciseNeeded) {
        app.vela.ui.VelaDialog(
            onDismissRequest = { showPreciseNeeded = false },
            title = stringResource(R.string.nav_precise_title),
            confirmText = stringResource(R.string.nav_precise_allow),
            onConfirm = {
                showPreciseNeeded = false
                finePermLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            dismissText = stringResource(R.string.root_not_now),
            onDismiss = { showPreciseNeeded = false },
            dismissLowEmphasis = true,
            text = { Text(stringResource(R.string.nav_precise_body)) },
        )
    }
    if (showLocationOff) {
        app.vela.ui.VelaDialog(
            onDismissRequest = { showLocationOff = false },
            title = stringResource(R.string.loc_off_title),
            confirmText = stringResource(R.string.loc_off_settings),
            onConfirm = {
                showLocationOff = false
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            },
            dismissText = stringResource(R.string.root_not_now),
            onDismiss = { showLocationOff = false },
            dismissLowEmphasis = true,
            text = { Text(stringResource(R.string.loc_off_body)) },
        )
    }
    if (showApproxNotice) {
        app.vela.ui.VelaDialog(
            onDismissRequest = { showApproxNotice = false },
            title = stringResource(R.string.loc_approx_title),
            confirmText = stringResource(R.string.loc_approx_allow),
            onConfirm = {
                showApproxNotice = false
                permLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            dismissText = stringResource(R.string.loc_approx_keep),
            onDismiss = { showApproxNotice = false },
            dismissLowEmphasis = true,
            text = { Text(stringResource(R.string.loc_approx_body)) },
        )
    }

    // Toggling the Flock layer in Settings refetches for the current view right away (otherwise the
    // cameras wouldn't appear until the next pan). Clears the layer when turned off.
    LaunchedEffect(app.vela.ui.Flock.on.value) { vm.refreshFlockNow() }
    // Saved-place pins for the browse map (issue #171): each list place carries its list's
    // icon+colour, quick-saves ride the default bookmark blue; deduped by place id (a place in
    // several lists draws once, newest list wins). Empty while a result set / nav / replay /
    // Street View owns the map.
    val savedPinData = remember(state.lists, state.saved, state.results, state.navigating, state.replaying, svPose) {
        if (state.navigating || state.replaying || svPose != null || state.results.isNotEmpty()) emptyList()
        else buildList {
            val seen = HashSet<String>()
            state.lists.forEach { l ->
                l.places.forEach { lp ->
                    if (seen.add(lp.id)) add(SavedPin(lp.lat, lp.lng, l.icon, l.color) to lp.toPlace())
                }
            }
            state.saved.forEach { sp ->
                if (seen.add(sp.id)) {
                    add(
                        SavedPin(sp.lat, sp.lng, "bookmark", 0xFF1A73E8) to
                            app.vela.core.model.Place(id = sp.id, name = sp.name, location = sp.location, address = sp.address),
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        VelaMapView(
            styleUri = mapStyleUri,
            myLocation = state.myLocation,
            myBearing = state.myBearing,
            // Coarse-only permission always means a vague position, even before a fresh fix
            // reports its accuracy (Android hands coarse apps a fix only every few minutes): fall
            // back to the ~2 km grid Android fuzzes coarse locations to, so the halo shows at once.
            myAccuracyM = state.myAccuracyM ?: if (hasLocation() && androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) 2000f else null,
            mySpeed = state.mySpeed,
            mySpeedRaw = state.mySpeedRaw,
            replaySpeedup = if (state.replaying) MapViewModel.REPLAY_SPEEDUP else 1f,
            replaying = state.replaying,
            compassHeading = state.compassHeading,
            locationStale = state.myLocationStale,
            cameraTarget = state.center,
            cameraTargetZoom = state.centerZoom,
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
            markers = markersOf(state, filteredResultIds),
            frameMarkers = state.results.isNotEmpty() && state.selected == null && !state.resultsCollapsed,
            holdMarkerFit = state.selected != null || state.streetView != null || state.streetViewLoading,
            // The endpoints card's measured bottom edge: the route fit frames start/end in the
            // strip between the card and the chooser instead of hiding either behind chrome.
            cameraTopInsetPx = if (state.directionsOpen && !state.navigating) topCardBottomPx else 0,
            // Numbered stop pins while the trip UI is active (chooser, editor or the drive itself).
            stopPins = if (state.directionsOpen || state.navigating) state.directionsWaypoints.map { it.location } else emptyList(),
            navMode = state.navigating,
            navDriveMode = state.travelMode == app.vela.core.model.TravelMode.DRIVE,
            navLabelExclude = navLabelExclude,
            navUpcomingRoads = navUpcomingRoads,
            // Follow yields to a manual pan AND to step preview: the per-frame follow ticker used
            // to keep re-pointing the camera at the puck while a banner swipe was trying to fly to
            // the previewed turn - the two fought at 60 fps (user 2026-07-14). The puck itself
            // keeps updating either way; only the camera steps aside.
            navFollowing = !state.navCameraDetached && state.previewStepIndex == null,
            navNorthUp = state.navNorthUp,
            // The compass below the nav card is the heading-up/north-up toggle during a drive
            // (a reorient-to-north tap would be overridden by the follow a frame later anyway).
            onCompassTap = { if (state.navigating) { vm.toggleNavNorthUp(); true } else false },
            poisEnabled = app.vela.ui.MapPoiPrefs.showPois.value,
            poiIconScale = app.vela.ui.MapPoiPrefs.iconScale.floatValue,
            onNavPanned = vm::onNavPanned,
            ambientCoversView = state.ambientCoversView,
            // Grabbing the map with a sheet up drops it down out of the way so the map is yours
            // to look at (Google does the same): the results sheet to its bar, the place sheet to
            // its minimized card. The bar / a drag brings them back.
            driveFollowing = driveFollowing,
            onMapTap = {
                // Tapping the map with the along-route panel up dismisses it, same as a pan
                // ("tap off of it should close it", user 2026-07-14).
                if (navSearchOpen) { navSearchOpen = false; focusManager.clearFocus() }
            },
            onUserPan = {
                // Grabbing the map is an explicit "let me look around" - stop tracking until the
                // locate tap re-arms it (Google drops follow the moment you pan).
                followMe = false
                // Bump ticks, don't flip state here: each sheet GLIDES down first and only then
                // flips its collapsed state, so the bar/card swap happens invisibly (flipping
                // straight away unmounted the content mid-drop — the "pops down" report).
                if (navSearchOpen) { navSearchOpen = false; focusManager.clearFocus() }
                if (resultsShown) resultsPanTick++
                if (state.selected != null && !searchOpen) sheetPanTick++
                if (state.directionsOpen && !searchOpen) dirPanTick++
            },
            onScaleChanged = { metersPerPixel = it },
            onOverlayState = { overlayDebugState = it },
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
            satelliteOn = app.vela.ui.SatelliteLayer.on.value,
            topographyOn = app.vela.ui.Topography.on.value,
            previewTarget = state.previewStepIndex?.let { state.activeRoute?.maneuvers?.getOrNull(it)?.location },
            navOverviewTick = navOverviewTick,
            onPoiTap = vm::onPoiTap,
            onMarkerTap = { i -> displayedPlaces(state).getOrNull(i)?.let(vm::selectPlace) },
            parkingSpot = state.parkingSpot,
            onParkingTap = { vm.showParkedCar(context.getString(R.string.map_parked_car)) },
            // Saved places stick out while browsing (issue #171): every list place + quick-save
            // draws its list's icon/emoji in the list's colour. Hidden while a result set owns the
            // map (a list's own results would double-draw) and during nav/replay (declutter).
            savedPins = savedPinData.map { it.first },
            onSavedPinTap = { i -> savedPinData.getOrNull(i)?.second?.let(vm::selectPlace) },
            svPose = svPose,
            svTopInsetPx = (screenHeightPx * 0.55f).toInt(),
            onSvMapTap = vm::moveStreetViewTo,
            // No stale ambient dots mid-drive: the fetch pauses during nav, and the basemap POIs
            // (kept visible in nav now) cover the gas-station-on-the-way case without duplicates.
            ambientPois = if (state.navigating) emptyList() else ambientMarkersOf(state),
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

        // --- Debug badge (Settings → Developer → Building overlay debug) ---
        // Canary aid: fill-buildings auto-suppression state at a glance + a UI-thread FPS readout.
        // The FPS is the Compose/Choreographer frame rate (what the main thread achieves), so it
        // drops exactly when panning janks - the number to watch while chasing map smoothness.
        if (app.vela.ui.BuildingDebug.on.value) {
            val label = when {
                !app.vela.ui.BuildingOverlay.on.value -> "MS bldg: OFF"
                overlayDebugState == "drawing" -> "MS bldg: DRAWING"
                overlayDebugState == "hidden" -> "MS bldg: hidden (OSM dense)"
                else -> "MS bldg: none here"
            }
            var fps by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                var frames = 0
                var acc = 0L
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        if (last != 0L) {
                            acc += now - last
                            frames++
                            if (acc >= 500_000_000L) { // recompute twice a second
                                fps = (frames * 1_000_000_000.0 / acc).toInt()
                                frames = 0; acc = 0L
                            }
                        }
                        last = now
                    }
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 92.dp)
                    .background(androidx.compose.ui.graphics.Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text("$fps fps", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp)
                Text(label, color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp)
            }
        }

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
                offRoute = state.nav.offRoute,
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
                // The road being driven = the one entered by the LIVE maneuver last passed
                // (never the previewed one - previewing shouldn't change where you "are").
                currentRef = mans?.getOrNull(liveStep - 1)?.ref,
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
                    // Landscape bare-map chrome collapses to one line (bar | chips) below. NB the
                    // condition must NOT include !searchOpen: focusing the bar flips searchOpen,
                    // and if that moved the SearchBar to a different subtree the remount blurred
                    // the field, which flipped searchOpen right back - the search page could
                    // never open. Instead the Row stays and only the MODIFIERS change (bar grows
                    // full width, chips drop out after it), so the field node never moves.
                    val landscapeOneLine = landscapeChrome && state.selected == null &&
                        state.results.isEmpty() && !state.directionsOpen
                    // Directions mode: the search bar swaps for the Google-style endpoints card
                    // (origin / stops / destination, swap, back) — the rows that used to sit in
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
                            // Both ends are editable now (issue #170): the your-location end keeps the
                            // origin picker; the PLACE end swaps the destination in place, keeping the
                            // origin and stops (backing out used to lose the custom origin).
                            onEditOrigin = if (state.directionsReversed) vm::beginPickDestination else vm::beginPickOrigin,
                            onEditDestination = if (state.directionsReversed) vm::beginPickOrigin else vm::beginPickDestination,
                            onEditStops = vm::openStopsEditor,
                            onAddStop = vm::openStopsEditor,
                            onSwap = vm::swapDirections,
                            onClose = vm::clearRoute,
                        )
                    }
                    // The bar hides while an expanded place sheet covers it: the visible sliver
                    // still took taps and opened search OVER the card (user 2026-07-11) — and
                    // while the endpoints card above replaces it in directions mode.
                    val searchBar: @Composable () -> Unit = {
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
                        onBack = if (searchOpen) ({ searchExpanded = false; focusManager.clearFocus(); vm.cancelPickOrigin(); vm.cancelPickDestination(); vm.cancelPickStop() }) else null,
                        offline = state.offline,
                        dpadMode = dpadMode,
                        onMic = onMic,
                    )
                    }
                    if (landscapeOneLine) {
                        // Landscape browse chrome is ONE line, Google-style (user 2026-07-15):
                        // the search bar takes half the width and the category chips scroll
                        // beside it, so the top-right corner stack (layers button, compass)
                        // starts a whole row higher on screens that are already short. With the
                        // search page open the bar grows to full width IN PLACE (see the
                        // landscapeOneLine note above - the field must not remount) and the
                        // chips step aside; the entry page renders full-width below as usual.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(if (searchOpen) Modifier.fillMaxWidth() else Modifier.weight(1f)) { searchBar() }
                            if (!searchOpen) {
                                Spacer(Modifier.width(10.dp))
                                CategoryChips(
                                    onPick = vm::quickSearch,
                                    onOpenLists = { listsSheetOpen = true },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else if (!(state.selected != null && placeSheetExpanded && !searchOpen) &&
                        !(state.directionsOpen && !searchOpen)
                    ) {
                        searchBar()
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
                                ((state.pickingOrigin || state.pickingDest || state.pickingStop) && state.query.isBlank())
                            ) -> SearchEntryContent(
                            suggestions = state.suggestions,
                            saved = state.saved,
                            recents = state.recents,
                            recentPlaces = state.recentPlaces,
                            home = state.home,
                            work = state.work,
                            assigning = state.assigningShortcut,
                            pickingOrigin = state.pickingOrigin,
                            pickingDest = state.pickingDest,
                            pickingStop = state.pickingStop,
                            onCancelPickStop = vm::cancelPickStop,
                            onUseMyLocation = vm::useMyLocationAsOrigin,
                            onChooseOnMap = {
                                focusManager.clearFocus()
                                if (state.pickingOrigin) vm.chooseOriginOnMap()
                                else if (state.pickingDest) vm.chooseDestOnMap()
                                else vm.chooseStopOnMap()
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
                            onRemoveRecent = vm::removeRecentQuery,
                            onRemoveRecentPlace = vm::removeRecentPlace,
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
                        // chips, and only on the bare map (in landscape they already rendered
                        // beside the bar above).
                        !landscapeOneLine && state.selected == null && state.results.isEmpty() -> CategoryChips(
                            onPick = vm::quickSearch,
                            onOpenLists = { listsSheetOpen = true },
                            modifier = Modifier.padding(top = 8.dp),
                        )
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

        // (The faster-route offer renders in the stacked notification column below, so it can
        // never sit under the turn card or on top of another card.)

        // The along-route search panel lives at the TOP, under the turn banner where the
        // heads-up cards go (user 2026-07-14): one stable position - the keyboard can never
        // cover it (no focus-driven move), and it can't collide with the FAB stack or the
        // bottom bar. Transient heads-up cards may draw over it; they're rare and short-lived.
        if (state.navigating && navSearchOpen && state.results.isEmpty()) {
            val panelBannerBottom = with(LocalDensity.current) { navBannerBottomPx.toDp() }
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
                    .padding(top = panelBannerBottom + 10.dp, start = 12.dp, end = 12.dp),
            )
        }

        // Right-edge nav FAB stack: volume + search live ON THE MAP (the bottom bar was
        // cramming four controls - user 2026-07-14; Google floats these there too), with the
        // re-center button joining the stack when panned away / previewing a step. Hidden
        // while the along-route results own the bottom slot.
        if (state.navigating && state.results.isEmpty()) {
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
                // Whole-route overview (Google's fly-over): camera only, the drive keeps
                // navigating; Re-center (above, it appears the moment this detaches the
                // camera) glides straight back into the follow.
                FloatingActionButton(
                    onClick = {
                        vm.navOverview()
                        navOverviewTick++
                    },
                    modifier = Modifier.dpadHighlight(RoundedCornerShape(16.dp)),
                ) { Icon(Icons.Default.ZoomOutMap, contentDescription = stringResource(R.string.nav_overview)) }
                FloatingActionButton(
                    onClick = vm::toggleVoice,
                    modifier = Modifier.dpadHighlight(RoundedCornerShape(16.dp)),
                ) {
                    Icon(
                        if (state.voiceMuted) Icons.Default.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (state.voiceMuted) stringResource(R.string.nav_unmute_voice) else stringResource(R.string.nav_mute_voice),
                    )
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

        // Unified speed readout (Google-style), bottom-left while MOVING - during nav AND during a
        // free-drive (browsing without a route, moving on the clean map). Always shows the current
        // speed; the posted limit (on-device graph maxspeed, else the streamed "Speed B" overlay)
        // tucks into the SAME box when known, and it still reads clean as just a speed when no limit
        // is available. Over the limit -> amber. In free-drive the scale bar hides (below) so the box
        // owns the corner, like Google's driving view.
        val movingFree = !state.navigating && (state.mySpeed ?: 0f) > 3f &&
            !searchOpen && state.selected == null && !state.directionsOpen && !state.showSteps && !resultsShown
        val postedLimitKmh = state.speedLimitKmh ?: state.speedLimitOverlayKmh
        if ((state.navigating && state.mySpeed != null) || movingFree) {
            SpeedWidget(
                speedMps = state.mySpeed,
                limitKmh = postedLimitKmh,
                imperial = Units.imperial.value,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    // During nav the box clears the ETA bar; free-driving there is no bar, so it
                    // sits low in the corner, level with the locate FAB (user 2026-07-14). The
                    // scale bar yields the spot while the box is there (below).
                    .padding(start = 16.dp, bottom = if (state.navigating) navBarClearance else 16.dp + chromeLift),
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
                originIsMe = !state.directionsReversed && state.directionsOrigin == null,
                destinationName = if (state.directionsReversed) (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location))
                else (state.selected?.name ?: stringResource(R.string.mapscreen_destination)),
                stops = state.directionsWaypoints,
                onApply = vm::applyStops,
                onAddStop = vm::beginPickStop,
                onDismiss = vm::closeStopsEditor,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Tapping "Directions" opens a dedicated panel (popup) — mode tabs, the
            // route option(s) with traffic-aware ETAs, selectable alternates, Start —
            // instead of burying it at the bottom of the place sheet.
            // Hidden while the search overlay is up (e.g. picking a custom origin) so
            // the panel doesn't render over it.
            state.directionsOpen && !searchOpen && state.pickOnMap == null -> DirectionsPanel(
                destinationName = if (state.directionsReversed) (state.directionsOrigin?.name ?: stringResource(R.string.mapscreen_your_location))
                else (state.selected?.name ?: stringResource(R.string.mapscreen_destination)),
                currentMode = state.travelMode,
                routes = state.routes,
                activeRoute = state.activeRoute,
                flockOnRoute = state.flockOnRoute,
                transit = state.transit,
                transitLoading = state.transitLoading,
                onModeSelected = vm::setTravelMode,
                avoidTolls = state.avoidTolls,
                avoidHighways = state.avoidHighways,
                onAvoidTolls = vm::setAvoidTolls,
                onAvoidHighways = vm::setAvoidHighways,
                onSelectRoute = vm::selectRoute,
                onStartNav = onStartNav,
                minimizeTick = dirPanTick,
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
                onExpandedChange = { placeSheetExpanded = it },
                isSaved = state.saved.any { it.id == state.selected!!.id },
                reviews = state.reviews,
                reviewsLoading = state.reviewsLoading,
                reviewsFound = state.reviewsFound,
                photosLoading = state.photosLoading,
                detailsLoading = state.loadingDetails,
                placesHere = state.placesHere,
                // Ownership-gated: a board renders ONLY on the place it was fetched for. Writers
                // are guarded, but selection paths that skip the clear (saved/recent opens) let a
                // previous stop's departures land on an unrelated place otherwise.
                stopDepartures = state.stopDepartures
                    ?.takeIf { state.stopDeparturesFor != null && state.stopDeparturesFor == state.selected?.id },
                stopDeparturesLoading = state.stopDeparturesLoading &&
                    state.stopDeparturesFor != null && state.stopDeparturesFor == state.selected?.id,
                onTapRoute = vm::openRouteDetail,
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
                lists = state.lists,
                onAddToList = { listId -> vm.addPlaceToList(listId, state.selected!!) },
                onRemoveFromList = { listId -> vm.removePlaceFromList(listId, state.selected!!) },
                onCreateListWith = { name -> val id = vm.createList(name); vm.addPlaceToList(id, state.selected!!) },
                onSetNote = { note -> vm.setPlaceNote(state.selected!!, note) },
                minimizeTick = sheetPanTick,
                // No navigationBarsPadding here: the sheet's background should reach
                // the screen bottom (no map peeking through under the nav bar); the
                // sheet pads its own content for the nav bar instead.
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Search results as a BOTTOM sheet, Google-style — same detent family as the place
            // sheet (minimized bar ↔ peek ↔ expanded). Reached only when nothing above matched,
            // so a selected place / directions / nav always win the bottom slot. Street View
            // gates it too: opening the viewer clears `selected`, which used to fall through to
            // THIS branch and draw the results list over the bottom-half mini map (user
            // 2026-07-18); the sheet returns when the viewer closes.
            state.results.isNotEmpty() && !searchOpen && state.pickOnMap == null &&
                state.streetView == null && !state.streetViewLoading -> {
              SearchResults(
                results = state.results,
                onShownChange = { filteredResultIds = it },
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
                listName = state.openListId?.let { id -> state.lists.firstOrNull { it.id == id }?.name },
                query = state.query,
                minimizeTick = resultsPanTick,
                modifier = Modifier.align(Alignment.BottomCenter),
              )
            // Imported Google list preview: offer to save (nothing persisted until tapped).
            // A pill under the search bar, clear of the results sheet at the bottom.
            // A pushed URGENT notice (calibration.json, level "urgent") is a modal, not a card —
        // for announcements that must be seen (the "servers overloaded" class of message).
        // Same signed channel + the same one-time dismissal persistence as the cards.
        state.notices.firstOrNull { it.level == app.vela.core.config.Notice.LEVEL_URGENT }?.let { n ->
            app.vela.ui.VelaDialog(
                onDismissRequest = { vm.dismissNotice(n.id) },
                title = n.title,
                confirmText = stringResource(android.R.string.ok),
                onConfirm = { vm.dismissNotice(n.id) },
                dismissText = if (n.url != null) stringResource(R.string.mapscreen_learn_more) else stringResource(R.string.place_close),
                onDismiss = {
                    n.url?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }
                    vm.dismissNotice(n.id)
                },
            ) {
                Text(n.body)
            }
        }
        state.pendingImport?.let { imp ->
                val savedMsg = stringResource(R.string.map_list_saved, imp.title)
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 76.dp)
                        .dpadHighlight(RoundedCornerShape(20.dp))
                        .clickable {
                            vm.saveImportedList()
                            Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                        },
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.map_save_list, imp.places.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            }
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

        // Tap-through: the stop timeline for a route tapped on the departure board. Drawn over the
        // place sheet; tapping a stop opens that stop's own board, so the user keeps drilling down
        // the line the way Google's tap-through does. Also shown (with a spinner) while it loads.
        if (state.routeDetail != null || state.routeDetailLoading) {
            app.vela.ui.place.RouteDetailSheet(
                step = state.routeDetail,
                title = state.routeDetailTitle,
                loading = state.routeDetailLoading,
                onClose = vm::closeRouteDetail,
                onStopTap = vm::openRouteStop,
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

        // The locate + parking buttons yield to EVERY bottom surface, the route chooser and the
        // step list included - a search from an open chooser could null the selection while
        // directionsOpen stayed true, and both buttons drew on top of the panel.
        if (!state.navigating && state.selected == null && !searchOpen && state.resumeNavLabel == null &&
            !resultsShown && !state.directionsOpen && !state.showSteps
        ) {
            // Stock M3 FAB, deliberately: a Google-style flat circle was tried (2026-07-08)
            // and reverted — every surface tone melted into the dark tiles.
            FloatingActionButton(
                onClick = onRecenter,
                modifier = Modifier
                    .dpadHighlight(RoundedCornerShape(16.dp))
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .padding(bottom = chromeLift),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.mapscreen_center_on_my_location))
            }
            // Parking button, its OWN control above the locate FAB. TAP with NO spot → save here
            // (the one-tap "I parked" path). TAP with a spot set (teal) → a small hub menu (Find my
            // car / Move parking here / Earlier spots / Clear) so re-parking is one obvious choice
            // instead of the old clear-then-tap-again dance (user 2026-07-11: "setting parking again
            // when you already have a spot is clunky"). LONG-PRESS still jumps straight to history.
            // A Surface, not SmallFAB: the FAB's clickable eats the down so an outer long-press never
            // fires; the inner Box detector owns both gestures (same seam the locate FAB needed).
            val parkingSavedMsg = stringResource(R.string.map_parking_saved)
            val parkingNoFixMsg = stringResource(R.string.map_parking_no_fix)
            val parkingMovedMsg = stringResource(R.string.map_parking_moved)
            val parkingClearedMsg = stringResource(R.string.map_parking_cleared)
            val parkedCarLabel = stringResource(R.string.map_parked_car)
            val parkingSet = state.parkingSpot != null
            var showParkingHistory by remember { mutableStateOf(false) }
            var showParkingMenu by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (parkingSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                // Soft glyph ink when unset (onSecondaryContainer read near-black, same as the
                // bookmark ribbon; user 2026-07-11). The SET state keeps primary/onPrimary - it
                // carries state, like the Home/Work rows.
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
                        .pointerInput(parkingSet, state.parkingHistory.size) {
                            detectTapGestures(
                                onTap = {
                                    if (parkingSet) {
                                        showParkingMenu = true
                                    } else {
                                        val msg = if (vm.saveParkingSpot()) parkingSavedMsg else parkingNoFixMsg
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
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
                    // VelaMenu, not a bare DropdownMenu - the D-pad rule (docs/dpad.md): a popup
                    // can't be pre-focused, so key-first devices get the auto-focusing chooser.
                    app.vela.ui.VelaMenu(expanded = showParkingMenu, onDismissRequest = { showParkingMenu = false }) {
                        item(stringResource(R.string.map_parking_find), Icons.Default.DirectionsCar) {
                            showParkingMenu = false; vm.showParkedCar(parkedCarLabel)
                        }
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
                            item(stringResource(R.string.map_parking_earlier), Icons.Default.History) {
                                showParkingMenu = false; showParkingHistory = true
                            }
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
            if (listsSheetOpen) {
                ListsSheet(
                    lists = state.lists,
                    onOpenList = { listsSheetOpen = false; vm.openList(it) },
                    onCreateList = { name -> vm.createList(name) },
                    onUpdateList = vm::updateList,
                    onDeleteList = vm::deleteList,
                    onDismiss = { listsSheetOpen = false },
                )
            }
            // (The live-traffic overlay toggle moved to Settings → Map — it's a
            // niche browse-only layer, and nav now shows per-segment route traffic,
            // so it no longer earns a spot on the map.)
            // Scale bar, bottom-left just past the attribution ⓘ. Hidden only while ACTUALLY
            // free-driving (moving, speed box on screen) - `!driveFollowing` alone hid it on the
            // whole browse map, since follow is armed by default (regression, 0.4.542..wave).
            // Satellite toggle, top-right under the search bar + chips (browse map only): flips
            // the Esri World Imagery raster under the symbol stack. Filled tint = on.
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
                        // One-line landscape chrome: the chips sit beside the bar, so the
                        // button rises a whole row (matches the compass margin in VelaMapView).
                        .padding(top = if (landscapeChrome) 74.dp else 128.dp, end = 14.dp),
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
            // Also step aside while the free-drive speed box occupies the low corner (it moved
            // down level with the locate FAB, user 2026-07-14) - the follow gate alone missed a
            // moving-but-panned map, where both used to want the same spot.
            if (!(driveFollowing && speedOverlayArmed) && !movingFree) {
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
        if (state.showPsdsTip && state.selected == null && !searchOpen && !state.navigating &&
            state.resumeNavLabel == null
        ) {
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
        // ONE notification area: the heads-up flash, downloads, the update card and pushed notices
        // all STACK here (each with its own dismiss) instead of painting over each other — the old
        // separate status card sat on the chips and could cover the update card. Position: just
        // below the search bar + chips on the browse map; during nav just below the turn card,
        // whose height VARIES (lanes, "then" row) — so it hangs off the banner's MEASURED bottom
        // edge, the same navBannerBottomPx the compass uses, and slides with it.
        val downloadingVoiceId = state.voiceDownloadingId
        val downloadingRegion = state.routingDownloadingId != null || state.poiPackDownloadingId != null
        val bareMap = state.selected == null && !searchOpen
        val fasterOffer = state.navigating && state.fasterRoute != null
        if (state.status != null || fasterOffer ||
            (bareMap && (state.notices.isNotEmpty() || downloadingVoiceId != null || downloadingRegion || state.updateInfo != null))
        ) {
            val bannerBottom = with(LocalDensity.current) { navBannerBottomPx.toDp() }
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        if (state.navigating && navBannerBottomPx > 0) {
                            // positionInRoot already spans the status bar — no statusBarsPadding here.
                            Modifier.padding(top = bannerBottom + 10.dp, start = 12.dp, end = 12.dp)
                        } else if (state.directionsOpen && !searchOpen && topCardBottomPx > 0) {
                            // Below the endpoints card, which is taller than the search bar the
                            // 132dp constant was tuned for (cards printed over it otherwise).
                            Modifier.padding(
                                top = with(LocalDensity.current) { topCardBottomPx.toDp() } + 10.dp,
                                start = 12.dp, end = 12.dp,
                            )
                        } else {
                            Modifier.statusBarsPadding().padding(top = 132.dp, start = 12.dp, end = 12.dp)
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (fasterOffer) {
                    FasterRouteCard(
                        savingSeconds = state.fasterSavingSeconds,
                        onSwitch = vm::acceptFasterRoute,
                        onDismiss = vm::dismissFasterRoute,
                    )
                }
                state.status?.let { msg ->
                    InfoCard(
                        title = stringResource(R.string.mapscreen_heads_up),
                        body = msg,
                        actionLabel = stringResource(R.string.mapscreen_dismiss),
                        onAction = vm::clearStatus,
                        // A voice problem carries its fix. Normally a pill straight to Vela's voice
                        // library; for a language with no Vela voice (Japanese) it opens the phone's
                        // own voice settings instead, where the user can add a system voice.
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
                                            Intent("com.android.settings.TTS_SETTINGS")
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.onFailure {
                                        runCatching {
                                            context.startActivity(
                                                Intent(android.provider.Settings.ACTION_SETTINGS)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }
                                }
                            }
                            state.statusVoiceAction -> {
                                { vm.clearStatus(); onOpenVoiceSettings() }
                            }
                            else -> null
                        },
                    )
                }
                if (bareMap) {
                    if (downloadingVoiceId != null) {
                        VoiceDownloadCard(installing = state.voiceInstalling, pct = state.voiceDownloadPct ?: 0f)
                    }
                    // The speech model download (started from the mic offer or Settings) gets the
                    // same card - it's also called Vela voice, so the one label fits both.
                    if (state.asrDownloadPct != null) {
                        VoiceDownloadCard(installing = state.asrInstalling, pct = state.asrDownloadPct ?: 0f)
                    }
                    // A region (state/country) download: the routing graph first, then its place
                    // pack — same progress card treatment as the voice download, so a Settings-
                    // started state download stays visible after backing out to the map.
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
                    state.notices.filterNot { it.level == app.vela.core.config.Notice.LEVEL_URGENT }.forEach { n ->
                        NoticeCard(n, onDismiss = { vm.dismissNotice(n.id) })
                    }
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

/** The ambient Google POIs actually shown: the bare browse map AND while a single place sheet
 *  is open. Wiping them on select emptied the whole ambient source, so tapping a POI made every
 *  icon around it vanish and closing the sheet re-placed the entire layer (the "POIs reload when
 *  I tap one then exit", user 2026-07-15) - Google keeps the area's icons up around an opened
 *  place. Only the opened place's own ambient copy is dropped, so its icon and label don't
 *  double-draw under the red selection pin. Still hidden while results / a route preview / nav /
 *  replay own the map. Used by BOTH the marker upload and the tap handler, so the
 *  AMBIENT_INDEX_PROP a tap reports always indexes THIS list. */
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

private fun markersOf(state: MapUiState, filteredIds: Set<String>?): List<MapMarker> =
    displayedPlaces(state)
        // The results sheet's filters (Open now / rating / price) report the surviving ids up;
        // pins the LIST dropped must drop off the MAP too (user 2026-07-11). null = no filter on.
        .let { list -> if (filteredIds == null) list else list.filter { it.id in filteredIds } }
        .map { MapMarker(it.name, it.location, it.category, rating = it.rating, fuelPrice = it.fuelPrice) }

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
    listName: String? = null, // set when the results ARE an open list — shown as the sheet title
    query: String = "", // the search text — leads the minimized bar so it says WHAT the results are
    minimizeTick: Int = 0, // bumped when the user grabs the map — glide down, THEN flip collapsed
    onShownChange: (Set<String>?) -> Unit = {}, // filtered-surviving ids (null = no filter active)
    modifier: Modifier = Modifier,
) {
    // A BOTTOM sheet, Google-style, sharing the place sheet's detent grammar:
    // MINIMIZED (a short "N results" bar, = the VM's resultsCollapsed so back agrees)
    // ↔ PEEK (~0.42) ↔ EXPANDED (~0.82, hoisted to MapScreen so BACK steps it first).
    // Drag the handle (or the list at its top) DOWN to shrink a detent, UP to grow
    // one; tap the handle to step up. The X exits the search entirely (results + query),
    // same as backing all the way out.
    var openOnly by remember { mutableStateOf(false) }
    // 0 = off; else the max price level to show (1=$ … 4=$$$$). Tapping the chip cycles.
    var priceMax by remember { mutableStateOf(0) }
    val screenH = LocalConfiguration.current.screenHeightDp
    // The list height is a hand-driven Animatable, the place sheet's physics: drags move it 1:1
    // with the finger, releases ride the throw's own decay to the nearest size (0 = the minimized
    // bar, peek, expanded), and the detent just stops the coast. Minimizing animates the list to
    // ZERO first and only then flips the collapsed state, so the bar swap happens invisibly.
    val peekL = screenH * 0.42f
    val expL = screenH * 0.82f
    val listH = remember { Animatable(if (collapsed) 0f else if (expanded) expL else peekL) }
    val resultsSettleSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 350f) }
    // Dropping to the bar GLIDES on a soft spring — at the settle stiffness the pan-triggered
    // drop read as an abrupt blink (user 2026-07-10). Growing keeps the quicker settle so taps
    // feel responsive.
    val resultsGlideSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 140f) }
    val resultsDecay = remember { exponentialDecay<Float>(frictionMultiplier = 1.6f) }
    LaunchedEffect(collapsed, expanded) {
        val target = if (collapsed) 0f else if (expanded) expL else peekL
        if (listH.targetValue != target) listH.animateTo(target, if (target == 0f) resultsGlideSpec else resultsSettleSpec)
    }
    // Map grabbed: glide the OPEN list down to zero and only then flip collapsed — the same
    // order the drag path uses. Flipping first unmounts the list mid-drop (the visible "pop").
    // seenTick consumes the mount-time value so a REMOUNT (returning from a place sheet) can't
    // replay a stale pan as a fresh minimize (same guard as the place sheet).
    var seenTick by remember { mutableStateOf(minimizeTick) }
    LaunchedEffect(minimizeTick) {
        if (minimizeTick == seenTick || collapsed) return@LaunchedEffect
        seenTick = minimizeTick
        listH.animateTo(0f, resultsGlideSpec)
        onMinimize()
    }
    val listState = rememberLazyListState()
    val minimize = rememberUpdatedState(onMinimize)
    val expand = rememberUpdatedState(onExpand)
    val isCollapsed = rememberUpdatedState(collapsed)
    val isExpanded = rememberUpdatedState(expanded)
    val setExpanded = rememberUpdatedState(onExpandedChange)
    val density = LocalDensity.current
    val sheetScope = rememberCoroutineScope()
    fun dragListBy(dyPx: Float) {
        val dyDp = with(density) { dyPx.toDp().value }
        sheetScope.launch { listH.snapTo((listH.value - dyDp).coerceIn(0f, expL)) }
    }
    fun settleList(velocityPxPerSec: Float) {
        val vDp = with(density) { velocityPxPerSec.toDp().value }
        val naturalEnd = resultsDecay.calculateTargetValue(listH.value, -vDp)
        // Flick = commit at least one detent in the flick's direction (the place sheet's
        // grammar; the pure projection made short flicks feel dead - user 2026-07-11).
        val detents = listOf(0f, peekL, expL)
        val target = when {
            vDp < -app.vela.ui.place.FLING_COMMIT_DPS -> {
                val up = detents.filter { it > listH.value + 1f }
                maxOf(up.minOrNull() ?: expL, up.minByOrNull { kotlin.math.abs(it - naturalEnd) } ?: expL)
            }
            vDp > app.vela.ui.place.FLING_COMMIT_DPS -> {
                val down = detents.filter { it < listH.value - 1f }
                minOf(down.maxOrNull() ?: 0f, down.minByOrNull { kotlin.math.abs(it - naturalEnd) } ?: 0f)
            }
            else -> detents.minByOrNull { kotlin.math.abs(it - naturalEnd) } ?: peekL
        }
        sheetScope.launch {
            // States first for peek/expanded so everything keyed on them stays honest; the
            // MINIMIZED flip waits until the list has ridden down to zero (see above).
            if (target != 0f) {
                if (isCollapsed.value) expand.value()
                setExpanded.value(target == expL)
            }
            val towardTarget = (naturalEnd - listH.value) * (target - listH.value) > 0f
            if (towardTarget && kotlin.math.abs(naturalEnd - listH.value) >= kotlin.math.abs(target - listH.value)) {
                try {
                    listH.updateBounds(lowerBound = minOf(listH.value, target), upperBound = maxOf(listH.value, target))
                    listH.animateDecay(-vDp, resultsDecay)
                } finally {
                    listH.updateBounds(lowerBound = null, upperBound = null)
                }
                if (kotlin.math.abs(listH.value - target) > 0.5f) listH.animateTo(target, resultsSettleSpec)
            } else {
                listH.animateTo(target, resultsSettleSpec, initialVelocity = -vDp)
            }
            if (target == 0f && !isCollapsed.value) minimize.value()
        }
    }
    val dismissConn = remember {
        object : NestedScrollConnection {
            private var draggingSheet = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop && listH.value > 0f) {
                    draggingSheet = true
                    dragListBy(available.y)
                    return available
                }
                if (available.y < 0f && listH.value < expL) {
                    draggingSheet = true
                    dragListBy(available.y)
                    return available
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (draggingSheet) {
                    draggingSheet = false
                    settleList(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    // Sort: 0 = relevance (Google's order), 1 = rating, 2 = distance. Picked from a menu — a
    // cycling chip hid what the options even were (user 2026-07-10, same for price + rating).
    var sortMode by remember { mutableStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var priceMenu by remember { mutableStateOf(false) }
    var ratingMenu by remember { mutableStateOf(false) }
    // Rating floor: 0 = off, else 3.5 / 4.0 / 4.5 (Google's tiers).
    var minRating by remember { mutableStateOf(0.0) }
    // Wheelchair accessible only — the one attribute the keyless search response carries
    // per result (see Place.wheelchairAccessible); the rest of Google's attribute filters
    // would need a details fetch per place.
    var accessibleOnly by remember { mutableStateOf(false) }
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
        .let { list -> if (minRating > 0.0) list.filter { (it.rating ?: 0.0) >= minRating } else list }
        .let { list -> if (priceMax > 0) list.filter { (it.priceLevel ?: Int.MAX_VALUE) <= priceMax } else list }
        .let { list -> if (accessibleOnly) list.filter { it.wheelchairAccessible } else list }
        .let { list ->
            when (sortMode) {
                1 -> list.sortedByDescending { it.rating ?: -1.0 }
                2 -> list.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
                else -> list
            }
        }
    // Tell the map which pins survived (sort doesn't change membership, so it isn't a key).
    LaunchedEffect(openOnly, minRating, priceMax, accessibleOnly, results) {
        onShownChange(
            if (!openOnly && minRating == 0.0 && priceMax == 0 && !accessibleOnly) null
            else shown.mapTo(HashSet()) { it.id },
        )
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
                    .pointerInput(Unit) {
                        // Same physics as the place sheet (the shared sheetDragGestures grammar).
                        sheetDragGestures(
                            dragBy = { dy ->
                                if (isCollapsed.value && dy < 0f) expand.value() // list mounts at 0 and grows with the finger
                                dragListBy(dy)
                            },
                            settle = { settleList(it) },
                        )
                    },
            ) {
                // The handle pill is a real focus stop under D-pad: OK steps the sheet up a
                // detent (the tap grammar), BACK steps it down as always - the tap/drag gestures
                // on the Column above have no key path of their own (docs/dpad.md).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .dpadHighlight(RoundedCornerShape(6.dp))
                        .onKeyEvent { ev ->
                            if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                                if (collapsed) onExpand() else onExpandedChange(!expanded)
                                true
                            } else {
                                false
                            }
                        }
                        .focusable(),
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
                        .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The bar says WHAT you're looking at, not just how many: the list name or
                    // the search text leads in full ink with the count on its own line UNDER it —
                    // the inline "title · count" floated awkwardly against the right-side buttons
                    // (user 2026-07-10); stacked left-aligned lines read like a proper header.
                    val barTitle = listName ?: query.trim().ifBlank { null }
                    Column(Modifier.weight(1f)) {
                        if (barTitle != null) {
                            Text(
                                barTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = SheetPalette.ink(dark),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            androidx.compose.ui.res.pluralStringResource(R.plurals.mapscreen_results_count, shown.size, shown.size),
                            style = if (barTitle != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = SheetPalette.dim(dark),
                            maxLines = 1,
                        )
                    }
                    // Circled like the place-sheet header (one control language). The chevron
                    // STAYS: it's the discoverable expand affordance AND the D-pad path (the
                    // handle's tap detector isn't focusable) — removing it would orphan keypad
                    // users (user 2026-07-11).
                    app.vela.ui.place.HeaderCircleButton(
                        if (!collapsed && expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        if (!collapsed && expanded) stringResource(R.string.mapscreen_shrink_list) else stringResource(R.string.mapscreen_expand_list),
                        tint = SheetPalette.ink(dark),
                        bg = SheetPalette.dim(dark),
                    ) { if (collapsed) onExpand() else onExpandedChange(!expanded) }
                    Spacer(Modifier.width(8.dp))
                    app.vela.ui.place.HeaderCircleButton(
                        Icons.Default.Close,
                        stringResource(R.string.mapscreen_close_results),
                        tint = SheetPalette.ink(dark),
                        bg = SheetPalette.dim(dark),
                    ) { onClose() }
                    Spacer(Modifier.width(8.dp))
                }
                // The chips (and the divider under them) FOLD with the sheet height over
                // its last 140dp of travel (SheetFold, the place sheet's minimize primitive):
                // by the time only the bar remains they are zero-height, so the collapsed
                // flip removes nothing visible - they used to pop out at the flip while the
                // sheet itself had already stopped moving (user 2026-07-11).
                val chipsFraction: () -> Float = { (listH.value / 140f).coerceIn(0f, 1f) }
                app.vela.ui.SheetFold(composed = !collapsed, fraction = chipsFraction) {
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
                    // Sort: a menu (Relevance / Rating / Distance). LEFT of the filters like
                    // Google's results header (user 2026-07-13).
                    Box {
                        ElevatedFilterChip(
                            selected = sortMode > 0,
                            onClick = { sortMenu = true },
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
                            trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        VelaMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            item(stringResource(R.string.mapscreen_sort_relevance)) { sortMode = 0; sortMenu = false }
                            item(stringResource(R.string.mapscreen_sort_rating_item)) { sortMode = 1; sortMenu = false }
                            item(stringResource(R.string.mapscreen_sort_distance_item)) { sortMode = 2; sortMenu = false }
                        }
                    }
                    // Rating floor: a MENU of Google's tiers (3.5+/4.0+/4.5+) — the old fixed
                    // 4.0★ toggle couldn't say what it did or offer another bar.
                    Box {
                        ElevatedFilterChip(
                            selected = minRating > 0.0,
                            onClick = { ratingMenu = true },
                            label = { Text(if (minRating > 0.0) String.format(Locale.US, "%.1f+ ★", minRating) else stringResource(R.string.mapscreen_filter_rating)) },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            colors = chipColors,
                            border = null,
                            trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        VelaMenu(expanded = ratingMenu, onDismissRequest = { ratingMenu = false }) {
                            item(stringResource(R.string.mapscreen_filter_any_rating)) { minRating = 0.0; ratingMenu = false }
                            item("3.5+ ★") { minRating = 3.5; ratingMenu = false }
                            item("4.0+ ★") { minRating = 4.0; ratingMenu = false }
                            item("4.5+ ★") { minRating = 4.5; ratingMenu = false }
                        }
                    }
                    // Price ceiling: a menu of the four levels instead of blind cycling.
                    Box {
                        ElevatedFilterChip(
                            selected = priceMax > 0,
                            onClick = { priceMenu = true },
                            label = { Text(if (priceMax == 0) stringResource(R.string.mapscreen_filter_price) else "≤ " + "$".repeat(priceMax)) },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            colors = chipColors,
                            border = null,
                            trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        VelaMenu(expanded = priceMenu, onDismissRequest = { priceMenu = false }) {
                            item(stringResource(R.string.mapscreen_filter_any_price)) { priceMax = 0; priceMenu = false }
                            (1..4).forEach { lvl ->
                                item("$".repeat(lvl)) { priceMax = lvl; priceMenu = false }
                            }
                        }
                    }
                    // Wheelchair accessible — the one attribute filter the keyless response supports.
                    ElevatedFilterChip(
                        selected = accessibleOnly,
                        onClick = { accessibleOnly = !accessibleOnly },
                        label = { Text(stringResource(R.string.mapscreen_filter_accessible)) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = chipColors,
                        border = null,
                        leadingIcon = if (accessibleOnly) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                }
                Divider()
                } // SheetFold - chips
            }
            if (!collapsed) {
            LazyColumn(
                Modifier
                    .nestedScroll(dismissConn)
                    .layout { measurable, constraints ->
                        // Layout-phase read: animation frames re-layout the list, never recompose it.
                        val maxHPx = listH.value.dp.roundToPx().coerceAtLeast(0)
                        val pl = measurable.measure(constraints.copy(maxHeight = minOf(constraints.maxHeight, maxHPx)))
                        layout(pl.width, pl.height) { pl.place(0, 0) }
                    },
                state = listState,
            ) {
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
                    // Gas stations: the live price on its own line under the address, bold with a
                    // pump glyph in the title ink so it pops out of the row (user 2026-07-10).
                    place.fuelPrice?.let { fp ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = SheetPalette.ink(dark),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                fp,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = SheetPalette.ink(dark),
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
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
                    // The owner's note on a saved place — the whole point of putting it in a
                    // list ("this restaurant's fish is better than its chicken"), so the list
                    // view must show it, not just the place sheet.
                    place.savedNote?.let { note ->
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.FormatQuote,
                                contentDescription = null,
                                tint = SheetPalette.dim(dark),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                note,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = SheetPalette.ink(dark),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
private fun CategoryChips(onPick: (String) -> Unit, onOpenLists: () -> Unit = {}, modifier: Modifier = Modifier) {
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
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ribbon shortcut to Your lists — leads the row, a round icon button. Light mode keeps
        // the subtle green secondaryContainer (user liked it); dark mode matches the chips'
        // neutral elevated grey — the green container read "too green" against the dark map.
        val dark = isAppInDarkTheme()
        Surface(
            onClick = onOpenLists,
            shape = CircleShape,
            color = if (dark) MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp) else MaterialTheme.colorScheme.secondaryContainer,
            // Soft glyph ink both modes - onSecondaryContainer read near-black next to the
            // grey chip glyphs (user 2026-07-11).
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = 2.dp,
            modifier = Modifier.dpadHighlight(CircleShape).size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Bookmarks, contentDescription = stringResource(R.string.mapscreen_section_lists), modifier = Modifier.size(20.dp))
            }
        }
        categories.forEach { (labelRes, query, icon) ->
            ElevatedAssistChip(
                onClick = { onPick(query) },
                modifier = Modifier.dpadHighlight(RoundedCornerShape(8.dp)),
                label = { Text(stringResource(labelRes)) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                // Full pill, Google-style — the M3 default 8dp corners read dated on a map chip row.
                shape = androidx.compose.foundation.shape.CircleShape,
                // MONOCHROME glyphs (user 2026-07-06), SOFT ink (user 2026-07-11): the M3
                // default tints the icon primary teal; full onSurface made the solid glyph
                // read DARKER than the label. onSurfaceVariant matches the glyph weight to
                // the text, like Google's rows.
                colors = androidx.compose.material3.AssistChipDefaults.elevatedAssistChipColors(
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // Theme-following surface: the old inverseSurface is INVERTED by definition, which
            // put a light banner on the dark app (user 2026-07-14).
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
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
                        when (target) {
                            MapPick.ORIGIN -> R.string.mapscreen_choose_origin_hint
                            MapPick.DEST -> R.string.mapscreen_choose_dest_hint
                            else -> R.string.mapscreen_choose_stop_hint
                        },
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
                    when (target) {
                        MapPick.ORIGIN -> R.string.mapscreen_choose_set_start
                        MapPick.DEST -> R.string.mapscreen_choose_set_dest
                        else -> R.string.mapscreen_choose_set_stop
                    },
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
    recents: List<RecentQuery>,
    recentPlaces: List<RecentPlace>,
    home: SavedPlace?,
    work: SavedPlace?,
    assigning: ShortcutKind?,
    pickingOrigin: Boolean = false,
    pickingDest: Boolean = false,
    pickingStop: Boolean = false,
    onCancelPickStop: () -> Unit = {},
    onUseMyLocation: () -> Unit = {},
    onChooseOnMap: () -> Unit = {},
    onPickSuggestion: (Place) -> Unit,
    onPickSaved: (SavedPlace) -> Unit,
    onPickRecent: (String) -> Unit,
    onPickRecentPlace: (SavedPlace) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onRemoveRecentPlace: (String) -> Unit,
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
        if (pickingOrigin || pickingDest || pickingStop) {
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
        // ONE chronological "Recent" list — Google mixes recently-viewed places and recent
        // searches by time rather than bucketing them; the icon tells them apart (pin for a
        // place you opened, clock for a query you typed).
        if (recentPlaces.isNotEmpty() || recents.isNotEmpty()) {
            SectionLabel(stringResource(R.string.mapscreen_section_recent))
            val merged: List<Any> = (recentPlaces + recents).sortedByDescending { entry ->
                when (entry) {
                    is RecentPlace -> entry.at
                    is RecentQuery -> entry.at
                    else -> 0L
                }
            }
            merged.forEach { entry ->
                when (entry) {
                    is RecentPlace -> SuggestionRow(
                        icon = Icons.Default.Place,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        label = entry.place.name,
                        // A real place shows its address under the name (Google-style).
                        sublabel = entry.place.address,
                        onClick = { onPickRecentPlace(entry.place) },
                        onRemove = { onRemoveRecentPlace(entry.place.id) },
                    )
                    is RecentQuery -> SuggestionRow(
                        icon = Icons.Default.History,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        label = entry.query,
                        onClick = { onPickRecent(entry.query) },
                        onRemove = { onRemoveRecent(entry.query) },
                    )
                }
                Divider()
            }
            TextButton(onClick = onClearRecents, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.mapscreen_clear_recents))
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
            // Unset rows share the page's glyph ink (onSurfaceVariant) - the old SheetPalette
            // dim was LIGHTER than the recents pin beside it and the pair read mismatched
            // (user 2026-07-11). Set rows keep the primary tint (they carry state).
            tint = if (place != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = SheetPalette.ink(dark))
            Text(
                place?.let { it.address ?: it.name }
                    ?: stringResource(R.string.mapscreen_set_shortcut_address, label.lowercase()),
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
                    // Same ink as the row's text - the default LocalContentColor went near-black
                    // on the fixed sheet grey under some themes (user report).
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.mapscreen_edit_shortcut, label),
                        tint = SheetPalette.ink(dark),
                    )
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
        // Explicit colours: the search page is a background()-Box, not a Surface, so
        // LocalContentColor is unset and a colourless Text/Icon renders BLACK in dark
        // mode (same trap ShortcutRow documents). Match the SuggestionRow siblings.
        Text(
            place.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.mapscreen_saved_place_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            // LocalContentColor is NOT set for it — a colourless Text falls back to BLACK and
            // vanishes on the dark sheet. Same convention as SuggestionRow.
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.mapscreen_cancel)) }
    }
}

/** A quiet mode label while picking a place to add as a directions stop — without it the
 *  Add-stop picker is visually identical to plain search. The old full banner with its own
 *  Cancel button read as clutter sitting right above the Choose-on-map row (user 2026-07-14);
 *  the search bar's back arrow and hardware BACK are the way out, like every other search mode. */
@Composable
private fun PickStopBanner(@Suppress("UNUSED_PARAMETER") onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AddLocationAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.mapscreen_pick_stop_hint),
            style = MaterialTheme.typography.labelLarge,
            // Explicit colour: no Surface on the search page means no LocalContentColor.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().dpadHighlight(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp), tint = tint)
        if (sublabel == null) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onRemove != null) Modifier.weight(1f) else Modifier,
            )
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
        // Per-row remove (the X on recents). Its own focus stop with a visible ring, so a D-pad
        // walk can reach it after the row itself.
        if (onRemove != null) {
            // Same circle language as the sheet headers, sized down for a list row.
            app.vela.ui.place.HeaderCircleButton(
                Icons.Default.Close,
                stringResource(R.string.mapscreen_menu_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                bg = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 32.dp,
            ) { onRemove() }
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
    pillLabel: String? = null,
    onPill: (() -> Unit)? = null,
) {
    // Fixed sheet palette so this banner reads as the same grey as the place sheet
    // and results list, not a wallpaper-tinted Material card.
    val dark = isAppInDarkTheme()
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SheetPalette.bg(dark)),
    ) {
        if (pillLabel != null && onPill != null) {
            // With a primary action the card takes the UpdateCard layout: text block, then a
            // trailing row of quiet-dismiss + filled pill (reads by shape/fill, colour-blind safe).
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = SheetPalette.ink(dark))
                Text(body, style = MaterialTheme.typography.bodySmall, color = SheetPalette.dim(dark))
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onPill,
                        shape = CircleShape,
                        modifier = Modifier.dpadHighlight(CircleShape),
                    ) { Text(pillLabel) }
                }
            }
        } else {
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
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                    Spacer(Modifier.width(4.dp))
                    // A filled primary pill, same treatment as the dialogs' confirm button: the
                    // action reads by SHAPE and fill, not by text colour alone (colour-blind safe).
                    Button(
                        onClick = onUpdate,
                        shape = CircleShape,
                        modifier = Modifier.dpadHighlight(CircleShape),
                    ) { Text(stringResource(R.string.update_install)) }
                }
            }
        }
    }
}

/** A notice pushed through the signed calibration channel - level-tinted, with an
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
            // Explicit contrast pairs, not the button defaults: under Material You the default
            // TextButton primary and this card's tertiaryContainer both derive from the wallpaper
            // and routinely land on near-identical pastels - the "No" all but vanished and the
            // "Switch" fill could blend into the card (user 2026-07-14). onTertiaryContainer is
            // contrast-guaranteed against tertiaryContainer in every scheme, so the dismiss reads
            // everywhere, and the confirm wears the inverse fill for the same guarantee.
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
            ) { Text(stringResource(R.string.mapscreen_no)) }
            Button(
                onClick = onSwitch,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) { Text(stringResource(R.string.mapscreen_switch)) }
        }
    }
}

/** Manage-lists dialog (Your lists): create, rename, restyle or delete a list;
 *  tapping one opens its places. */
@Composable
private fun ListsSheet(
    lists: List<app.vela.core.model.PlaceList>,
    onOpenList: (String) -> Unit,
    onCreateList: (String) -> String,
    onUpdateList: (app.vela.core.model.PlaceList) -> Unit,
    onDeleteList: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<app.vela.core.model.PlaceList?>(null) }
    var creating by remember { mutableStateOf(false) }
    // D-pad-first initial focus (hard rule, docs/dpad.md): a raw Dialog must place focus
    // itself - land it on the New-list button so the menu opens usable with no wasted press.
    val listsAutoFocus = app.vela.ui.rememberDpadAutoFocus()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp).widthIn(max = 420.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.mapscreen_section_lists),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { creating = true }, modifier = Modifier.focusRequester(listsAutoFocus).dpadHighlight(RoundedCornerShape(20.dp))) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.mapscreen_new_list))
                    }
                }
                if (lists.isEmpty()) {
                    Text(
                        stringResource(R.string.lists_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(lists, key = { it.id }) { list ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .dpadHighlight(RoundedCornerShape(8.dp))
                                .clickable { onOpenList(list.id) }
                                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ListIconBadge(list.icon, Color(list.color), 22.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(list.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(R.string.lists_place_count, list.places.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { editing = list }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.list_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }
    if (creating) {
        ListEditorDialog(
            initial = null,
            onSave = { name, icon, color -> onCreateList(name); creating = false },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
    editing?.let { list ->
        ListEditorDialog(
            initial = list,
            onSave = { name, icon, color -> onUpdateList(list.copy(name = name, icon = icon, color = color)); editing = null },
            onDelete = { onDeleteList(list.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** Parking history menu (long-press the P button). Restore an older spot after an accidental
 *  overwrite; delete stale entries. Newest first; the one matching the current spot is tagged. */
@Composable
private fun ParkingHistorySheet(
    history: List<app.vela.core.model.ParkedSpot>,
    currentAtMillis: Long,
    onRestore: (app.vela.core.model.ParkedSpot) -> Unit,
    onDelete: (app.vela.core.model.ParkedSpot) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isAppInDarkTheme()
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
                    TextButton(onClick = onClearAll) { Text(stringResource(R.string.parking_history_clear_all)) }
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

// The list icon set (keys stored in PlaceList.icon). Small, recognisable, Google-list-like.
private val LIST_ICONS: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> = listOf(
    "bookmark" to Icons.Default.Bookmark,
    "star" to Icons.Default.Star,
    "favorite" to Icons.Default.Favorite,
    "flag" to Icons.Default.Flag,
    "place" to Icons.Default.Place,
    "restaurant" to Icons.Default.Restaurant,
    "car" to Icons.Default.DirectionsCar,
    "home" to Icons.Default.Home,
    "work" to Icons.Default.Work,
    "shopping" to Icons.Default.ShoppingCart,
)
private val LIST_COLORS: List<Long> = listOf(
    0xFF1A73E8, 0xFF00897B, 0xFFE8710A, 0xFFD93025, 0xFF9334E6, 0xFF1E8E3E, 0xFFF9AB00, 0xFF5F6368,
)

private fun listIcon(key: String): androidx.compose.ui.graphics.vector.ImageVector =
    LIST_ICONS.firstOrNull { it.first == key }?.second ?: Icons.Default.Bookmark

// A curated emoji palette for list icons (issue #173) — picked from a D-pad-focusable grid, so
// keypad phones (whose keyboards have no emoji) get the feature too; touch users can also type
// any emoji into the free-text field. Stored as "emoji:X" in PlaceList.icon (old payloads and
// old builds decode it fine: unknown keys fall back to the bookmark vector).
private val LIST_EMOJI = listOf(
    "❤️", "⭐", "📍", "🎯", "🍕", "🍔", "🍜", "🍣", "🌮", "☕", "🍰", "🍺",
    "🛒", "🎁", "🏖️", "⛰️", "🎡", "🎬", "🎵", "🏛️", "⛽", "🏥", "🐾", "✈️",
)

/** Renders a list's icon: the emoji itself for an "emoji:X" key, else the tinted vector. */
@Composable
private fun ListIconBadge(key: String, tint: Color, size: androidx.compose.ui.unit.Dp) {
    if (key.startsWith("emoji:")) {
        Text(key.removePrefix("emoji:"), fontSize = with(LocalDensity.current) { (size * 0.86f).toSp() })
    } else {
        Icon(listIcon(key), contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** Create / edit a place-list: name, icon and colour; Delete when editing. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ListEditorDialog(
    initial: app.vela.core.model.PlaceList?,
    onSave: (name: String, icon: String, color: Long) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "bookmark") }
    var color by remember { mutableStateOf(initial?.color ?: LIST_COLORS.first()) }
    var emojiOpen by remember { mutableStateOf(false) }
    var emojiText by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).widthIn(max = 420.dp)) {
                Text(
                    stringResource(if (initial == null) R.string.list_new_title else R.string.list_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.list_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.list_icon_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LIST_ICONS.forEach { (key, vec) ->
                        val sel = key == icon
                        Surface(
                            shape = CircleShape,
                            color = if (sel) Color(color).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (sel) BorderStroke(2.dp, Color(color)) else null,
                            modifier = Modifier.size(44.dp).dpadHighlight(CircleShape).clickable { icon = key },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(vec, contentDescription = key, tint = Color(color), modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    // Custom EMOJI icon (issue #173): the tail tile shows the picked emoji (selected
                    // state) or a face, and toggles the picker grid below. The grid, not a bare text
                    // field, is the primary picker so key-only phones can use it (docs/dpad.md).
                    val isEmoji = icon.startsWith("emoji:")
                    Surface(
                        shape = CircleShape,
                        color = if (isEmoji) Color(color).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isEmoji) BorderStroke(2.dp, Color(color)) else null,
                        modifier = Modifier.size(44.dp).dpadHighlight(CircleShape).clickable { emojiOpen = !emojiOpen },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (isEmoji) icon.removePrefix("emoji:") else "😀", fontSize = 20.sp)
                        }
                    }
                }
                if (emojiOpen) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LIST_EMOJI.forEach { e ->
                            val sel = icon == "emoji:$e"
                            Surface(
                                shape = CircleShape,
                                color = if (sel) Color(color).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (sel) BorderStroke(2.dp, Color(color)) else null,
                                modifier = Modifier.size(40.dp).dpadHighlight(CircleShape).clickable { icon = "emoji:$e" },
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 18.sp) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Free-type field for any emoji the grid lacks (touch keyboards have the full set).
                    OutlinedTextField(
                        value = emojiText,
                        onValueChange = { v ->
                            emojiText = v.take(16)
                            if (emojiText.isNotBlank()) icon = "emoji:${emojiText.trim()}"
                        },
                        label = { Text(stringResource(R.string.list_emoji_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.list_color_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIST_COLORS.forEach { c ->
                        val sel = c == color
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .dpadHighlight(CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.list_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.list_cancel)) }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), icon, color) }, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.list_save))
                    }
                }
            }
        }
    }
}

/**
 * The posted speed-limit sign shown by the speedometer during nav - US MUTCD style (white rounded
 * rectangle, "SPEED LIMIT" + number) in imperial units, EU/RoW style (white disc, red ring, number)
 * in metric. The number turns red when the current GPS speed exceeds the limit by a tolerance (GPS
 * speed is noisy, so a plain > would flap). [limitKmh] is the OSM/GraphHopper value in km/h.
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

    // ONE rounded rectangle in both states (user 2026-07-14): without a posted limit it's a
    // snug box around just the speed, and when a limit is known the box simply WIDENS as the
    // sign joins it - the same surface growing, never a second widget or a shape change.
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SheetPalette.bg(dark),
        contentColor = SheetPalette.ink(dark),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (limitDisp != null) {
                if (imperial) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, signInk),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
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
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("$limitDisp", color = if (over) overColor else signInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$value", fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp, color = if (over) overColor else SheetPalette.ink(dark))
                Text(unit, fontSize = 9.sp, color = SheetPalette.dim(dark), lineHeight = 10.sp)
            }
        }
    }
}
