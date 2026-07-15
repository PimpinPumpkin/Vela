package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import app.vela.offline.OfflineMaps
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.ShoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MLLatLng
import org.maplibre.android.geometry.LatLngBounds as MLLatLngBounds

private const val ROUTE_SRC = "vela-route-src"
private const val ROUTE_LAYER = "vela-route"

// The active route stripe's zoom curve (and the alt routes a step thinner): 6 px was constant at
// every zoom and read THIN at nav zooms next to Google's fat stripe (user 2026-07-15). Values are
// Google-eyeballed: browse ~unchanged, street-level noticeably wider.
private val ROUTE_WIDTH = Expression.interpolate(
    Expression.exponential(1.5f), Expression.zoom(),
    Expression.stop(10, 5f), Expression.stop(14, 7f), Expression.stop(16, 10f), Expression.stop(18.5f, 17f),
)
private val ALT_ROUTE_WIDTH = Expression.interpolate(
    Expression.exponential(1.5f), Expression.zoom(),
    Expression.stop(10, 4f), Expression.stop(14, 5.5f), Expression.stop(16, 8f), Expression.stop(18.5f, 13f),
)
// A second line on the SAME route source, drawn dashed (Google-style for walking/biking).
// Two layers + visibility toggle, because MapLibre's line-dasharray DISABLES line-gradient —
// so the solid driving line (traffic gradient) and the dashed foot/bike line can't share one.
private const val ROUTE_DASH_LAYER = "vela-route-dash"
private const val ROUTE_DOT_IMG = "vela-route-dot"
private const val ROUTE_DOT_SRC = "vela-route-dot-src"
// Target centre-to-centre dot spacing in MapLibre's density-independent px (dp) — the unit
// getMetersPerPixelAtLatitude works in. ~17dp = a ~10dp dot + a ~7dp gap, Google's dense chain.
// Held constant at EVERY zoom by regenerating the dot POINTS ourselves (see regenRouteDots).
private const val ROUTE_DOT_SPACING_PX = 17.0
// The AHEAD half of the nav route. During nav the driven/ahead cut is a GEOMETRY split, not a
// gradient stop: MapLibre rasterizes line-gradient into a 256×1 LINEAR-filtered texture, so a
// "hard" step() cut renders as a grey→blue fade of routeLength/256 metres (~39 m on a 10 km
// route — the "gradient appears if we zoom in" bug) with the centre quantized to the nearest
// texel. Geometry is pixel-exact at any zoom/length: ROUTE_LAYER shows the full line in
// traversed grey underneath; this layer draws the REMAINING suffix from the puck forward
// (frame-ticker-updated, traffic spans remapped onto the suffix).
private const val ROUTE_AHEAD_SRC = "vela-route-ahead-src"
private const val ROUTE_AHEAD_LAYER = "vela-route-ahead"
// Traversed-route grey, per theme — dimmer than and distinct from the alternates' #9AA0A6 so
// the driven tail doesn't read as another tappable route.
private const val TRAVERSED_LIGHT = "#B9BDC2"
private const val TRAVERSED_DARK = "#54585C"
private const val ALT_ROUTE_SRC = "vela-alt-route-src"
private const val ALT_ROUTE_LAYER = "vela-alt-route"
private const val ALT_INDEX_PROP = "vela-alt-index"
private const val MARKERS_SRC = "vela-markers-src"
private const val MARKERS_LAYER = "vela-markers"
// Collapsed search results: the same source drawn as small red dots UNDER the pins. The pin layer
// collides (best rank wins the slot); a result whose pin is culled still shows as its dot, which
// "expands" into the pin as you zoom in - Google's dense-results behaviour.
private const val MARKERS_DOTS_LAYER = "vela-markers-dots"
private const val PIN_IMG = "vela-pin"
// The saved parking spot: one teal "P" pin, tappable (opens the parked-car sheet).
private const val PARKING_SRC = "vela-parking-src"
private const val PARKING_LAYER = "vela-parking"
private const val PARKING_IMG = "vela-parking-img"
private const val MARKER_INDEX_PROP = "vela-marker-index"
// Ambient Google POIs — small category dots (reusing PoiIcons' `vela-poi-<group>` images), the
// "Google for the businesses" layer that replaces the OSM business POIs on the bare browse map.
private const val AMBIENT_SRC = "vela-ambient-src"
private const val AMBIENT_LAYER = "vela-ambient"
private const val AMBIENT_DOT_LAYER = "vela-ambient-dots"
private const val CONTROLS_SRC = "vela-controls-src" // OSM traffic lights + stop signs drawn at high zoom
private const val CONTROLS_LAYER = "vela-controls"
private const val CONTROLS_CLAIM_LAYER = "vela-controls-claim" // invisible collision box over the labels
private const val SIGNAL_IMG = "vela-signal"
private const val STOP_IMG = "vela-stop"
private const val FLOCK_SRC = "vela-flock-src" // ALPR/Flock cameras (DeFlock/OSM) drawn at high zoom
private const val FLOCK_LAYER = "vela-flock"
private const val FLOCK_IMG = "vela-flock-cam"
private const val TRANSIT_STOPS_SRC = "vela-transit-stops-src" // canonical GTFS stops (Transitous)
private const val TRANSIT_STOPS_LAYER = "vela-transit-stops"
private const val TRANSIT_STOP_IMG = "vela-transit-stop"
private const val TRANSIT_STOP_INDEX_PROP = "vela_transit_stop_index"
private const val AMBIENT_INDEX_PROP = "vela-ambient-index"
private const val ACCURACY_SRC = "vela-me-accuracy-src"
private const val ACCURACY_LAYER = "vela-me-accuracy"
// Only a genuinely vague fix earns the halo: coarse-permission and network fixes report hundreds to
// thousands of meters; normal GPS (3-30 m) stays a plain dot.
private const val ACCURACY_HALO_MIN_M = 100f
private const val ME_SRC = "vela-me-src"
private const val ME_LAYER = "vela-me"
private const val ME_ARROW_LAYER = "vela-me-arrow"
private const val ME_ARROW_IMG = "vela-arrow"
private const val NAV_PUCK_IMG = "vela-nav-puck"
private const val PREVIEW_SRC = "vela-preview-src"
private const val PREVIEW_LAYER = "vela-preview"
private const val DEM_SRC = "vela-dem"
private const val HILLSHADE_LAYER = "vela-hillshade"
// Keyless open elevation tiles (AWS Open Data, terrarium-encoded) — no key, and
// no CORS to worry about on native. Gives Google-style terrain relief.
private const val TERRARIUM_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

private const val TRAFFIC_SRC = "vela-traffic-src"
private const val STOPNUM_SRC = "vela-stopnums-src"
private const val STOPNUM_LAYER = "vela-stopnums"
private const val TRAFFIC_LAYER = "vela-traffic"
// Rail-highlight layer (train + subway/tram), drawn over the basemap's own transportation lines.
private const val TRANSIT_LAYER = "vela-transit"
private const val TRANSIT_RAIL = "#7E57C2"   // heavy rail — purple
private const val TRANSIT_SUBWAY = "#12B5A5" // subway / light rail / tram — teal
// Google's LIVE traffic, as a raster overlay (congestion-coloured roads +
// incidents) — the web map's own `/maps/vt` tile, which is a public, keyless PNG
// on www.google.com (the same host we already scrape). The trimmed `pb` (no map
// version epoch, so it doesn't rot): `!2straffic` = the traffic layer, `!1e2` =
// overlay. Standard XYZ tile coords (`!1i{z}!2i{x}!3i{y}`).
private const val TRAFFIC_TILES =
    "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
        "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"

/** A tappable search-result pin on the map. [prominence] (0 = unknown/low) drives the ambient dot's
 *  size + keep-distance so anchor stores read bigger and show from farther, Google-style. */
data class MapMarker(val name: String, val location: LatLng, val category: String? = null, val prominence: Double = 0.0, val rating: Double? = null, val fuelPrice: String? = null)

// Last marker/ambient lists actually pushed to the GeoJSON sources, so applyData can skip a redundant
// setGeoJson (a full symbol re-tessellation) when they're unchanged. Nulled on style reload (the fresh
// source is empty and must repopulate). Single map instance, so file scope is fine.
private var lastAppliedMarkers: List<MapMarker>? = null
private var lastAppliedAmbient: List<MapMarker>? = null
private var lastAppliedParking: LatLng? = null
private var lastAccuracyLoc: LatLng? = null
private var lastAccuracyM: Float? = null
private var parkingApplied = false // distinguishes "never applied" from "applied null"
private var lastAppliedControls: List<app.vela.core.data.TrafficControl>? = null
private var lastAppliedFlock: List<app.vela.core.data.AlprCamera>? = null
private var lastAppliedTransitStops: List<app.vela.core.data.transit.Transitous.MapStop>? = null
private var lastTransitBusHidden: Boolean? = null // gate the poi_transit filter flip
private var origPoiTransitFilter: Expression? = null // basemap filter to restore when coverage goes
private var lastOsmPoiVis: String? = null // identity-gate the basemap-POI visibility flips
private var lastControlsVis: String? = null // identity-gate the traffic-control visibility flips
private var lastAppliedRouteLine: List<LatLng>? = null // identity-gate the route upload — applyData runs
                                                       // every recomposition and re-tessellating a
                                                       // thousands-of-vertices linestring per fix burned
                                                       // frame budget exactly while the ticker eased the camera
private var lastNavRouteMode = false                   // nav→browse transition clears the ahead-suffix layer once

/**
 * MapLibre wrapped for Compose. Three camera behaviours:
 *  - [navMode]: heading-up, tilted, close follow (drives like a nav app);
 *  - a fresh route preview: fit the whole route to the screen once;
 *  - otherwise: gentle north-up follow of the camera target.
 * The location dot also shows a heading arrow when a GPS bearing is available.
 */
@Composable
fun VelaMapView(
    styleUri: String,
    myLocation: LatLng?,
    myBearing: Float?,
    myAccuracyM: Float? = null,
    mySpeed: Float? = null,
    mySpeedRaw: Float? = null, // THIS fix's own measurement (null = fix had none) — Kalman feed
    // Trip-replay time scale (1 = live). The recorded fixes arrive speedup× faster than real time
    // but carry REAL speeds, so all the puck's wall-clock physics (dead-reckon integration, blind
    // window, easing time-constants, plausibility caps) must run in TRACE time or the puck reckons
    // 1/speedup of the ground covered per fix and surges to catch up — the "stuttery arrow +
    // pulsing mph" replay artifact. At speedup=1 every formula is byte-identical to before.
    replaySpeedup: Float = 1f,
    compassHeading: Float? = null, // device facing (sensor); points the browse cone when stopped
    locationStale: Boolean = false,
    cameraTarget: LatLng?,
    cameraTargetZoom: Double? = null, // deep-link z= override for the target fly (null = default framing)
    recenterTick: Int = 0, // bumped on each recenter tap → force a move even if already "centered"
    cameraBottomInsetPx: Int = 0,
    cameraTopInsetPx: Int = 0, // measured top chrome (the endpoints card) - the route fit clears it
    routePolyline: List<LatLng>,
    routeColor: String,
    routeDashed: Boolean = false, // draw the route dashed (walking / biking), Google-style

    // Per-segment live traffic as (startFraction, endFraction, level) along the route
    // — colours the route line like Google (free-flow elsewhere). Empty = no live data.
    routeTrafficSpans: List<Triple<Float, Float, Int>> = emptyList(),
    alternates: List<Pair<Int, List<LatLng>>> = emptyList(),
    altColor: String = "#9AA0A6",
    onSelectAlternate: (Int) -> Unit = {},
    markers: List<MapMarker>,
    // Intermediate trip stops in VISIT ORDER - drawn as numbered teal pins (1, 2, ...) while a
    // trip is planned or driven; reordering the stops re-numbers the pins (list identity keys it).
    stopPins: List<LatLng> = emptyList(),
    frameMarkers: Boolean,
    navMode: Boolean,
    navFollowing: Boolean = true,
    navNorthUp: Boolean = false,
    // Free-drive follow (no route open): when true the camera tracks the live fix north-up and
    // the heading beam is smoothed per frame, the way the puck is during nav. The caller drops it
    // to false on a user pan and raises it again on the locate tap.
    driveFollowing: Boolean = false,
    onNavPanned: () -> Unit = {},
    ambientCoversView: Boolean = false, // viewport inside the ambient-Google fetch area → OSM POIs yield
    onUserPan: () -> Unit = {},
    onMapTap: () -> Unit = {}, // ANY map tap, before POI/marker resolution - dismiss-transients hook
    onScaleChanged: (metersPerPixel: Double) -> Unit = {},
    darkTheme: Boolean,
    applyKeylessTheme: Boolean,
    trafficOn: Boolean,
    transitOn: Boolean = false, // highlight rail (train + subway/tram) lines from the basemap tiles
    satelliteOn: Boolean = false, // Esri World Imagery raster under the symbol layers (map button)
    topographyOn: Boolean = false, // terrain-relief hillshade; OFF by default (Google-style)
    previewTarget: LatLng?,
    navOverviewTick: Int = 0, // bumped by the in-nav Overview button — fly the camera out to the whole route
    onPoiTap: (name: String, location: LatLng, poiKind: String?) -> Unit,
    onMarkerTap: (index: Int) -> Unit,
    parkingSpot: LatLng? = null, // saved "parked here" pin; tap → onParkingTap
    onParkingTap: () -> Unit = {},
    ambientPois: List<MapMarker> = emptyList(),
    onAmbientTap: (index: Int) -> Unit = {},
    onTransitStopTap: (app.vela.core.data.transit.Transitous.MapStop) -> Unit = {},
    buildingOverlays: List<String> = emptyList(), // full pmtiles:// source URIs (file:// downloaded / https:// streamed)
    addressOverlays: List<String> = emptyList(), // pmtiles:// URIs for house-number labels (streamed, OpenAddresses)
    maxspeedOverlays: List<String> = emptyList(), // pmtiles:// URIs for the posted-speed overlay (streamed); read under the puck
    onRoadLimitKmh: (Double?) -> Unit = {}, // reports the maxspeed (km/h) read from the overlay under the puck, or null
    speedOverlayOn: Boolean = false, // only query the overlay while it can matter (driving / navigating)
    trafficControls: List<app.vela.core.data.TrafficControl> = emptyList(), // OSM lights + stop signs drawn at high zoom
    flockCameras: List<app.vela.core.data.AlprCamera> = emptyList(), // ALPR/Flock cameras drawn at high zoom
    transitStops: List<app.vela.core.data.transit.Transitous.MapStop> = emptyList(), // canonical GTFS stops at street zoom
    navBannerBottomPx: Int = 0, // measured screen-Y of the maneuver banner's bottom edge; drops the compass below it during nav
    // Compass tap: return true to CONSUME (nav uses it to toggle heading-up/north-up); false runs
    // the default reorient-to-north animation.
    onCompassTap: () -> Boolean = { false },
    onCameraIdle: (center: LatLng) -> Unit,
    onMapLongPress: (location: LatLng) -> Unit,
    onAddressLabelTap: (number: String, location: LatLng) -> Unit = { _, _ -> },
    onViewport: (south: Double, west: Double, north: Double, east: Double, zoom: Double) -> Unit = { _, _, _, _, _ -> },
    dpadController: MapDpadController? = null, // key-driven pan/zoom/select for D-pad-only devices (docs/dpad.md)
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Push MapLibre's compass below the status bar (it defaults to the top-right corner, which sits
    // *under* the status bar). During NAV the full-width maneuver banner also sits at the top and painted
    // OVER the compass — so while navigating, drop it below the banner. The banner's height VARIES (lane
    // guidance + a "then" row make it much taller), so a fixed guess couldn't clear it; MapScreen measures
    // the banner's actual bottom edge ([navBannerBottomPx]) and we sit the compass 8 dp under that. Fall back
    // to a generous fixed offset until the first measurement lands (or if it's somehow 0).
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val gap8Px = with(density) { 8.dp.roundToPx() }
    val compassTopPx = when {
        navMode && navBannerBottomPx > 0 -> navBannerBottomPx + gap8Px
        navMode -> statusBarTopPx + with(density) { 176.dp.roundToPx() }
        // Browse: below the floating search bar AND the category-chip row - 8dp under the
        // status bar put the compass exactly behind the bar (a half-hidden circle peeking
        // out its right end, clearly visible in light mode).
        else -> statusBarTopPx + with(density) { 122.dp.roundToPx() }
    }
    val compassRightPx = with(density) { 8.dp.roundToPx() }
    val poiTap = rememberUpdatedState(onPoiTap)
    val mapTap = rememberUpdatedState(onMapTap)
    val markerTap = rememberUpdatedState(onMarkerTap)
    val ambientTap = rememberUpdatedState(onAmbientTap)
    val transitStopTap = rememberUpdatedState(onTransitStopTap)
    val transitStopsNow = rememberUpdatedState(transitStops)
    val parkingTap = rememberUpdatedState(onParkingTap)
    val cameraIdle = rememberUpdatedState(onCameraIdle)
    val longPress = rememberUpdatedState(onMapLongPress)
    val addrLabelTap = rememberUpdatedState(onAddressLabelTap)
    val navPanned = rememberUpdatedState(onNavPanned)
    val userPan = rememberUpdatedState(onUserPan)
    val scaleChanged = rememberUpdatedState(onScaleChanged)
    val selectAlt = rememberUpdatedState(onSelectAlternate)
    val navModeHolder = rememberUpdatedState(navMode)
    val navFollowingHolder = rememberUpdatedState(navFollowing)
    val navNorthUpHolder = rememberUpdatedState(navNorthUp)
    val navTiltEase = remember { doubleArrayOf(55.0) } // eased so the compass toggle glides, not snaps
    val navPadEase = remember { doubleArrayOf(0.0) } // puck-low top padding as a height fraction, eased on (re)attach
    val wasNavRef = remember { booleanArrayOf(false) } // a drive actually ran - gates the one-shot camera teardown
    val onCompassTapHolder = rememberUpdatedState(onCompassTap)
    val shoving = remember { booleanArrayOf(false) } // two-finger tilt gesture in flight - ticker steps aside
    val navUserTilt = remember { doubleArrayOf(Double.NaN) } // shove-set tilt override (like navUserZoom)
    val browseZoomGoal = remember { doubleArrayOf(Double.NaN) } // locate-tap standard zoom, eased by the browse ticker
    val browseFlying = remember { booleanArrayOf(false) } // cold-engage flight in progress - ticker parks until it lands
    val myBearingHolder = rememberUpdatedState(myBearing) // vehicle course for the accel projection
    val myLocationHolder = rememberUpdatedState(myLocation)     // live fix, for the free-drive follow ticker
    val mySpeedHolder = rememberUpdatedState(mySpeed)           // live speed, for the free-drive dead reckon
    val compassHeadingHolder = rememberUpdatedState(compassHeading) // device facing, for the beam
    val driveFollowingHolder = rememberUpdatedState(driveFollowing)
    val browseCam = remember { doubleArrayOf(Double.NaN, Double.NaN) } // eased free-drive camera [lat,lng]; NaN = re-seed
    val browseBeam = remember { floatArrayOf(Float.NaN) }              // smoothed browse heading (NaN = idle, use raw)
    val browseAtt = remember { doubleArrayOf(0.0, 0.0) } // next-frame [bearing (signed), tilt], recomputed from the LIVE camera each frame
    // Free-drive dead reckon: the last fix + its speed/course, so the follow target GLIDES between
    // the ~1 Hz fixes instead of jumping and sitting (the per-second surge-and-stall jitter).
    val browseFix = remember { doubleArrayOf(0.0, 0.0, 0.0, 0.0, Double.NaN) } // [lat, lng, atMs, speed m/s, course deg]
    val browseFixRef = remember { arrayOfNulls<Any>(1) } // identity of the fix browseFix was taken from
    // Free-drive DRIVING mode (user 2026-07-15): [0] smoothed speed m/s, [1] engaged flag (latches
    // on at driving speed, released only by the follow ending - a red light must HOLD the heading,
    // not level the camera out), [2] the course the camera is easing toward, [3] eased forward
    // lookahead metres (the padding-free puck-low).
    val browseDrive = remember { doubleArrayOf(0.0, 0.0, Double.NaN, 0.0) }
    val lastBrowse = remember { doubleArrayOf(Double.NaN, Double.NaN, Double.NaN) } // last applied [lat,lng,bearing] (skip no-op frames)
    val viewport = rememberUpdatedState(onViewport)
    val dpadHolder = rememberUpdatedState(dpadController)
    val gestureMove = remember { booleanArrayOf(false) }
    val navZoomSpeed = remember { floatArrayOf(0f) }          // low-passed speed driving the nav zoom
    val scaling = remember { booleanArrayOf(false) }          // a pinch-zoom is in progress
    val navUserZoom = remember { doubleArrayOf(Double.NaN) }  // manual nav zoom override (NaN = auto)
    val camState = remember { doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0) } // eased follow-camera [lat,lng,bearing,zoom]; lat NaN = needs re-seed
    val routeColorHolder = rememberUpdatedState(routeColor)
    val routeSpansHolder = rememberUpdatedState(routeTrafficSpans)
    val darkHolder = rememberUpdatedState(darkTheme)
    val dashHolder = rememberUpdatedState(routeDashed)
    val speedupHolder = rememberUpdatedState(replaySpeedup)
    val lastGradM = remember { doubleArrayOf(-1e9) } // progressM the route split was last set at
    val lastGradNs = remember { longArrayOf(0L) }    // frame time of the last split upload (wall-clock floor)
    val mPerPxHolder = remember { doubleArrayOf(10.0) } // metres/pixel at the camera (scale-bar feed) —
                                                        // sizes the split-update throttle to sub-pixel
    val lastScaleReport = remember { doubleArrayOf(-1.0) } // last mpp PUSHED to compose (gate, see reportScale)
    // A manual pinch sets a zoom override (navUserZoom) that we keep following at; it's cleared
    // when you PAN (in the move listener, so a pan→Re-center returns to auto-zoom) and when nav
    // ends. Keyed on navMode, NOT navFollowing — navFollowing flips while panning and would
    // otherwise nuke a just-set pinch zoom, snapping it back to auto a beat later.
    LaunchedEffect(navMode) { if (!navMode) navUserZoom[0] = Double.NaN }
    remember { MapLibre.getInstance(context) }
    // D-pad-only operation (docs/dpad.md): MapLibre's MapView calls requestFocus() on
    // itself and overrides onKeyDown to handle hardware D-pad keys (DPAD_CENTER = zoom in,
    // arrows = scroll). On a keypad phone it therefore SWALLOWS every D-pad key before
    // Compose focus ever sees it — the "literally nothing happens with the D-pad" bug.
    // We drive the map through MapDpadController instead, so make the MapView (and its
    // surface child) non-focusable, unconditionally: touch gestures don't need view focus,
    // so nothing is lost, and key events now flow to the Compose focus system.
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    // Ending nav returns the camera to Google's flat north-up browse view — the follow camera's
    // last bearing/tilt used to linger, which also left the compass pinned on the map (it only
    // hides facing north; user 2026-07-10). Below mapRef so the handle is in scope.
    LaunchedEffect(navMode, mapRef) {
        if (navMode) return@LaunchedEffect
        val m = mapRef ?: return@LaunchedEffect
        val cam = m.cameraPosition
        if (kotlin.math.abs(cam.bearing) > 0.5 || cam.tilt > 0.5) {
            m.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    org.maplibre.android.camera.CameraPosition.Builder(cam).bearing(0.0).tilt(0.0).build(),
                ),
                600,
            )
        }
    }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var appliedStyleKey by remember { mutableStateOf<String?>(null) }
    var lastCameraTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastInsetPx by remember { mutableStateOf(-1) }
    var lastFittedRouteKey by remember { mutableStateOf<Int?>(null) }
    var lastRecenterTick by remember { mutableStateOf(-1) }
    var lastFittedMarkersKey by remember { mutableStateOf<Int?>(null) }
    var lastPreviewTarget by remember { mutableStateOf<LatLng?>(null) }
    // The last fix we actually re-pointed the nav camera at, so we can skip the
    // redundant re-animations that make the follow shimmer/lag (see the nav branch).
    var lastNavTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastNavBearing by remember { mutableStateOf<Float?>(null) }
    // Re-arm the pre-engage nav camera whenever follow is REGAINED (the re-center tap): its
    // moved-4m/turned-2deg gate compares against the last fix it used, so a stationary driver
    // who panned away and tapped re-center matched "nothing changed" and the camera never came
    // back (user 2026-07-14). Nulling the baselines makes the next pass move unconditionally.
    LaunchedEffect(navFollowing) {
        if (navFollowing) { lastNavTarget = null; lastNavBearing = null }
    }
    val navPuck = remember { NavPuck() }
    val routeCum = remember(routePolyline) { cumLengths(routePolyline) }
    // Accelerometer feed for the puck's speed Kalman — collected only during nav, written into a
    // PLAIN array (not compose state: sensor-rate updates through MutableState would recompose
    // the world 60×/s; the frame ticker below reads it directly instead).
    val motionProvider = remember { app.vela.core.location.MotionProvider(context) }
    val worldAccel = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(navMode) {
        worldAccel[0] = 0f
        worldAccel[1] = 0f
        if (!navMode) return@LaunchedEffect
        motionProvider.worldAccel().collect { a ->
            worldAccel[0] = a[0]
            worldAccel[1] = a[1]
        }
    }

    // Declutter POIs during turn-by-turn (Google-style): POI labels re-run symbol collision on
    // every nav camera rotate/zoom and pop in and out at zoom thresholds. Hide ALL POI tiers
    // while navigating (keeping even the top rank still left labels flickering at the threshold)
    // for a clean nav map, and restore on exit. Keyed on styleRef so it re-applies after a style
    // (re)load (dark/light flip), which recreates the layers at default visibility.
    LaunchedEffect(navMode, styleRef) {
        val style = styleRef ?: return@LaunchedEffect
        val vis = if (navMode) Property.NONE else Property.VISIBLE
        runCatching {
            listOf("poi_r1", "poi_r7", "poi_r20", "poi_transit").forEach { id ->
                style.getLayer(id)?.setProperties(PropertyFactory.visibility(vis))
            }
        }
        // This write races applyData's ambient/search suppression of the same layers: forcing
        // VISIBLE on nav end while its gate still says NONE left doubled icons until the next
        // state flip. Nulling the gate makes the next applyData frame re-assert the right value.
        lastOsmPoiVis = null
        // Stop signs + lights stay a NAV aid at z16; on the browse map they hold back until true
        // street zoom (user 2026-07-10: too busy mid-zoom without a route on screen).
        runCatching {
            val minZ = if (navMode) 16f else 17.5f
            (style.getLayer(CONTROLS_LAYER))?.minZoom = minZ
            (style.getLayer(CONTROLS_CLAIM_LAYER))?.minZoom = minZ
        }
        // Bus-stop icons + their name labels are browse furniture, not a driving aid — hide the
        // canonical GTFS stops layer during turn-by-turn (user drive 2026-07-14) and restore on
        // exit. The per-viewport stop FETCH is also skipped while navigating (MapViewModel).
        runCatching {
            style.getLayer(TRANSIT_STOPS_LAYER)
                ?.setProperties(PropertyFactory.visibility(if (navMode) Property.NONE else Property.VISIBLE))
        }
    }

    // Open building-footprint overlays (Microsoft, ODbL — PMTiles): render each region's footprints in a fill
    // layer BENEATH the OSM `building` layer, so it only fills GAPS where OSM is thin (a suburb the Microsoft→OSM
    // import missed) — OSM draws on top where it has data. Each entry is a full `pmtiles://` URI: `file://` for a
    // downloaded region (offline), or `https://` for the region in view that isn't downloaded — MapLibre 11.7+
    // streams that one via PMTiles HTTP range requests, fetching only the visible tiles, so footprints appear
    // with no download. Keyed on styleRef (re-add after a style reload) + darkTheme (fill matches the themed OSM
    // building colour, indistinguishable from a real OSM footprint).
    LaunchedEffect(buildingOverlays, styleRef, darkTheme) {
        val style = styleRef ?: return@LaunchedEffect
        // runCatching the enumerations too: the style can die between the null-check and this walk
        // (theme flip mid-effect), and a dead style throws on ANY access, not just mutation.
        runCatching { style.layers.filter { it.id.startsWith("vela-ovl-") }.forEach { style.removeLayer(it) } }
        runCatching { style.sources.filter { it.id.startsWith("vela-ovl-src-") }.forEach { style.removeSource(it) } }
        // MUST equal the OSM `building` fill/outline in applyDark/applyLight AND
        // applyClassicDark/applyClassicLight, or the Microsoft-only footprints read as a second
        // building colour beside the OSM ones. This pair is keyed on darkTheme but ALSO has to honour
        // the Modern/Classic palette: it was hardcoded to Modern, so switching to Classic left the
        // overlay houses Google-navy while the OSM ones went grey ("houses still bluish in classic",
        // user 2026-07-12). MapColors.current() feeds the styleKey, so a palette switch reloads the
        // style and re-runs this effect; reading classic() here picks up the new palette.
        val classic = app.vela.ui.MapColors.classic()
        val fill = when {
            classic && darkTheme -> "#383d45" // == applyClassicDark building
            classic -> "#dde1e7"              // == applyClassicLight building
            darkTheme -> "#1c3b69"            // == applyDark building
            else -> "#e2e3e9"                 // == applyLight building
        }
        val line = when {
            classic && darkTheme -> "#464c56"
            darkTheme -> "#2e3d6d"
            else -> "#c4c9d1" // classic-light + modern-light share this outline
        }
        val below = runCatching { style.getLayer("building")?.id }.getOrNull() // beneath OSM buildings so they win wherever OSM has them
        buildingOverlays.forEachIndexed { i, uri ->
            runCatching {
                val srcId = "vela-ovl-src-$i"
                style.addSource(VectorSource(srcId, uri)) // uri already carries pmtiles://file:// or pmtiles://https://
                val layer = FillLayer("vela-ovl-$i", srcId).apply {
                    setSourceLayer("building") // the tippecanoe layer name (build-overlay-region.sh: -l building)
                    setMinZoom(14f)
                    setProperties(PropertyFactory.fillColor(fill), PropertyFactory.fillOutlineColor(line))
                }
                if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
            }
        }
    }

    // Posted-speed-limit overlay (OSM maxspeed PMTiles, streamed): an INVISIBLE-but-queryable line layer.
    // We never draw it, but a wide-ish line means queryRenderedFeatures under the puck reliably hits the
    // road segment, and reading its `maxspeed` tag gives the "Speed B" online limit without a downloaded
    // routing graph. minZoom low so it's present at nav/free-drive zoom (z16 tiles overzoom).
    // ONLY added while speedOverlayOn (driving/nav) - the poll reads it only then, and adding it during a
    // plain browse was pure overhead AND rendered a BLACK stripe over every road: MapLibre's colour parser
    // rejected the 8-digit "#00000000" and fell back to its default OPAQUE BLACK. Fixed by the transparent
    // @ColorInt overload (no string parsing) and by not carrying the layer on the browse map at all.
    LaunchedEffect(maxspeedOverlays, styleRef, speedOverlayOn) {
        val style = styleRef ?: return@LaunchedEffect
        runCatching { style.layers.filter { it.id.startsWith("vela-ms-") }.forEach { style.removeLayer(it) } }
        runCatching { style.sources.filter { it.id.startsWith("vela-ms-src-") }.forEach { style.removeSource(it) } }
        if (!speedOverlayOn) return@LaunchedEffect // no query layer on the browse map
        maxspeedOverlays.forEachIndexed { i, uri ->
            runCatching {
                val srcId = "vela-ms-src-$i"
                style.addSource(VectorSource(srcId, uri))
                val layer = LineLayer("vela-ms-$i", srcId).apply {
                    setSourceLayer("maxspeed") // tippecanoe layer name (build-maxspeed-region.sh: -l maxspeed)
                    setMinZoom(11f)
                    setProperties(
                        // NOT opacity 0: MapLibre skips fully transparent features at render time, and
                        // queryRenderedFeatures only sees what rendered - the transparent version returned
                        // nothing, ever, so the badge died with the black-roads fix (found by ars18 in the
                        // vela-dpad fork; A/B-proven here, 35 mph vs null over the same road). 0.004 is one
                        // alpha step of black: invisible on any basemap, but the features stay queryable.
                        PropertyFactory.lineColor(android.graphics.Color.BLACK),
                        PropertyFactory.lineOpacity(0.004f),
                        PropertyFactory.lineWidth(12f),          // wide hit target for the point query
                    )
                }
                style.addLayer(layer)
            }
        }
    }

    // Poll the streamed maxspeed overlay under the puck (~2.5 s) while driving/navigating and report the
    // posted limit up, so a sign shows online with no routing graph. Uses the RAW fix (maxspeed needs no
    // sub-metre precision), projected to screen, queried off the invisible line layer. Main-thread (Compose)
    // so queryRenderedFeatures is legal; runCatching guards a mid-teardown style.
    val latestFix = rememberUpdatedState(myLocation)
    val latestMs = rememberUpdatedState(maxspeedOverlays)
    LaunchedEffect(speedOverlayOn) {
        if (!speedOverlayOn) { onRoadLimitKmh(null); return@LaunchedEffect }
        while (true) {
            val m = mapRef
            val fix = latestFix.value
            if (m != null && fix != null && latestMs.value.isNotEmpty()) {
                val kmh = runCatching {
                    val p = m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(fix.lat, fix.lng))
                    val r = 14f
                    val layers = latestMs.value.indices.map { "vela-ms-$it" }.toTypedArray()
                    m.queryRenderedFeatures(android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r), *layers)
                        .asSequence()
                        .mapNotNull { f ->
                            app.vela.core.data.OsmMaxspeed.fromTags(
                                f.getStringProperty("maxspeed"),
                                f.getStringProperty("maxspeed:forward"),
                                f.getStringProperty("maxspeed:backward"),
                            )
                        }.firstOrNull()
                }.getOrNull()
                onRoadLimitKmh(kmh)
            }
            kotlinx.coroutines.delay(2500)
        }
    }

    // House-number labels from the open ADDRESS overlay (OpenAddresses PMTiles of points): a SymbolLayer of the
    // `number` field, STREAMED for the region in view — fills in house numbers where OSM has no `addr:housenumber`
    // (the same gap the building overlay fills for footprints). Matched to the basemap `vela-housenumber` style
    // (Noto Sans 10, grey + white halo). minZoom 19 so numbers only appear when truly close (~50 ft scale) and
    // collision thins dense blocks. INSERTED BELOW the controls CLAIM layer (which sits below the ambient POI
    // icons) — NOT addLayer/top: MapLibre places symbols TOPMOST-LAYER-FIRST, so numbers stacked above the
    // ambient layer grabbed their collision boxes before the business icons placed, EVICTING them at z16+
    // (device-reproduced: Applebee's icon on the "5710" building vanished the moment numbers appeared; small
    // neighbours survived because the prominence-scaled big icons collide the most). Below the icons, numbers
    // place last and yield — Google's exact behaviour (a house number never displaces a business icon).
    // Numbered stop pins: one teal pin per intermediate stop, numbered in visit order. The
    // whole feature set re-uploads whenever the list (or its order) changes, so a reorder in
    // the stops editor re-numbers the map immediately. Icons register on demand per number.
    LaunchedEffect(stopPins, styleRef) {
        val style = styleRef ?: return@LaunchedEffect
        runCatching {
            if (stopPins.isEmpty()) {
                style.getLayer(STOPNUM_LAYER)?.let { style.removeLayer(it) }
                style.getSource(STOPNUM_SRC)?.let { style.removeSource(it) }
                return@LaunchedEffect
            }
            val feats = stopPins.mapIndexed { i, p ->
                PoiIcons.ensureStopNumberIcon(style, i + 1)
                Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                    addStringProperty("icon", "vela-stopnum-${i + 1}")
                }
            }
            val existing = style.getSource(STOPNUM_SRC) as? GeoJsonSource
            if (existing != null) {
                existing.setGeoJson(FeatureCollection.fromFeatures(feats))
            } else {
                style.addSource(GeoJsonSource(STOPNUM_SRC, FeatureCollection.fromFeatures(feats)))
            }
            if (style.getLayer(STOPNUM_LAYER) == null) {
                val layer = SymbolLayer(STOPNUM_LAYER, STOPNUM_SRC).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(0.9f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM), // tip marks the stop
                    PropertyFactory.iconAllowOverlap(true), // a handful of pins, always visible
                    PropertyFactory.iconIgnorePlacement(true),
                )
                // Beside the result markers in the stack: above the route line and geometry,
                // below the basemap labels the markers already sit under.
                when {
                    style.getLayer(MARKERS_LAYER) != null -> style.addLayerAbove(layer, MARKERS_LAYER)
                    else -> style.addLayer(layer)
                }
            }
        }
    }

    LaunchedEffect(addressOverlays, styleRef, darkTheme, satelliteOn) {
        val style = styleRef ?: return@LaunchedEffect
        runCatching { style.layers.filter { it.id.startsWith("vela-addr-") }.forEach { style.removeLayer(it) } }
        runCatching { style.sources.filter { it.id.startsWith("vela-addr-src-") }.forEach { style.removeSource(it) } }
        // The overlay statewide data covers what OSM has too — hide the basemap number layer while the overlay
        // is active, or the SAME address renders twice at a slight offset (device-seen: "5611" / "5607" doubled).
        runCatching {
            style.getLayer("vela-housenumber")?.setProperties(
                PropertyFactory.visibility(if (addressOverlays.isEmpty()) Property.VISIBLE else Property.NONE),
            )
        }
        // Over satellite imagery: white-with-black-halo like every other label (these layers
        // mount AFTER applySatelliteLabels' style-load sweep, so they style themselves).
        val txt = if (satelliteOn) "#ffffff" else if (darkTheme) "#9aa0a6" else "#8a8a8a"
        val halo = if (satelliteOn) "#000000" else if (darkTheme) "#1b2432" else "#ffffff"
        addressOverlays.forEachIndexed { i, uri ->
            runCatching {
                val srcId = "vela-addr-src-$i"
                style.addSource(VectorSource(srcId, uri))
                val layer =
                    SymbolLayer("vela-addr-$i", srcId).apply {
                        setSourceLayer("address") // tippecanoe layer name (build-address-region.sh: -l address)
                        // The archive carries tiles only at z16-17, and MapLibre's pmtiles path never
                        // cold-fetches a tile clamped 2+ levels below the camera - a layer minZoom of 19
                        // meant a cold source (fresh launch, zoom straight in) fetched nothing, silently,
                        // until some lower-zoom browse made tiles resident. So the layer arms at 17
                        // (in-zoom-range is what drives fetching) and the 50 ft UX gate lives in the
                        // TEXT FIELD: empty below z19, the number at 19+. Empty text means no symbols
                        // exist at 17-18.9 at all - an opacity-0 gate was tried first and worked, but
                        // it left every number doing invisible placement work each frame in the densest
                        // band on the map (19 still = the ~50 ft view; 17.5 carpeted whole blocks,
                        // user 2026-07-13).
                        setMinZoom(17f)
                        setProperties(
                            PropertyFactory.textField(
                                Expression.step(
                                    Expression.zoom(), Expression.literal(""),
                                    Expression.stop(19f, Expression.get("number")),
                                ),
                            ),
                            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                            PropertyFactory.textSize(10f),
                            PropertyFactory.textColor(txt),
                            PropertyFactory.textHaloColor(halo),
                            PropertyFactory.textHaloWidth(if (satelliteOn) 1.8f else 1f),
                            // Numbers still YIELD to icons/labels (allow-overlap stays false), but they
                            // never enter the collision index themselves: nothing needs to dodge a house
                            // number, and keeping hundreds of them out of the index makes each placement
                            // pass at street zoom cheaper (they're the densest symbols on screen there).
                            PropertyFactory.textIgnorePlacement(true),
                        )
                    }
                // Anchor to the CLAIM twin, which sits where the visible controls layer used to
                // (above the basemap labels, below the ambient icons). Anchoring to the VISIBLE
                // controls layer broke the moment it moved to the bottom of the symbol stack
                // (2026-07-09): the numbers sank with it, below bridge geometry and the building
                // extrusions - drawn "under the buildings", and yielding to every basemap label
                // so only the odd out-of-footprint number survived.
                when {
                    style.getLayer(CONTROLS_CLAIM_LAYER) != null -> style.addLayerBelow(layer, CONTROLS_CLAIM_LAYER)
                    style.getLayer(AMBIENT_LAYER) != null -> style.addLayerBelow(layer, AMBIENT_LAYER)
                    else -> style.addLayer(layer) // defensive - top is better than absent
                }
            }
        }
    }

    // "3D buildings" setting → the basemap's building-3d fill-extrusion layer (z16+).
    // Extrusion is the most fragment-expensive thing the map draws, so this is the direct
    // lever for zoomed-in pan stutter on weaker GPUs. applyLight/applyDark colour the layer
    // but never touch visibility, so this effect owns it (re-applied on style reload too).
    // Extrusions also hide while SATELLITE imagery is on: the grey 3D boxes drew on top of the
    // photo roofs (they sit above the raster in the layer stack) - wrong-looking AND the most
    // fragment-expensive thing on screen (user 2026-07-13).
    val buildings3d = app.vela.ui.Buildings3d.on.value && !satelliteOn
    LaunchedEffect(buildings3d, styleRef) {
        runCatching {
            styleRef?.getLayer("building-3d")?.setProperties(
                PropertyFactory.visibility(if (buildings3d) Property.VISIBLE else Property.NONE),
            )
        }
    }

    // Free-drive follow (no route open, driveFollowing on): a per-frame ticker that does for
    // browsing what the nav ticker does for navigating - it OWNS the location source so the
    // heading beam eases smoothly (killing the per-recomposition compass jitter) and glides the
    // camera onto the live fix north-up (the user's own zoom, no tilt/rotation). Keyed on
    // driveFollowing so it only spins while the user is actually being followed; a pan drops the
    // flag and this relaunches into the reset branch, handing the source back to applyData.
    LaunchedEffect(navMode, driveFollowing) {
        if (navMode || !driveFollowing) {
            browseBeam[0] = Float.NaN   // applyData paints the raw fix again once we're not following
            browseCam[0] = Double.NaN
            lastBrowse[0] = Double.NaN
            browseDrive[1] = 0.0; browseDrive[2] = Double.NaN; browseDrive[3] = 0.0
            browseZoomGoal[0] = Double.NaN
            browseFlying[0] = false // whatever cancelled the follow also cancelled the flight (onCancel), but never leak
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = (if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9)).toFloat().coerceIn(0f, 0.1f)
            lastNanos = now
            val style = styleRef ?: continue
            val loc = myLocationHolder.value ?: continue
            // Ease the beam toward the freshest heading (compass when stopped, GPS course when
            // moving). Hold the last angle when neither is available so the cone doesn't snap to 0.
            // While DRIVING the GPS course leads - a car body wrecks the magnetometer, and the
            // heading-up camera below keys off course, so the puck and the map must agree.
            val tgt = if (browseDrive[1] > 0.5) myBearingHolder.value ?: compassHeadingHolder.value
            else compassHeadingHolder.value ?: myBearingHolder.value
            if (tgt != null) browseBeam[0] = if (browseBeam[0].isNaN()) tgt else smoothBearing(browseBeam[0], tgt, dt, 0.15f)
            val beam = if (browseBeam[0].isNaN()) 0f else browseBeam[0]
            // DEAD-RECKON the follow target between fixes (user 2026-07-14: "not a smooth inertial
            // glide like navigation"). Easing toward the RAW fix chases a target that jumps once a
            // second and then sits still - the camera surges after each fix and stalls before the
            // next, the visible jitter. Nav glides because its puck integrates speed every frame;
            // the browse equivalent is a constant-velocity projection of the last fix along its
            // own course, so the ease chases a target that MOVES like the car. Gated to a real
            // driving speed with a known course; capped at 2.5 s so a dropped signal can't run the
            // camera away (the next fix re-anchors and the ease absorbs the correction smoothly).
            if (browseFixRef[0] !== loc) {
                browseFixRef[0] = loc
                browseFix[0] = loc.lat; browseFix[1] = loc.lng
                browseFix[2] = android.os.SystemClock.elapsedRealtime().toDouble()
                browseFix[3] = (mySpeedHolder.value ?: 0f).toDouble()
                browseFix[4] = myBearingHolder.value?.toDouble() ?: Double.NaN
            }
            val sinceFix = (android.os.SystemClock.elapsedRealtime() - browseFix[2]) / 1000.0
            val drM = if (browseFix[3] > 1.5 && !browseFix[4].isNaN()) {
                browseFix[3] * sinceFix.coerceAtMost(2.5)
            } else 0.0
            val tgtLat: Double
            val tgtLng: Double
            if (drM > 0.0) {
                val br = Math.toRadians(browseFix[4])
                tgtLat = browseFix[0] + drM * kotlin.math.cos(br) / 111_320.0
                tgtLng = browseFix[1] + drM * kotlin.math.sin(br) /
                    (111_320.0 * kotlin.math.cos(Math.toRadians(browseFix[0])).coerceAtLeast(0.1))
            } else {
                tgtLat = loc.lat
                tgtLng = loc.lng
            }
            // Ease the camera toward the (reckoned) target (skipped while pinching - the fingers win).
            val cam = mapRef
            var camLat = tgtLat; var camLng = tgtLng
            var lookLat = tgtLat; var lookLng = tgtLng // camera aim point (ahead of the puck while driving)
            var attSettling = false // true while bearing/tilt are still easing (to course-up or north-up)
            if (cam != null && !scaling[0] && !browseFlying[0]) {
                if (browseCam[0].isNaN()) {
                    val cp = cam.cameraPosition
                    // First follow-engagement. If the camera is zoomed OUT past street level - a cold
                    // launch, a crash relaunch, or a locate tap from a far view - FLY to the fix at
                    // street zoom (AUDIT FIX 4, 2026-07-15: this used to be an instant moveCamera
                    // teleport that also cancelled the launch flight). One owned animateCamera; the
                    // ticker parks (browseFlying) until it lands. The flag clears in BOTH onFinish
                    // and onCancel - a leaked flag would kill follow for the session, and a missing
                    // cancel path would resurrect the "came back zoomed to the whole US" bug this
                    // snap was added for. browseCam stays NaN through the flight, so the next frame
                    // reseeds from the LANDED camera - if the fix moved mid-flight, the ease just
                    // glides the difference. A normal street camera is still preserved (else branch).
                    if (cp.zoom < 14.0) {
                        browseFlying[0] = true
                        cam.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(MLLatLng(loc.lat, loc.lng), 15.5),
                            650,
                            object : MapLibreMap.CancelableCallback {
                                override fun onFinish() { browseFlying[0] = false }
                                override fun onCancel() { browseFlying[0] = false }
                            },
                        )
                        continue
                    } else {
                        browseCam[0] = cp.target?.latitude ?: loc.lat
                        browseCam[1] = cp.target?.longitude ?: loc.lng
                    }
                }
                // tau 0.22 s (was 0.16): a slightly longer time-constant keeps the camera CHASING
                // between the ~1 Hz fixes instead of coasting to each one and stopping, so the follow
                // reads as a continuous glide (closer to the nav feel) rather than a per-second ease.
                val k = (1f - kotlin.math.exp(-dt / 0.22f)).toDouble()
                browseCam[0] += (tgtLat - browseCam[0]) * k
                browseCam[1] += (tgtLng - browseCam[1]) * k
                camLat = browseCam[0]; camLng = browseCam[1]
                lookLat = camLat; lookLng = camLng
                val cp = cam.cameraPosition
                // DRIVING mode latches on at driving speed (smoothed, so one noisy fix can't
                // flip it) and only the follow ending releases it - a red light HOLDS the
                // heading-up attitude rather than levelling out (user 2026-07-15: the free-drive
                // camera must track the heading like navigation; the earlier north-up ask turned
                // out to be about the puck moving SIDEWAYS, and course-up is what Google does).
                browseDrive[0] += (browseFix[3] - browseDrive[0]) * (1.0 - kotlin.math.exp(-dt / 1.0))
                if (browseDrive[1] < 0.5 && browseDrive[0] > 2.5 && !browseFix[4].isNaN()) browseDrive[1] = 1.0
                if (browseDrive[1] > 0.5) {
                    // Heading-up, nav-style: ease the live camera toward the course (updated only
                    // while the course is trustworthy - above walking speed), tilt toward nav's 55,
                    // and aim the camera a speed-scaled distance AHEAD of the puck so the road
                    // ahead owns the view. Nav gets that framing from sticky camera padding; a
                    // projected aim point does the same job with nothing to un-stick when the
                    // follow drops.
                    if (browseFix[3] > 2.0 && !browseFix[4].isNaN()) browseDrive[2] = browseFix[4]
                    val crs = if (browseDrive[2].isNaN()) cp.bearing else browseDrive[2]
                    val db = ((crs - cp.bearing + 540.0) % 360.0) - 180.0
                    browseAtt[0] = (cp.bearing + db * k + 360.0) % 360.0
                    browseAtt[1] = cp.tilt + (55.0 - cp.tilt) * k
                    browseDrive[3] += ((browseDrive[0] * 5.0).coerceAtMost(250.0) - browseDrive[3]) * k
                    val lr = Math.toRadians(crs)
                    lookLat = camLat + browseDrive[3] * kotlin.math.cos(lr) / 111_320.0
                    lookLng = camLng + browseDrive[3] * kotlin.math.sin(lr) /
                        (111_320.0 * kotlin.math.cos(Math.toRadians(camLat)).coerceAtLeast(0.1))
                    attSettling = true // camera state is live every frame while driving
                } else {
                    // NORTH-UP, FLAT (walking/slow browse) - enforced against the LIVE camera every
                    // frame, not a one-shot shadow: the first cut copied bearing/tilt once when
                    // follow engaged and eased that copy, so any rotation arriving from OUTSIDE the
                    // ticker afterwards (a camera animation, a restored rotated camera - anything
                    // that isn't a follow-dropping gesture) was invisible to it and the map stayed
                    // rotated (user drive 2026-07-14). Reading the real values each frame
                    // self-corrects no matter where the rotation came from; the built-in compass
                    // shows while it settles and fades at north. A manual rotate is a gesture,
                    // which drops follow, so fingers still win.
                    val realBrg = ((cp.bearing + 540.0) % 360.0) - 180.0 // signed, eases to 0
                    attSettling = kotlin.math.abs(realBrg) > 0.15 || cp.tilt > 0.15
                    browseAtt[0] = realBrg * (1.0 - k)
                    browseAtt[1] = cp.tilt * (1.0 - k)
                }
            } else {
                browseCam[0] = Double.NaN // released (pinch) → re-seed from the live camera on re-attach
            }
            // Skip the native calls when nothing moved enough to see (a settled follow at a red
            // light shouldn't re-upload the point + re-drive the camera 60×/s). ~1e-6 deg is well
            // under a pixel at street zoom; 0.4 deg of heading is invisible on the cone.
            val moved = lastBrowse[0].isNaN() || attSettling ||
                kotlin.math.abs(camLat - lastBrowse[0]) > 1e-6 ||
                kotlin.math.abs(camLng - lastBrowse[1]) > 1e-6 ||
                kotlin.math.abs(beam - lastBrowse[2]) > 0.4
            // The locate tap's standard zoom rides the ticker (an animateCamera would be cancelled
            // by the ticker's own writes a frame later): ease toward the goal, retire it on arrival.
            var zoomEase = Double.NaN
            if (cam != null && !scaling[0] && !browseFlying[0] && !browseZoomGoal[0].isNaN()) {
                val z = cam.cameraPosition.zoom
                if (kotlin.math.abs(browseZoomGoal[0] - z) < 0.02) browseZoomGoal[0] = Double.NaN
                else zoomEase = z + (browseZoomGoal[0] - z) * (1f - kotlin.math.exp(-dt / 0.22f)).toDouble()
            }
            if (moved || !zoomEase.isNaN()) {
                // Draw the puck at the EASED follow position (camLat/camLng), not the raw fix: at the
                // raw fix the dot teleported forward on the map each 1 Hz fix while the camera eased to
                // catch up (the visible hop). At the eased position the dot stays centred and glides with
                // the map - the same locked puck+camera the nav follow shows. (Falls back to the raw fix
                // while pinching, when the camera isn't easing.)
                val puckAt = if (cam != null && !scaling[0] && !browseFlying[0]) LatLng(camLat, camLng) else loc
                setMeSource(style, puckAt, beam)
                if (cam != null && !scaling[0] && !browseFlying[0]) {
                    if (attSettling || !zoomEase.isNaN()) {
                        // Easing attitude (course-up while driving, back to north-up flat
                        // otherwise): drive it alongside the aim point (zoom left unset =
                        // preserved, so a pinch level survives). While driving the aim point
                        // rides AHEAD of the puck, which puts the puck low on the screen.
                        cam.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(MLLatLng(lookLat, lookLng))
                                    .bearing((browseAtt[0] + 360.0) % 360.0)
                                    .tilt(browseAtt[1].coerceAtLeast(0.0))
                                    .apply { if (!zoomEase.isNaN()) zoom(zoomEase) }
                                    .build(),
                            ),
                        )
                    } else {
                        cam.moveCamera(CameraUpdateFactory.newLatLng(MLLatLng(camLat, camLng)))
                    }
                }
                lastBrowse[0] = camLat; lastBrowse[1] = camLng; lastBrowse[2] = beam.toDouble()
            }
        }
    }

    // The in-nav ROUTE OVERVIEW (Google's fly-over): fit the whole route while the drive keeps
    // navigating - the VM marked the camera detached, so the follow ticker has already stepped
    // aside and the existing Re-center button glides back into the puck-low follow. Camera only;
    // guidance, voice and the puck (which keeps moving along the overview) are untouched.
    LaunchedEffect(navOverviewTick) {
        if (navOverviewTick == 0 || !navMode || routePolyline.size < 2) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        // The puck-low top padding would skew a bounds fit; the follow re-applies it per frame
        // when Re-center re-attaches.
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        val b = MLLatLngBounds.Builder()
        routePolyline.forEach { b.include(MLLatLng(it.lat, it.lng)) }
        runCatching {
            // Fit NORTH-UP and FLAT (the bearing/tilt overload): the plain bounds fit kept the
            // follow's rotated 55-degree camera, and a tilted, rotated fit shows LESS than the
            // whole route however correct the math (user 2026-07-15: "doesn't quite show the
            // full route") - Google's overview levels out too.
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    b.build(), 0.0, 0.0,
                    70, (map.height * 0.30).toInt(), 70, (map.height * 0.22).toInt(),
                ),
                700,
            )
        }
    }

    // Centre on the user ONCE per session, the moment the map AND the first fix are both ready. A cold
    // launch gets this for free, but a crash relaunch restores MapLibre's last (wide) camera and the
    // seeded centre doesn't reliably override it (user 2026-07-12: "came back zoomed to the whole US;
    // it can take a sec for the location to resolve"). Runs in the VIEW layer, so it fires AFTER the map
    // is ready and the fix has landed - and waits for that fix however long it takes. Skipped once the
    // user has taken the wheel (a pan, or a search/route already owns the camera).
    val didLaunchCentre = remember { booleanArrayOf(false) }
    LaunchedEffect(mapRef, myLocation, navMode) {
        if (didLaunchCentre[0]) return@LaunchedEffect
        val cam = mapRef ?: return@LaunchedEffect
        val loc = myLocation ?: return@LaunchedEffect
        // Nav owns the camera, or the user already took control → don't grab it; just retire the one-shot.
        if (navMode || gestureMove[0] || markers.isNotEmpty() || routePolyline.isNotEmpty()) {
            didLaunchCentre[0] = true
            return@LaunchedEffect
        }
        didLaunchCentre[0] = true
        runCatching {
            cam.animateCamera(CameraUpdateFactory.newLatLngZoom(MLLatLng(loc.lat, loc.lng), 15.5), 650)
        }
    }

    // Nav puck motion model (Google-style): a per-frame ticker glides the displayed
    // position forward along the route. Two pieces, both copied from how Google's puck
    // behaves: (1) **dead reckoning** — between the ~1 Hz GPS fixes, the goal keeps
    // advancing at the last known speed (predicted = lastFix + speed·timeSinceFix), so the
    // puck never stalls mid-second; a fresh fix simply re-anchors it. (2) **eased,
    // monotonic** along-route progress + **smoothed heading**, so it rides forward without
    // the per-fix teleport, never jitters backward, and rotates smoothly through bends
    // instead of snapping at every vertex. It owns the location source while navigating;
    // off-route or in browse, applyData drives the source from the raw fix instead.
    // Re-keyed on routePolyline too: a mid-nav reroute swaps the route geometry, so the
    // ticker relaunches with the fresh route + cum and re-acquires the puck onto it at the
    // next fix (engaged=false) instead of gliding along stale geometry.
    LaunchedEffect(navMode, routePolyline) {
        if (!navMode) {
            // AUDIT FIX 7 (2026-07-15): only tear the camera down on an ACTUAL nav exit. This
            // effect is keyed on routePolyline too (so reroutes relaunch the puck ticker), which
            // meant every route swap in the CHOOSER (picking an alternate, editing stops) re-ran
            // this branch and its moveCamera killed the route-fit flight at frame ~0 - the
            // "picking an alternate kills the fly-over" hitch. wasNavRef makes the teardown
            // one-shot per real drive.
            if (!wasNavRef[0]) return@LaunchedEffect
            wasNavRef[0] = false
            navPuck.kalman.reset() // nav ended — don't carry a stale speed into the next trip
            navUserTilt[0] = Double.NaN // shove-set tilt is a per-drive override
            navTiltEase[0] = 55.0 // next drive starts at the default pitch, not wherever this one ended
            // Camera padding is STICKY MapLibre state: the nav view's puck-low offset (top padding,
            // set on every follow frame + the pre-engage case) would otherwise shift the browse
            // camera's centre for the rest of the session. Bearing + tilt are sticky the same way.
            // ONE INSTANT move, not an animate: the 450 ms level-out used to get CANCELLED
            // mid-flight by the next camera write (a browse recenter, the follow ticker seeding)
            // and left the map PARTIALLY rotated after a drive - the "still not quite north-up"
            // report (user 2026-07-14). A snap can't be interrupted; the free-drive follow's
            // live-read ease still smooths any rotation that arrives later.
            mapRef?.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .bearing(0.0)
                        .tilt(0.0)
                        .padding(0.0, 0.0, 0.0, 0.0)
                        .build(),
                ),
            )
            return@LaunchedEffect
        }
        wasNavRef[0] = true
        navPuck.engaged = false
        var lastNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            // Two frame deltas: dtRaw (true wall-clock, for the PHYSICS — a janky 150 ms frame
            // must integrate 150 ms of travel, else the puck loses distance and lurches at each
            // fix) and dt (clamped, for the EASING filters only, where a huge step just means
            // "snap most of the way" and 0.1 s keeps them stable).
            val dtRaw = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9)
            val dt = dtRaw.toFloat().coerceIn(0f, 0.1f)
            lastNanos = now
            val style = styleRef ?: continue
            // TRACE-time frame deltas: during a replay the world runs speedup× faster than the
            // wall clock, so every physics/easing step must integrate speedup× as much time or
            // the puck falls behind each fix and surges to catch up (the replay stutter). Live
            // (speedup = 1) these are identical to dtRaw/dt.
            val ts = speedupHolder.value.toDouble().coerceAtLeast(1.0)
            val dtT = dtRaw * ts
            val dtE = (dt * ts.toFloat()).coerceAtMost(0.3f)
            if (navPuck.engaged && routePolyline.size >= 2) {
                // Kalman-predict the speed each frame: fold the MEASURED forward acceleration
                // into the modelled speed, so braking kills the prediction NOW — not at the next
                // GPS fix. The old last-fix-speed × elapsed reckoning glided at full speed for up
                // to a second after you hit the brakes, and monotonic progress could never walk
                // it back (the "puck sits ahead of me when I stop" weirdness). The projection
                // bearing is the VEHICLE's course (myBearing), not the drawn puck's route bearing
                // — when the puck has overshot around a corner those diverge, and projecting onto
                // the puck's own bearing attenuates (90°) or even INVERTS (U-turn) the braking
                // signal exactly when it matters most.
                val vehBrg = myBearingHolder.value?.toDouble()
                    ?: (if (navPuck.displayBearing.isNaN()) null else navPuck.displayBearing.toDouble())
                val fwd = if (vehBrg == null) 0.0
                    else app.vela.core.location.forwardAccel(
                        worldAccel[0].toDouble(), worldAccel[1].toDouble(), vehBrg,
                    )
                // Clamp the predict step (an app-pause gap shouldn't integrate minutes of stale
                // accel); the GPS fix after the gap re-measures anyway.
                navPuck.kalman.predict(fwd, dtT.coerceAtMost(0.5))
                navPuck.speed = navPuck.kalman.speed
                // Dead-reckon by INTEGRATING the live modelled speed — over THIS frame's part of
                // the blind window since the fix (TRACE time; the window caps how far a dropped
                // GPS signal can run the puck away down the route).
                // Blind window = 3 s (was 2): some chipsets deliver fixes 2.5-3.5 s apart under
                // canopy, and a 2 s cap made the puck glide-stall-lurch every cycle at exactly
                // that cadence. 3 s still bounds a dropped-signal runaway to ~100 m at highway
                // speed, and the decay below shaves the model right after it.
                val sinceFix = (android.os.SystemClock.elapsedRealtime() - navPuck.targetAtMs) / 1000.0 * ts
                val tEnd = sinceFix.coerceIn(0.0, 3.0)
                val tStart = (sinceFix - dtT).coerceIn(0.0, 3.0)
                if (tEnd > tStart) navPuck.reckonedM += navPuck.kalman.speed * (tEnd - tStart)
                // Past the dead-reckon window with no accepted fix = a measurement outage: decay
                // the modelled speed toward 0 (there's no evidence we're still moving) so the
                // zoom/look-ahead don't ride a stale speed forever. A resumed fix re-measures.
                if (sinceFix > 3.0) navPuck.kalman.decay(dtT.coerceAtMost(0.5))
                val predicted = navPuck.targetM + navPuck.reckonedM
                val eased = navPuck.progressM + (predicted - navPuck.progressM) * (1f - kotlin.math.exp(-dtE / 0.25f))
                navPuck.progressM = maxOf(navPuck.progressM, eased) // monotonic — never backward
                val (pt, segBrg) = pointAtMeters(routePolyline, routeCum, navPuck.progressM)
                navPuck.displayBearing = if (navPuck.displayBearing.isNaN()) segBrg
                    else smoothBearing(navPuck.displayBearing, segBrg, dtE, 0.2f)
                navPuck.drawn = pt // the camera follows this smoothed point, not the raw fix
                setMeSource(style, pt, navPuck.displayBearing)
                // Drive the follow-camera HERE, per frame (60 fps) with a continuous ease, instead
                // of the recomposition-driven block below (which re-pointed only ~1-3×/s in
                // throttled 550 ms eases — the "stiff" feel). Ease the camera toward the smooth
                // puck each frame (~0.12 s) so it glides; seed from the live camera on (re)attach
                // for a smooth hand-off from the pre-engage framing / a Re-center. Skipped while
                // panning (detached) or pinching (the user's fingers win).
                val cam = mapRef
                if (cam != null && navFollowingHolder.value && !scaling[0] && !shoving[0]) {
                    val sp = navPuck.speed.toFloat().coerceIn(0f, 30f)
                    navZoomSpeed[0] += (sp - navZoomSpeed[0]) * (1f - kotlin.math.exp(-dtE / 0.6f))
                    val tgtZoom = if (!navUserZoom[0].isNaN()) navUserZoom[0]
                        else 18.5 - (navZoomSpeed[0] / 30f) * (18.5 - 15.8) // even closer default (user 2026-07-15, was 18.0-15.5); speed still zooms out
                    if (camState[0].isNaN()) { // (re)seed from the live camera for a smooth hand-off
                        val cp = cam.cameraPosition
                        camState[0] = cp.target?.latitude ?: pt.lat
                        camState[1] = cp.target?.longitude ?: pt.lng
                        camState[2] = cp.bearing
                        camState[3] = if (cp.zoom > 1.0) cp.zoom else tgtZoom
                        // AUDIT FIX 1 (2026-07-15): seed tilt AND the puck-low padding from the
                        // live camera too. Position/zoom/bearing were seeded but tilt snapped to
                        // its eased remainder and the 0.45x top padding landed whole on frame ONE -
                        // re-attaching from the flat, unpadded overview slammed 55 degrees of tilt
                        // plus a 22%-of-screen centre jump in a single frame (the overview->follow
                        // jolt). Both now ease in with the same k as everything else.
                        navTiltEase[0] = cp.tilt
                        navPadEase[0] = (cp.padding?.getOrNull(1) ?: 0.0) / cam.height.toDouble().coerceAtLeast(1.0)
                    }
                    val k = (1f - kotlin.math.exp(-dtE / 0.12f)).toDouble()
                    camState[0] += (pt.lat - camState[0]) * k
                    camState[1] += (pt.lng - camState[1]) * k
                    // Compass toggle (user 2026-07-15): north-up keeps the follow (position, zoom,
                    // puck-low framing) but eases bearing to 0 and the tilt flat; the puck arrow
                    // then rotates on the north-up map instead of the map rotating under it.
                    val brgTgt = if (navNorthUpHolder.value) 0.0 else navPuck.displayBearing.toDouble()
                    val db = ((brgTgt - camState[2] + 540.0) % 360.0) - 180.0 // shortest arc
                    camState[2] = (camState[2] + db * k + 360.0) % 360.0
                    camState[3] += (tgtZoom - camState[3]) * k
                    // Tilt: north-up = flat; else a shove-set override wins over the 55 default.
                    val tiltTgt = when {
                        navNorthUpHolder.value -> 0.0
                        !navUserTilt[0].isNaN() -> navUserTilt[0]
                        else -> 55.0
                    }
                    navTiltEase[0] += (tiltTgt - navTiltEase[0]) * k
                    navPadEase[0] += (0.45 - navPadEase[0]) * k
                    if (kotlin.math.abs(0.45 - navPadEase[0]) < 0.002) navPadEase[0] = 0.45 // terminate exactly
                    cam.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(MLLatLng(camState[0], camState[1]))
                                .bearing(camState[2])
                                .zoom(camState[3])
                                .tilt(navTiltEase[0])
                                // Puck LOW on the screen, Google-style: a top padding of ~0.45x
                                // the view height renders the target at ~72% down, so the road
                                // AHEAD owns the view instead of splitting it with what's behind
                                // (user 2026-07-14). Eased in on (re)attach - see the seed above.
                                // Padding is sticky camera state - the nav teardown below resets
                                // it for the browse map.
                                .padding(0.0, cam.height * navPadEase[0], 0.0, 0.0)
                                .build(),
                        ),
                    )
                } else {
                    camState[0] = Double.NaN // reset → re-attach eases in from the live camera
                }
                // Keep the driven/ahead cut EXACTLY under the arrow — a GEOMETRY split updated
                // here (throttled to sub-pixel at the CURRENT zoom): the ahead layer gets the
                // polyline suffix from the puck's progress point forward (traffic spans remapped
                // onto the suffix) and the full line beneath is painted traversed-grey. The old
                // line-gradient stop could never be crisp: MapLibre bakes the whole gradient
                // into a 256-texel texture, smearing the "hard" cut into a routeLength/256-metre
                // ramp — the zoomed-in gradient the user reported. (Dashed walk/bike lines keep
                // their plain style — dasharray disables gradients anyway.)
                // Throttled two ways: sub-pixel distance at the current zoom (floor 1 m) AND a
                // 150 ms wall-clock floor — each update re-uploads the remaining-suffix
                // LineString, and an unbounded rate burned frame budget at highway speed.
                if (!dashHolder.value && routeCum.isNotEmpty() && routeCum.last() > 0.0 &&
                    kotlin.math.abs(navPuck.progressM - lastGradM[0]) > (mPerPxHolder[0] * 0.75).coerceIn(1.0, 3.0) &&
                    now - lastGradNs[0] > 150_000_000L
                ) {
                    lastGradM[0] = navPuck.progressM
                    lastGradNs[0] = now
                    val gInt = runCatching { android.graphics.Color.parseColor(routeColorHolder.value) }
                        .getOrDefault(ROUTE_FREEFLOW)
                    val total = routeCum.last()
                    val prog = navPuck.progressM.coerceIn(0.0, total)
                    val cutIdx = indexAtMeters(routeCum, prog)
                    val (cutPt, _) = pointAtMeters(routePolyline, routeCum, prog)
                    val pts = ArrayList<Point>(routePolyline.size - cutIdx + 1)
                    pts.add(Point.fromLngLat(cutPt.lng, cutPt.lat))
                    for (i in cutIdx until routePolyline.size) {
                        pts.add(Point.fromLngLat(routePolyline[i].lng, routePolyline[i].lat))
                    }
                    style.getSourceAs<GeoJsonSource>(ROUTE_AHEAD_SRC)?.setGeoJson(
                        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts))),
                    )
                    // Remap the whole-route traffic-span fractions onto the suffix's 0..1.
                    val gp = (prog / total).toFloat()
                    val remapped = if (gp >= 0.999f) emptyList() else routeSpansHolder.value.mapNotNull { (s, e, lvl) ->
                        val s2 = ((s - gp) / (1f - gp)).coerceIn(0f, 1f)
                        val e2 = ((e - gp) / (1f - gp)).coerceIn(0f, 1f)
                        if (e2 <= s2) null else Triple(s2, e2, lvl)
                    }
                    style.getLayer(ROUTE_AHEAD_LAYER)?.setProperties(
                        PropertyFactory.visibility(Property.VISIBLE),
                        PropertyFactory.lineGradient(routeGradient(0f, gInt, remapped)),
                    )
                    val traversed = android.graphics.Color.parseColor(
                        if (darkHolder.value) TRAVERSED_DARK else TRAVERSED_LIGHT,
                    )
                    style.getLayer(ROUTE_LAYER)?.setProperties(
                        PropertyFactory.lineGradient(routeGradient(0f, traversed, emptyList())),
                    )
                }
            } else {
                navPuck.raw?.let { setMeSource(style, it, navPuck.rawBearing ?: 0f) }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        mapView.onStart()
        mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        // Re-assert non-focusability each pass — MapLibre re-enables it on surface
        // (re)creation, which would let it eat D-pad keys again (docs/dpad.md).
        if (mv.isFocusable) {
            mv.isFocusable = false
            mv.isFocusableInTouchMode = false
            mv.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (mapRef == null) {
            mv.getMapAsync { map ->
                map.uiSettings.isLogoEnabled = false
                // Hide the bottom-left attribution "ⓘ" — open-tile attribution lives
                // in Settings → About instead, so the map stays clean (Google-style).
                map.uiSettings.isAttributionEnabled = false
                // Two-finger vertical drag tilts the map (3D ↔ flat), like Google. Enable it
                // explicitly so it can't be off, and lift the default ~60° cap to 70° so a
                // satisfying near-horizon 3D is reachable; browse-camera moves use
                // newLatLngZoom (which preserves pitch), so a tilt the user sets sticks.
                map.uiSettings.isTiltGesturesEnabled = true
                // Two-finger tilt was nearly impossible to trigger (user 2026-07-11): stock
                // shove detection wants both fingers moving in near-perfect vertical parallel
                // (20 degrees). Widen the accepted angle and drop the start threshold so a
                // casual two-finger drag tilts.
                runCatching {
                    map.gesturesManager.shoveGestureDetector.maxShoveAngle = 55f
                    map.gesturesManager.shoveGestureDetector.pixelDeltaThreshold = 8f
                }
                map.setMaxPitchPreference(70.0)
                // Tap a labelled POI on the map to open it. (Named so the D-pad
                // controller's OK-at-crosshair runs the EXACT same resolution path;
                // docs/dpad.md.)
                val handleTap = handleTap@{ tapped: MLLatLng ->
                    mapTap.value()
                    val p = map.projection.toScreenLocation(tapped)
                    // Generous hit radius (~16dp) so taps near a POI icon register —
                    // a tight box made the bigger markers feel un-tappable.
                    val r = density.density * 24f
                    val feats = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r))
                    // NEAREST-TO-FINGER, in screen pixels. queryRenderedFeatures returns render-stack
                    // order, and every pick here used to take firstOrNull - so with the generous 48dp
                    // hit box swallowing several icons at street zoom, "whichever the renderer listed
                    // first" won, not the icon under the finger (user 2026-07-14, dense strip mall:
                    // taps kept opening a neighbour even dead-on the right icon).
                    fun screenDist2(f: Feature): Double {
                        val pt = f.geometry() as? Point ?: return Double.MAX_VALUE
                        val sp = map.projection.toScreenLocation(MLLatLng(pt.latitude(), pt.longitude()))
                        val dx = (sp.x - p.x).toDouble()
                        val dy = (sp.y - p.y).toDouble()
                        return dx * dx + dy * dy
                    }
                    // The parking pin outranks everything — it's a single deliberate object.
                    if (map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), PARKING_LAYER).isNotEmpty()) {
                        parkingTap.value()
                        return@handleTap true
                    }
                    // Our own search-result pins take priority over basemap POI labels.
                    val pin = feats.filter { it.hasProperty(MARKER_INDEX_PROP) }.minByOrNull(::screenDist2)
                    if (pin != null) {
                        markerTap.value(pin.getNumberProperty(MARKER_INDEX_PROP).toInt())
                        return@handleTap true
                    }
                    // A canonical GTFS stop icon (Transitous layer): open the stop's board directly
                    // by stop id - no name resolution at all.
                    val gtfsStop = feats.filter { it.hasProperty(TRANSIT_STOP_INDEX_PROP) }.minByOrNull(::screenDist2)
                    if (gtfsStop != null) {
                        transitStopsNow.value.getOrNull(gtfsStop.getNumberProperty(TRANSIT_STOP_INDEX_PROP).toInt())
                            ?.let { st -> transitStopTap.value(st); return@handleTap true }
                    }
                    // Ambient Google POIs and NAMED basemap POIs are the same kind of thing at street
                    // zoom - two interleaved layers of businesses - so they compete by DISTANCE, not by
                    // class: the old absolute priority let an ambient DOT (a few px wide, all but
                    // invisible) anywhere in the box steal a tap landed dead on a basemap icon. The
                    // nearest candidate across both wins; ambient still carries its richer data when
                    // it IS the nearest. (Handled below, after the alternate-route check, so a route
                    // pick keeps its priority.)
                    val amb = feats.filter { it.hasProperty(AMBIENT_INDEX_PROP) }.minByOrNull(::screenDist2)
                    // Tap a greyed alternate route line to switch to it (Google-style).
                    val altHit = map.queryRenderedFeatures(
                        RectF(p.x - r, p.y - r, p.x + r, p.y + r), ALT_ROUTE_LAYER,
                    ).firstOrNull { it.hasProperty(ALT_INDEX_PROP) }
                    if (altHit != null) {
                        selectAlt.value(altHit.getNumberProperty(ALT_INDEX_PROP).toInt())
                        return@handleTap true
                    }
                    // POIs are named Points; some only carry name:latin/name:en, so
                    // try those too — more icons become directly tappable that way.
                    fun nameOf(f: Feature): String? = sequenceOf("name", "name:latin", "name:en")
                        .firstOrNull { f.hasProperty(it) && !f.getStringProperty(it).isNullOrBlank() }
                        ?.let { f.getStringProperty(it) }
                    val hit = feats
                        .filter {
                            it.geometry() is Point && nameOf(it) != null &&
                                !it.hasProperty(AMBIENT_INDEX_PROP) && !it.hasProperty(MARKER_INDEX_PROP) &&
                                !it.hasProperty(TRANSIT_STOP_INDEX_PROP)
                        }
                        .minByOrNull(::screenDist2)
                    // The BUSINESS pick: ambient vs basemap POI by distance to the finger (see above).
                    if (amb != null && (hit == null || screenDist2(amb) <= screenDist2(hit))) {
                        ambientTap.value(amb.getNumberProperty(AMBIENT_INDEX_PROP).toInt())
                        return@handleTap true
                    }
                    if (hit != null) {
                        val pt = hit.geometry() as Point
                        // The POI's kind (OMT subclass, e.g. "bus_stop"/"station", else class) tells
                        // onPoiTap whether it's a transit stop, so it can resolve to the STOP rather than
                        // the road junction its intersection-name would otherwise search to (issue #71).
                        val kind = hit.getStringProperty("subclass") ?: hit.getStringProperty("class")
                        poiTap.value(nameOf(hit)!!, LatLng(pt.latitude(), pt.longitude()), kind)
                        return@handleTap true
                    }
                    val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
                    // A tapped HOUSE-NUMBER label — the basemap `vela-housenumber` (OSM addr:housenumber)
                    // or the streamed address overlay (`vela-addr-*`). Snap the pin to that LABEL'S OWN
                    // point, not the finger, so tapping "5611" resolves to 5611's address instead of a
                    // fuzzy reverse-geocode of wherever the tap landed. The reverse-geocode at that exact
                    // point returns the house (offline: nearest mapped house ≤60 m == this one).
                    val addrLayers = (sequenceOf("vela-housenumber") +
                        (map.style?.layers?.asSequence()?.map { it.id }?.filter { it.startsWith("vela-addr-") }
                            ?: emptySequence())).toList().toTypedArray()
                    val addrHit = if (addrLayers.isNotEmpty()) {
                        // Nearest number to the finger, not render order — two house numbers can share
                        // the 48dp box on adjacent townhomes.
                        map.queryRenderedFeatures(box, *addrLayers).filter { it.geometry() is Point }
                            .minByOrNull(::screenDist2)
                    } else null
                    if (addrHit != null) {
                        val pt = addrHit.geometry() as Point
                        val num = when {
                            addrHit.hasProperty("housenumber") -> addrHit.getStringProperty("housenumber")
                            addrHit.hasProperty("number") -> addrHit.getStringProperty("number")
                            else -> null
                        }
                        if (!num.isNullOrBlank()) {
                            addrLabelTap.value(num, LatLng(pt.latitude(), pt.longitude()))
                        } else {
                            longPress.value(LatLng(pt.latitude(), pt.longitude()))
                        }
                        return@handleTap true
                    }
                    // An unnamed POI icon (has a class but no name — an apartment
                    // gym, an unnamed park/playground, …) used to be a dead tap.
                    // Reverse-geocode the spot to a pin + address, like a long-press.
                    if (feats.any { it.geometry() is Point && it.hasProperty("class") }) {
                        longPress.value(LatLng(tapped.latitude, tapped.longitude))
                        return@handleTap true
                    }
                    // A tapped BUILDING footprint — OSM basemap fill (`building`/`building-3d`) or the
                    // streamed footprint overlay (`vela-ovl-*`). Makes a plain house/business building
                    // tappable, not only long-pressable: the finger is inside the polygon so reverse-
                    // geocoding the tapped point returns that building's address. Empty land has no
                    // footprint here, so it falls through to `false` and only a long-press drops a raw
                    // coordinate pin there (as before).
                    val bldgLayers = (sequenceOf("building", "building-3d") +
                        (map.style?.layers?.asSequence()?.map { it.id }?.filter { it.startsWith("vela-ovl-") }
                            ?: emptySequence())).toList().toTypedArray()
                    if (map.queryRenderedFeatures(box, *bldgLayers).isNotEmpty()) {
                        longPress.value(LatLng(tapped.latitude, tapped.longitude))
                        return@handleTap true
                    }
                    false
                }
                map.addOnMapClickListener { handleTap(it) }
                // Only flag camera settling when the user dragged the map (not
                // our own programmatic framing) → drives "Search this area".
                map.addOnCameraMoveStartedListener { reason ->
                    gestureMove[0] = reason ==
                        MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                    // The user grabbing the map is a signal in its own right — MapScreen uses it
                    // to drop the results sheet down out of the way (Google's behaviour).
                    if (gestureMove[0]) userPan.value()
                }
                // Tell a PAN from a PINCH during nav (the move-started reason can't): a pan
                // detaches the follow-camera so you can look around (the Re-center button
                // reattaches it), but a PINCH keeps following — it just changes the zoom you're
                // followed at. While actively pinching, `scaling` suppresses the follow animation
                // so it can't fight your fingers; on release we adopt your zoom as the override.
                map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(detector: MoveGestureDetector) {}
                    // Detach on a genuine PAN — decided in onMove, NOT onMoveBegin: by the time
                    // onMove fires, onScaleBegin has already set `scaling` for a pinch, so a pinch's
                    // incidental translation isn't mistaken for a pan (that misread is what made the
                    // camera detach + stop tracking the instant you zoomed). A pan drops the pinch
                    // zoom too, so a later Re-center returns to auto-zoom. navPanned is idempotent.
                    override fun onMove(detector: MoveGestureDetector) {
                        // A shove's incidental translation must not read as a pan either (same
                        // misread the scaling guard fixes for pinch) - it detached the camera the
                        // moment a two-finger tilt started.
                        if (navModeHolder.value && !scaling[0] && !shoving[0]) {
                            navPanned.value()
                            navUserZoom[0] = Double.NaN
                        }
                    }
                    override fun onMoveEnd(detector: MoveGestureDetector) {}
                })
                map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                    override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                        scaling[0] = true
                        browseZoomGoal[0] = Double.NaN // fingers beat a pending locate-tap zoom
                    }
                    // Capture the zoom CONTINUOUSLY (not only on end) so the override is set even
                    // if the end callback is missed; we keep FOLLOWING at it and never detach.
                    override fun onScale(detector: StandardScaleGestureDetector) {
                        if (navModeHolder.value) navUserZoom[0] = map.cameraPosition.zoom
                    }
                    override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                        if (navModeHolder.value) navUserZoom[0] = map.cameraPosition.zoom
                        scaling[0] = false
                    }
                })
                // Two-finger TILT (shove). Without this the nav ticker kept writing its own tilt
                // every frame and the gesture jittered and lost (user 2026-07-15). The shove flag
                // makes the ticker step aside like a pinch, and the resulting tilt sticks as an
                // override the same way a pinch zoom does. Cleared when nav ends.
                map.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                    override fun onShoveBegin(detector: ShoveGestureDetector) { shoving[0] = true }
                    override fun onShove(detector: ShoveGestureDetector) {
                        if (navModeHolder.value) navUserTilt[0] = map.cameraPosition.tilt
                    }
                    override fun onShoveEnd(detector: ShoveGestureDetector) {
                        if (navModeHolder.value) navUserTilt[0] = map.cameraPosition.tilt
                        shoving[0] = false
                    }
                })
                map.addOnCameraIdleListener {
                    if (gestureMove[0]) {
                        gestureMove[0] = false
                        map.cameraPosition.target?.let { t ->
                            cameraIdle.value(LatLng(t.latitude, t.longitude))
                        }
                    }
                    // Keep the VM's "area you're viewing" current so the offline
                    // download can be triggered from Settings, not a map FAB.
                    val b = map.projection.visibleRegion.latLngBounds
                    viewport.value(
                        b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast,
                        map.cameraPosition.zoom,
                    )
                }
                // Feed the on-screen scale bar: metres-per-pixel at the centre
                // latitude (varies with zoom AND latitude on a Mercator map).
                val reportScale = {
                    map.cameraPosition.target?.let { t ->
                        val mpp = map.projection.getMetersPerPixelAtLatitude(t.latitude)
                        mPerPxHolder[0] = mpp
                        // This fires on EVERY camera-move frame. Only push to compose state when the
                        // value moved enough to change the drawn bar (>1%): an unconditional write
                        // recomposed the scale bar per pan frame for invisible sub-percent latitude
                        // drift — wasted main-thread work right when a slow phone can least afford it.
                        if (lastScaleReport[0] <= 0.0 ||
                            kotlin.math.abs(mpp - lastScaleReport[0]) > lastScaleReport[0] * 0.01
                        ) {
                            lastScaleReport[0] = mpp
                            scaleChanged.value(mpp)
                        }
                    }
                    Unit
                }
                map.addOnCameraMoveListener {
                    reportScale()
                    // Keep the walk/bike dot spacing constant WHILE zooming, not just at idle —
                    // gated to ~0.2-zoom steps so it's a handful of cheap regens per zoom doubling.
                    if (dashDotPoly.isNotEmpty() &&
                        kotlin.math.abs(map.cameraPosition.zoom - dashDotZoom) > 0.2
                    ) {
                        map.getStyle { st -> regenRouteDots(map, st, dashDotPoly) }
                    }
                }
                reportScale()
                // Press-and-hold anywhere → drop a pin and reverse-geocode it.
                map.addOnMapLongClickListener { p ->
                    longPress.value(LatLng(p.latitude, p.longitude))
                    true
                }
                // D-pad control seam (docs/dpad.md): key-driven pan/zoom/select reuses the
                // SAME tap resolution, long-press, gesture-flag and nav-zoom-override paths
                // the touch listeners use, so behaviour is identical either way in.
                dpadHolder.value?.let { c ->
                    c.mapView = mv
                    c.map = map
                    c.onTap = { handleTap(it) }
                    c.onLongPress = { pt -> longPress.value(LatLng(pt.latitude, pt.longitude)) }
                    c.markPan = {
                        gestureMove[0] = true
                        if (navModeHolder.value) {
                            navPanned.value()
                            navUserZoom[0] = Double.NaN
                        }
                    }
                    c.markZoom = { z ->
                        if (navModeHolder.value) navUserZoom[0] = z else gestureMove[0] = true
                    }
                }
                // The built-in compass reorients to north on tap, which the nav follow overrides a
                // frame later - useless mid-drive. Route the tap through the app instead (nav
                // toggles heading-up/north-up with it, user 2026-07-15); unconsumed taps get the
                // stock reorient animation.
                runCatching {
                    // An `is` TYPE check, never a class-NAME match: R8 renames MapLibre's
                    // CompassView in release builds, so simpleName == "CompassView" silently
                    // found nothing and the toggle was dead in every release APK (caught by
                    // the 2026-07-15 simulated-drive smoke test).
                    fun findCompass(v: android.view.View): android.view.View? {
                        if (v is org.maplibre.android.maps.widgets.CompassView) return v
                        if (v is android.view.ViewGroup) {
                            for (i in 0 until v.childCount) findCompass(v.getChildAt(i))?.let { return it }
                        }
                        return null
                    }
                    findCompass(mapView)?.setOnClickListener {
                        if (!onCompassTapHolder.value()) {
                            map.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
                        }
                    }
                }
                mapRef = map
            }
        }
        val map = mapRef ?: return@AndroidView
        // Keep the compass clear of the status bar (insets are ready post-layout).
        map.uiSettings.setCompassMargins(0, compassTopPx, compassRightPx, 0)
        // Browse keeps Google's fade-when-north; NAV shows the compass the whole drive - a
        // stationary route start is often still north-up, which faded it out right when the
        // user looked for it (user 2026-07-14; Google pins it during nav too).
        map.uiSettings.setCompassFadeFacingNorth(!navMode)

        // Fraction of the route already driven (for the traversed-grey gradient) —
        // 0 unless we're navigating and on the line.
        val routeProgress = when {
            // Split the traversed-grey at the puck's DRAWN position (progressM — exactly where
            // the arrow is rendered), not the target it's easing toward (targetM). Using targetM
            // left the grey/colour boundary a few metres off the arrow, so the transition peeked
            // out instead of sitting under the puck ("gradient not completely under the arrow").
            navMode && navPuck.engaged && routeCum.isNotEmpty() && routeCum.last() > 0.0 ->
                (navPuck.progressM / routeCum.last()).toFloat().coerceIn(0f, 1f)
            navMode && myLocation != null && routePolyline.size >= 2 -> progressAlong(routePolyline, myLocation)
            else -> 0f
        }
        // Nav puck map-matching, OsmAnd-style (modelled on its RoutingHelper): snap the fix
        // onto the route for a steady on-road puck + heading, but once engaged ONLY ever search
        // a bounded look-ahead FORWARD of our current progress — never behind, never the whole
        // route — so the camera can't be yanked onto a parallel or earlier leg where the route
        // runs near itself (the "pans to a random spot along the route" bug). Off-route / not
        // navigating → raw.
        val snap = if (navMode && myLocation != null && routePolyline.size >= 2) {
            if (navPuck.engaged) {
                // Bounded forward look-ahead, scaled with speed so a multi-second GPS gap still
                // catches up; a small back-tolerance absorbs standstill jitter. Strictly ahead,
                // so a self-approaching route can't pull us onto the other pass. The perpendicular
                // tolerance also scales with speed (22 m parked → ~35 m at highway speed): OSRM
                // geometry can sit half a road-width off the driven lane on wide/divided roads,
                // and at 70 mph a run of misses froze the puck mid-drive for 6-8 s ("glitching
                // out"). The heading gate is SKIPPED when stopped — myBearing holds its pre-stop
                // value through a light, and a stale bearing vetoing valid snaps right after a
                // turn was another way the puck wedged.
                // Size the look-ahead from the speed AT the last accepted fix, not the live
                // (decaying) model — during a 5-8 s outage the decay would otherwise SHRINK the
                // window exactly when the resume fix needs it big, costing a disengage cycle.
                val aheadSpeed = maxOf(navPuck.speed, navPuck.speedAtAccept)
                val ahead = (aheadSpeed * 8.0).coerceIn(150.0, 600.0)
                snapToRouteWindowed(
                    myLocation,
                    if (navPuck.kalman.speed < 1.0) null else myBearing,
                    routePolyline, routeCum, navPuck.targetM - 25.0, navPuck.targetM + ahead,
                    maxM = 22.0 + aheadSpeed.coerceIn(0.0, 13.0),
                )
            } else {
                // Not yet engaged (nav start, or the ticker just re-keyed on a reroute): one
                // global acquisition to find where we are on this route, then forward-only.
                snapToRouteWindowed(myLocation, myBearing, routePolyline, routeCum, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            }
        } else null
        val displayLoc = snap?.first ?: myLocation
        // Browse cone points the device-facing compass (sensor) when we have it — a stopped
        // phone has no GPS course, so this is the only honest "which way am I facing". Nav is
        // unaffected: there `snap.second` (the road heading) wins, and off-route falls to myBearing.
        // Off-route/no-course fallback while NAVIGATING: use the engaged puck's own route-derived
        // heading (the ticker seeds displayBearing from the road segment) so the ARROW still renders.
        // This is the replay fix: recorded traces often carry no per-fix bearing (no doppler at low
        // speed / older devices), so `myBearing` is null and, with no snap, displayBearing went null —
        // which hid the arrow and left only the dot. The puck always has a route bearing when engaged.
        val displayBearing = snap?.second ?: (if (!navMode) compassHeading else null) ?: myBearing
            ?: (if (navMode && navPuck.engaged) navPuck.displayBearing.takeIf { !it.isNaN() } else null)
        // While the free-drive ticker owns the source, hand applyData the SMOOTHED beam so a
        // compass-driven recomposition can't repaint the raw (jittery) angle between frames.
        val meBearing = if (!navMode && driveFollowing && !browseBeam[0].isNaN()) browseBeam[0] else displayBearing
        // Same guard for the POSITION (user drive 2026-07-14: camera smooth, dot jolting once a
        // second): the ticker draws the puck at the EASED follow point, but each fix's
        // recomposition ran this paint with the RAW fix - the dot teleported forward and the
        // ticker's next frame pulled it back. Paint the eased point while the ticker owns it.
        val mePaint = if (!navMode && driveFollowing && !browseCam[0].isNaN()) LatLng(browseCam[0], browseCam[1]) else displayLoc
        // Feed this fix to the puck motion model (the frame ticker above does the gliding).
        // Gated on the fix being NEW (identity — the ViewModel makes a fresh LatLng per fix and
        // recompositions re-pass the same instance): this block runs in a recomposing scope, and
        // kalman.update / reckonedM=0 / targetAtMs=now / missCount++ are NOT idempotent — an
        // unrelated recomposition (stale-flag flip, mute toggle) would re-inject a stale GPS
        // speed at high gain (undoing the accelerometer's braking) and re-open the blind
        // reckoning window; the miss branch would count phantom misses toward disengage.
        val newFix = myLocation != null && myLocation !== navPuck.lastFixLoc
        if (navMode && newFix && snap != null) {
            navPuck.lastFixLoc = myLocation
            val m = snap.third
            val now = android.os.SystemClock.elapsedRealtime()
            var accepted = false
            if (!navPuck.engaged) {
                navPuck.progressM = m; navPuck.targetM = m; navPuck.engaged = true
                navPuck.fwdRejects = 0
                accepted = true
            } else {
                // Monotonic forward only: ease in a plausible advance (speed × elapsed × 2.5 +
                // 60 m absorbs a real GPS gap), reject anything else and let dead reckoning hold.
                // Elapsed measured in TRACE time (replays deliver fixes speedup× faster than the
                // wall clock but the ground covered per fix is a full trace-second of travel).
                val dtFix = ((now - navPuck.targetAtMs) / 1000.0 * speedupHolder.value.toDouble().coerceAtLeast(1.0))
                    .coerceIn(0.0, 10.0)
                val maxStep = navPuck.speed.coerceAtLeast(1.0) * dtFix * 2.5 + 60.0
                val fwd = m - navPuck.targetM
                when {
                    // Parked-jitter gate: at ~zero modelled speed a small forward hop is GPS
                    // noise, not travel — don't ratchet targetM. The old code accepted EVERY
                    // forward wobble at a red light, so the puck crept ahead, and on pull-away
                    // the real position sat BEHIND the crept target → every fix rejected as
                    // backward → the puck froze until the car re-drove the phantom metres
                    // ("progression halts as if I'm not moving"). Thresholds sized for the
                    // slowest real traveller: a stroll is ~0.9-1.4 m/s (must flow fix-by-fix),
                    // parked doppler noise reads < ~0.4; queue-creep below even that still gets
                    // in once it accumulates past the 8 m noise floor.
                    fwd in 0.0..maxStep && (navPuck.kalman.speed > 0.5 || fwd > 8.0) -> {
                        navPuck.targetM = m
                        accepted = true
                    }
                    // An over-cap forward jump that PERSISTS is the new reality (a long fix gap
                    // at speed) — accept on the 2nd consecutive one instead of deadlocking:
                    // maxStep is computed from the (near-zero, post-stop) modelled speed, so a
                    // genuine catch-up could exceed it every time while snaps kept succeeding,
                    // freezing targetM for 10+ s mid-drive.
                    fwd > maxStep -> {
                        navPuck.fwdRejects += 1
                        if (navPuck.fwdRejects >= 2) {
                            navPuck.targetM = m
                            accepted = true
                        }
                    }
                    // Backward / parked-gated: hold — dead reckoning + monotonic draw cover it.
                    // ALSO break the over-cap streak: fwdRejects means CONSECUTIVE over-cap
                    // fixes; without this reset, two isolated multipath spikes minutes apart at
                    // a red light (hundreds of gated jitter fixes in between) would count as
                    // "persistent" and drive the puck ~100 m through the light.
                    else -> navPuck.fwdRejects = 0
                }
                if (accepted) navPuck.fwdRejects = 0
            }
            navPuck.missCount = 0
            if (accepted) {
                navPuck.targetAtMs = now // anchor for dead reckoning
                navPuck.reckonedM = 0.0  // a fresh ACCEPTED fix re-anchors — the integral restarts
                // (a REJECTED fix must NOT re-open the 2 s blind window: at a standstill that
                // re-armed the creep every second; the old anchor stays until a fix is accepted)
            }
            // The fix's OWN (spike-filtered) measurement is the Kalman MEASUREMENT; the
            // accelerometer steers between fixes (predict step in the frame ticker). Feeding the
            // held state speed here — the old code — re-injected the stale braking speed at
            // near-unity gain through every stop: the stuck-mph/creeping-puck bug. A doppler-less
            // fix simply doesn't measure (predict + decay carry the model).
            mySpeedRaw?.let { navPuck.kalman.update(it.toDouble()) }
            navPuck.speed = navPuck.kalman.speed
            if (accepted) navPuck.speedAtAccept = navPuck.speed
        } else if (navMode && newFix && navPuck.engaged) {
            navPuck.lastFixLoc = myLocation
            // Nothing ahead on the route within tolerance: a GPS spike, or we've drifted off it.
            // HOLD — stay engaged so the ticker keeps dead-reckoning forward along the route —
            // rather than the old global re-snap that teleported the camera onto a random leg.
            // Leave targetM / targetAtMs untouched so the dead-reckoning clock keeps running from
            // the last good fix. A short run of misses disengages to re-acquire (3, not the old 6
            // — at 1 Hz that's still 3 s of frozen puck, and NavEngine's off-route detection
            // (45 m × 4 hits) drives the actual reroute in the meantime).
            navPuck.missCount += 1
            if (navPuck.missCount >= 3) navPuck.engaged = false
            navPuck.raw = myLocation
            navPuck.rawBearing = myBearing
        } else if (!navMode || !navPuck.engaged) {
            navPuck.engaged = false
            navPuck.raw = myLocation
            navPuck.rawBearing = myBearing
        }
        // Palette in the key so a Settings colour-set switch reloads the style, same as a theme flip.
        val styleKey = "$styleUri|dark=$darkTheme|pal=${app.vela.ui.MapColors.current()}|sat=$satelliteOn"
        if (appliedStyleKey != styleKey) {
            appliedStyleKey = styleKey
            val builder = if (styleUri.startsWith("asset://")) {
                // Bundled style JSON (Liberty re-pointed at Roboto glyphs). Its
                // tile/sprite/glyph URLs are absolute, so it still loads keyless.
                val json = context.assets.open(styleUri.removePrefix("asset://"))
                    .bufferedReader().use { it.readText() }
                Style.Builder().fromJson(json)
            } else if (styleUri.startsWith("file://")) {
                // MapFonts' patched Liberty (live style re-pointed at the Roboto
                // glyph host). Read + fromJson rather than trusting native file://
                // handling; a vanished/empty file falls back to the plain URL.
                val f = java.io.File(styleUri.removePrefix("file://"))
                val json = runCatching { f.readText() }.getOrNull()
                if (json.isNullOrBlank()) Style.Builder().fromUri(app.vela.core.data.tiles.MapStyle.LIBERTY.uri)
                else Style.Builder().fromJson(json)
            } else {
                Style.Builder().fromUri(styleUri)
            }
            // The moment setStyle is called the OLD Style object is dead: any access (even
            // .layers) throws "Calling getLayers when a newer style is loading". The manual
            // light/dark flip crashed exactly here - the overlay effects are keyed on darkTheme
            // too, so they re-ran in the load window against the stale reference. Null the ref
            // FIRST so every effect bails until the new style lands in the callback.
            styleRef = null
            map.setStyle(builder) { style ->
                styleRef = style
                ensureLayers(style)
                lastAppliedMarkers = null // fresh style = empty sources; force applyData to repopulate
                lastOsmPoiVis = null
                lastControlsVis = null
                parkingApplied = false
                lastAppliedAmbient = null
                lastAppliedControls = null
                lastAppliedFlock = null
                lastAppliedTransitStops = null
                lastTransitBusHidden = null
                origPoiTransitFilter = null
                lastAppliedRouteLine = null
                lastGradM[0] = -1e9 // force the nav split to re-render on the fresh style
                PoiIcons.satellite = satelliteOn
                PoiIcons.addTo(context, style)
                if (applyKeylessTheme) applyMapTheme(style, darkTheme) else tuneMapTiler(style, darkTheme)
                if (satelliteOn) applySatelliteLabels(style)
                emphasizeShields(style)
                applyData(map, style, context, darkTheme, ambientCoversView, routePolyline, routeColor, routeDashed, routeTrafficSpans, alternates, altColor, markers, ambientPois, trafficControls, flockCameras, transitStops, mePaint, meBearing, myAccuracyM, locationStale, previewTarget, routeProgress, navMode, parkingSpot)
                ensureSatellite(style, satelliteOn)
                ensureTraffic(style, trafficOn)
                ensureTransit(style, transitOn)
                ensureTopography(style, topographyOn)
            }
        } else {
            styleRef?.let {
                applyData(map, it, context, darkTheme, ambientCoversView, routePolyline, routeColor, routeDashed, routeTrafficSpans, alternates, altColor, markers, ambientPois, trafficControls, flockCameras, transitStops, mePaint, meBearing, myAccuracyM, locationStale, previewTarget, routeProgress, navMode, parkingSpot)
                ensureSatellite(it, satelliteOn)
                ensureTraffic(it, trafficOn)
                ensureTransit(it, transitOn)
                ensureTopography(it, topographyOn)
            }
        }

        if (previewTarget == null) lastPreviewTarget = null
        // Shift the map's optical centre up by the bottom-sheet height so the
        // focused pin sits in the *visible* strip above the place sheet instead of
        // being hidden behind it. Padding is the map's single source of truth, so
        // every camera move below respects it. Reset to 0 when no sheet is up.
        if (cameraBottomInsetPx != lastInsetPx) {
            // Only re-frame when the sheet APPEARS or grows (lift the pin above it). When it
            // shrinks to 0 (sheet closed) we must NOT null lastCameraTarget — doing so let the
            // else-branch below re-center on the now-stale cameraTarget at a zoomed-out level,
            // yanking the map back to the tapped place and zooming out after you'd panned away.
            val grew = cameraBottomInsetPx > lastInsetPx
            lastInsetPx = cameraBottomInsetPx
            map.setPadding(0, 0, 0, cameraBottomInsetPx)
            if (grew) lastCameraTarget = null // re-frame the current target against the new inset
        }
        // While the results sheet is closed (or a place is selected) forget the last marker fit,
        // so pulling the list back up frames the cluster again even after a manual pan away.
        if (!frameMarkers) lastFittedMarkersKey = null
        when {
            // A recenter TAP always wins — even if we're already on the target (the
            // `target != lastCameraTarget` guard below used to swallow it after a manual pan) or a
            // route/markers would otherwise hold the camera. Force a move to the user, once per tap.
            recenterTick != lastRecenterTick -> {
                lastRecenterTick = recenterTick
                val t = myLocation ?: cameraTarget
                if (t != null) {
                    lastCameraTarget = t
                    // A vague fix (approximate permission / weak network) zooms out to FIT the
                    // accuracy circle: at street zoom a 2 km halo covers the whole screen as an
                    // invisible uniform wash - the blob only reads when its edge is on screen.
                    val acc = myAccuracyM
                    val zoom = when {
                        acc != null && acc > 100f -> {
                            // MapLibre's zoom scale is DENSITY-INDEPENDENT (m per dp, 512-tile
                            // constant), not physical pixels. The first cut used physical px +
                            // the 256-tile constant and landed ~2.5 levels too CLOSE on a 2.75x
                            // phone - the 2 km circle overflowed the screen as the same invisible
                            // uniform wash the fit exists to avoid.
                            val density = mapView.resources.displayMetrics.density
                            val screenDp = (mapView.width / density).coerceAtLeast(1f)
                            val mpd = (acc.toDouble() * 2 / 0.7) / screenDp
                            (Math.log((78271.517 * Math.cos(Math.toRadians(t.lat))) / mpd) / Math.log(2.0)).coerceIn(3.0, 15.0)
                        }
                        cameraBottomInsetPx > 0 -> 16.5
                        else -> 15.0
                    }
                    if (driveFollowing && !navMode) {
                        // The free-drive ticker owns the camera and its per-frame moveCamera
                        // CANCELS an animateCamera mid-flight - the zoom landed wherever the
                        // animation was when interrupted, a different level every tap (user
                        // 2026-07-15: "doesn't bring me back to the standard zoom consistently").
                        // Hand the ticker a zoom GOAL instead; it eases position and zoom together.
                        browseZoomGoal[0] = zoom
                    } else {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(MLLatLng(t.lat, t.lng), zoom), 500)
                    }
                }
            }
            // Previewing a step takes over the camera (and holds, suppressing
            // nav-follow) so you can look ahead at where you'd turn.
            previewTarget != null -> {
                lastNavTarget = null // so nav-follow re-centres cleanly when the preview ends
                if (previewTarget != lastPreviewTarget) {
                    lastPreviewTarget = previewTarget
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            MLLatLng(previewTarget.lat, previewTarget.lng), 16.5,
                        ),
                        700,
                    )
                }
            }

            navMode && myLocation != null && navFollowing -> {
                // The per-frame follow-camera now runs in the motion ticker above (continuous
                // glide — that's what fixes the "stiff" throttled-ease feel). Here we only handle
                // the PRE-ENGAGE / off-route case (puck not snapped to the route yet): a throttled
                // eased re-point to the raw fix until the ticker takes over. Keeping this case
                // matched even when engaged stops a mid-nav reroute from falling through to the
                // fit-route case and zooming the whole route out.
                if (!navPuck.engaged) {
                    val loc = displayLoc ?: myLocation
                    val brg = lastNavBearing ?: displayBearing ?: 0f
                    val moved = lastNavTarget?.let { it.distanceTo(loc) > 4.0 } ?: true
                    val turned = lastNavBearing?.let { kotlin.math.abs(((brg - it + 540f) % 360f) - 180f) > 2f } ?: true
                    if ((moved || turned) && !scaling[0]) {
                        lastNavTarget = loc
                        lastNavBearing = brg
                        val rawSp = (mySpeed ?: 0f).coerceIn(0f, 30f)
                        navZoomSpeed[0] += (rawSp - navZoomSpeed[0]) * 0.3f
                        val zoom = if (!navUserZoom[0].isNaN()) navUserZoom[0]
                            else 18.5 - (navZoomSpeed[0] / 30f) * (18.5 - 15.8) // even closer default (user 2026-07-15, was 18.0-15.5); speed still zooms out
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(MLLatLng(loc.lat, loc.lng))
                                    .zoom(zoom)
                                    .tilt(if (navNorthUp) 0.0 else 55.0)
                                    .bearing(if (navNorthUp) 0.0 else brg.toDouble())
                                    // Same puck-low offset as the engaged follow ticker.
                                    .padding(0.0, map.height * 0.45, 0.0, 0.0)
                                    .build(),
                            ),
                            550,
                        )
                    }
                }
            }

            // Keyed on the insets too, not just the geometry: minimizing the chooser shrinks the
            // bottom inset, and re-running the fit then re-frames the route CLOSER over the freed
            // map (user 2026-07-14); the top-card measurement landing a frame late corrects the
            // same way instead of leaving a fit that ignored it.
            // AUDIT FIX 2 (2026-07-15): this branch stays MATCHED during nav but its body is a
            // no-op swallow - once the nav camera detached (Overview tap, a mid-drive pan), the
            // nav follow branch above stopped matching and evaluation fell through HERE, whose
            // key always mismatches in nav (the chooser insets are gone), so the next ~1 Hz
            // recomposition fired an uninvited 800 ms whole-route flight: mid-drive pans got
            // yanked to a route overview, reroutes-while-detached teleported the view, and the
            // Overview tap raced its own dedicated fit (two flights, loser cancelled mid-air) -
            // the "intermittent" transition hitch. Matched-but-swallowed (not !navMode on the
            // condition) so the branches BELOW stay unreachable during nav, same pattern as the
            // nav follow branch's own pre-engage swallow.
            routePolyline.size >= 2 &&
                (navMode || (routePolyline.hashCode() * 31 + cameraBottomInsetPx * 7 + cameraTopInsetPx) != lastFittedRouteKey) -> {
                if (!navMode) { // in-nav framing is owned by the follow ticker + navOverviewTick
                    lastFittedRouteKey = routePolyline.hashCode() * 31 + cameraBottomInsetPx * 7 + cameraTopInsetPx
                    val builder = MLLatLngBounds.Builder()
                    routePolyline.forEach { builder.include(MLLatLng(it.lat, it.lng)) }
                    // Reserve room at the bottom for the directions panel AND at the top for the
                    // endpoints card, so the route's start/end frame in the VISIBLE strip between
                    // them instead of hiding behind either (user 2026-07-14).
                    val pad = 140
                    val bottom = if (cameraBottomInsetPx > 0) cameraBottomInsetPx + pad else pad
                    val top = if (cameraTopInsetPx > 0) cameraTopInsetPx + pad else pad
                    val bounds = builder.build()
                    // A continental trip fit zooms out until nothing has context; past ~12 degrees
                    // of span, frame the DESTINATION area instead - the end point is the part worth
                    // seeing (user 2026-07-14), and the route line still leads off-screen toward it.
                    val huge = bounds.latitudeSpan > 12.0 || bounds.longitudeSpan > 14.0
                    runCatching {
                        if (huge) {
                            val end = routePolyline.last()
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(MLLatLng(end.lat, end.lng), 9.0), 800,
                            )
                            return@runCatching
                        }
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(bounds, pad, top, pad, bottom), 800,
                        )
                    }
                }
            }

            frameMarkers && markers.isNotEmpty() && markers.hashCode() != lastFittedMarkersKey -> {
                lastFittedMarkersKey = markers.hashCode()
                // Consume the pending camera target: the results-sheet inset growing nulls
                // lastCameraTarget (to re-frame a place against the sheet), and with it null the
                // else-branch below re-fires on the STALE center (the VM center only updates on
                // user gestures) — one recomposition after this frame it yanked the camera back
                // to wherever you were before the search (device-seen 2026-07-09).
                lastCameraTarget = cameraTarget
                // Frame the result CLUSTER, not every last pin: a single stray hit hundreds of
                // miles away (Google pads sparse local searches with far matches) used to zoom
                // the camera out to a continental view. Median-center the pins and drop outliers
                // beyond 4x the median spread (min 40 km) before fitting (user 2026-07-09).
                val pts = markers.map { it.location }
                val medLat = pts.map { it.lat }.sorted()[pts.size / 2]
                val medLng = pts.map { it.lng }.sorted()[pts.size / 2]
                val med = app.vela.core.model.LatLng(medLat, medLng)
                val dists = pts.map { it.distanceTo(med) }.sorted()
                val cutoff = maxOf(dists[dists.size / 2] * 4, 40_000.0)
                val cluster = pts.filter { it.distanceTo(med) <= cutoff }
                if (cluster.size == 1) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            MLLatLng(cluster[0].lat, cluster[0].lng), 15.0,
                        ),
                    )
                } else {
                    val builder = MLLatLngBounds.Builder()
                    cluster.forEach { builder.include(MLLatLng(it.lat, it.lng)) }
                    // Keep the cluster above the results sheet (peek covers the bottom half).
                    val bottom = if (cameraBottomInsetPx > 0) cameraBottomInsetPx + 160 else 160
                    runCatching {
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 160, 160, 160, bottom), 700)
                    }
                }
            }

            // Free-drive follow owns the camera from the per-frame ticker above; swallow the
            // generic recenter-on-fix below so a new GPS fix (which changes myLocation every
            // second) doesn't fire an animateCamera that fights the smooth glide.
            !navMode && driveFollowing && myLocation != null -> {
                lastCameraTarget = myLocation // keep the else-branch baseline current for when follow ends
            }

            else -> {
                val target = cameraTarget ?: myLocation
                if (target != null && target != lastCameraTarget) {
                    lastCameraTarget = target
                    // Zoom in closer when a place sheet is up (focusing a single pin),
                    // looser for a plain recenter - unless a deep link asked for its own zoom.
                    val zoom = cameraTargetZoom ?: if (cameraBottomInsetPx > 0) 16.5 else 14.5
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(MLLatLng(target.lat, target.lng), zoom),
                    )
                }
            }
        }
    }
}

private fun ensureLayers(style: Style) {
    // Kill the style light: MapLibre lights fill-extrusion faces toward white (default
    // intensity 0.5), so at z16+ the building-3d tops rendered ~40% brighter than the
    // palette (#1c3b69 became #2e5590) while Google keeps buildings the SAME colour at
    // every zoom (pixel-proven side by side, user 2026-07-11). Intensity 0 makes the
    // extrusion render its set colour verbatim; the vertical-gradient flags on the
    // building-3d layers (applyLight/applyDark) kill the remaining side shading.
    runCatching { style.light?.setIntensity(0f) }
    // FLAT vegetation, like Google (user 2026-07-11). Two parts. (1) Liberty's wetland +
    // pedestrian-plaza layers ship with a fill-PATTERN (fern hatch / dots), and setting
    // fill-pattern to an empty literal does NOT clear it on device (both themes tried - the
    // repeating icons kept rendering). So the patterned originals are hidden outright and
    // clean flat twins take their place; applyLight/applyDark colour the twins.
    style.getLayer("landcover_wetland")?.setProperties(PropertyFactory.visibility(Property.NONE))
    style.getLayer("road_area_pattern")?.setProperties(PropertyFactory.visibility(Property.NONE))
    if (style.getLayer("vela-wetland") == null && style.getLayer("landcover_wetland") != null) {
        val wet = FillLayer("vela-wetland", "openmaptiles").withSourceLayer("landcover")
            .withFilter(Expression.eq(Expression.get("class"), "wetland"))
        wet.minZoom = 12f
        style.addLayerAbove(wet, "landcover_wetland")
        val plaza = FillLayer("vela-plaza", "openmaptiles").withSourceLayer("transportation")
            .withFilter(
                Expression.match(
                    Expression.geometryType(), Expression.literal(false),
                    Expression.stop("Polygon", true), Expression.stop("MultiPolygon", true),
                ),
            )
        // Guard the anchor itself: this block is gated on landcover_wetland existing, but a
        // Liberty drift could drop road_area_pattern alone and the addLayerAbove would throw
        // on every style load (review 2026-07-11).
        if (style.getLayer("road_area_pattern") != null) style.addLayerAbove(plaza, "road_area_pattern")
    }
    // Sports fields (pitch/playground/track/stadium) get their own accent fill - the Google
    // app tints them a touch lighter than the surrounding park (dark #0d4956 vs #0d3847,
    // sampled on the P9 side-by-side 2026-07-11). Drawn above the vegetation fills.
    if (style.getLayer("vela-pitch") == null && style.getLayer("park") != null) {
        val pitch = FillLayer("vela-pitch", "openmaptiles").withSourceLayer("landuse")
            .withFilter(
                Expression.match(
                    Expression.get("class"), Expression.literal(false),
                    Expression.stop("pitch", true), Expression.stop("playground", true),
                    Expression.stop("track", true), Expression.stop("stadium", true),
                ),
            )
        pitch.minZoom = 13f
        style.addLayerAbove(pitch, "park")
    }
    // Commercial/retail blocks: the Google APP tints them cream in light mode (sampled
    // #fdf9ef on the P9, 2026-07-11) - Liberty ships no layer for those classes at all,
    // so this twin draws them; dark mode paints it the other-landuse navy (no change).
    if (style.getLayer("vela-commercial") == null && style.getLayer("park") != null) {
        val comm = FillLayer("vela-commercial", "openmaptiles").withSourceLayer("landuse")
            .withFilter(
                Expression.match(
                    Expression.get("class"), Expression.literal(false),
                    Expression.stop("commercial", true), Expression.stop("retail", true),
                ),
            )
        comm.minZoom = 12f
        style.addLayerAbove(comm, "park")
    }
    // Park TRAILS, Google-style green lines (dark #167055, sampled 2026-07-11).
    // Liberty's own path layers stay hidden (class path+pedestrian = every sidewalk, the
    // June "weird walking tracks" clutter); this twin keeps ONLY the deliberate FOOT trail
    // network - subclass path/bridleway - and skips footway/steps/pedestrian. Dedicated bike
    // paths (subclass cycleway) get their OWN teal layer below (Google's bike-route accent).
    if (style.getLayer("vela-trails") == null && style.getLayer("road_minor") != null) {
        val pathWidth = Expression.interpolate(
            Expression.exponential(1.4f), Expression.zoom(),
            Expression.stop(14f, 0.7f), Expression.stop(16f, 1.6f), Expression.stop(19f, 4f),
        )
        val trails = LineLayer("vela-trails", "openmaptiles").withSourceLayer("transportation")
            .withFilter(
                Expression.all(
                    Expression.match(
                        Expression.geometryType(), Expression.literal(false),
                        Expression.stop("LineString", true), Expression.stop("MultiLineString", true),
                    ),
                    Expression.eq(Expression.get("class"), Expression.literal("path")),
                    Expression.match(
                        Expression.get("subclass"), Expression.literal(false),
                        Expression.stop("path", true), Expression.stop("bridleway", true),
                    ),
                ),
            )
        trails.minZoom = 14f
        trails.setProperties(PropertyFactory.lineWidth(pathWidth), PropertyFactory.lineCap(Property.LINE_CAP_ROUND))
        // Under the real streets so a trail crossing merges beneath the road, like Google.
        style.addLayerBelow(trails, "road_minor")

        // Dedicated bike paths (OSM highway=cycleway) in Google's teal accent. NB the keyless
        // OMT tiles carry off-street cycleways only; ON-STREET painted lanes (cycleway=lane on
        // a road) aren't in the tile schema - those need an Overpass layer (see ROADMAP).
        val bike = LineLayer("vela-bikeroutes", "openmaptiles").withSourceLayer("transportation")
            .withFilter(
                Expression.all(
                    Expression.match(
                        Expression.geometryType(), Expression.literal(false),
                        Expression.stop("LineString", true), Expression.stop("MultiLineString", true),
                    ),
                    Expression.eq(Expression.get("class"), Expression.literal("path")),
                    Expression.eq(Expression.get("subclass"), Expression.literal("cycleway")),
                ),
            )
        bike.minZoom = 14f
        bike.setProperties(PropertyFactory.lineWidth(pathWidth), PropertyFactory.lineCap(Property.LINE_CAP_ROUND))
        style.addLayerBelow(bike, "road_minor")
    }
    // (2) The OSM poi tiers scatter park/garden/tree icons across every wood - Google keeps
    // forests flat colour. Rebuild each tier's rank-band filter with vegetation excluded.
    run {
        val isPoint = Expression.match(
            Expression.geometryType(), Expression.literal(false),
            Expression.stop("Point", true), Expression.stop("MultiPoint", true),
        )
        val veg = Expression.match(
            Expression.get("class"), Expression.literal(false),
            Expression.stop("park", true), Expression.stop("garden", true),
            Expression.stop("picnic_site", true), Expression.stop("wood", true),
            Expression.stop("forest", true), Expression.stop("tree", true),
            Expression.stop("grass", true), Expression.stop("wetland", true),
        )
        fun tier(id: String, lo: Int, hi: Int?) {
            val parts = mutableListOf(isPoint, Expression.gte(Expression.get("rank"), Expression.literal(lo)), Expression.not(veg))
            if (hi != null) parts += Expression.lt(Expression.get("rank"), Expression.literal(hi))
            (style.getLayer(id) as? SymbolLayer)?.setFilter(Expression.all(*parts.toTypedArray()))
        }
        tier("poi_r1", 1, 7)
        tier("poi_r7", 7, 20)
        tier("poi_r20", 20, null)
    }

    if (style.getImage(ME_ARROW_IMG) == null) style.addImage(ME_ARROW_IMG, arrowBitmap())
    if (style.getImage(NAV_PUCK_IMG) == null) style.addImage(NAV_PUCK_IMG, navPuckBitmap())

    // Terrain relief — only over the OpenMapTiles basemap (the keyless path).
    if (style.getSource("openmaptiles") != null) ensureHillshade(style)

    // House numbers at high zoom. OpenFreeMap's tiles carry the OpenMapTiles
    // "housenumber" source-layer; the Liberty style just doesn't draw it.
    // Guarded to the openmaptiles vector source so other styles don't error.
    if (style.getSource("openmaptiles") != null && style.getLayer("vela-housenumber") == null) {
        style.addLayer(
            SymbolLayer("vela-housenumber", "openmaptiles").apply {
                setSourceLayer("housenumber")
                // OpenFreeMap DOES serve the OMT `housenumber` source-layer (verified against the
                // live TileJSON + z14 tiles), so this renders where OSM has `addr:housenumber`.
                // 19 = the ~50 ft scale-bar view: numbers only when truly close, in lockstep with the
                // address-overlay layers; 17.5 still carpeted whole blocks in numbers (user 2026-07-13)
                // (16 carpeted the map — user 2026-07-06). Keep in lockstep with the vela-addr overlay.
                setMinZoom(19f)
                setProperties(
                    PropertyFactory.textField(Expression.get("housenumber")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textColor("#8a8a8a"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(1f),
                    // Same as the vela-addr overlay: numbers yield to everything but never occupy
                    // the collision index — cheaper placement at street zoom, and they can't evict
                    // a business icon whatever the layer order.
                    PropertyFactory.textIgnorePlacement(true),
                )
            },
        )
    }

    if (style.getSource(ROUTE_SRC) == null) {
        // lineMetrics → line-progress works, so we can grey the *traversed* part of the
        // route behind the vehicle (Google-style) with a line-gradient.
        style.addSource(GeoJsonSource(ROUTE_SRC, GeoJsonOptions().withLineMetrics(true)))
        // Insert the route line BELOW the basemap's first label layer (Google-style) so road
        // names and POI text stay legible *on top* of it, instead of being painted over.
        val routeLine = LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#1F6FEB"),
            // Zoom-scaled like Google's stripe (user 2026-07-15: "the blue stripe looks bigger
            // in Google") - a constant 6 px reads THIN at nav zooms (17-18.5) where Google
            // draws it fat over the road. Browse zooms barely change.
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        // The route must draw ABOVE road + BRIDGE geometry (else a bridge paints over it — the "blue line
        // vanishes on a bridge" bug) but BELOW text labels. In Liberty the FIRST symbol layer is
        // `road_one_way_arrow` (idx ~61), which sits BELOW the `bridge_*` layers (~63-82) — anchoring there
        // hid the route on bridges. Anchor instead to the first symbol AFTER the last bridge (a real label),
        // falling back to the first symbol / top if a style has no bridge layers.
        val lastBridge = style.layers.indexOfLast { it.id.startsWith("bridge_") }
        val firstLabel = (if (lastBridge >= 0) style.layers.drop(lastBridge + 1) else style.layers)
            .firstOrNull { it is SymbolLayer }?.id
        if (firstLabel != null) style.addLayerBelow(routeLine, firstLabel) else style.addLayer(routeLine)
        // The dotted foot/bike variant (hidden until a walk/bike route is shown). Google-style
        // CONSTANT-ON-SCREEN dots: a symbol layer placed along the line with a fixed
        // symbol-spacing, which is in SCREEN pixels and therefore zoom-invariant. A line
        // dasharray can never do this — its units are line-widths and MapLibre quantises the
        // dash texture to integer zooms (compressing up to ~2x in between), so dash dots always
        // cram together zoomed out (user report 2026-07-08). The dot is an SDF template so
        // iconColor can restyle it like lineColor did.
        if (style.getImage(ROUTE_DOT_IMG) == null) style.addImage(ROUTE_DOT_IMG, routeDotBitmap())
        // POINT features on their own source, regenerated per zoom (regenRouteDots) — MapLibre's
        // line-placed symbol spacing is computed in tile space and stretches up to ~2x between
        // integer zooms (user: "the gaps between integers are rough"), so we do the spacing math
        // ourselves and the gap is EXACTLY constant on screen at every zoom.
        style.addSource(GeoJsonSource(ROUTE_DOT_SRC))
        val routeDash = SymbolLayer(ROUTE_DASH_LAYER, ROUTE_DOT_SRC).withProperties(
            PropertyFactory.iconImage(ROUTE_DOT_IMG),
            // The dots ARE the route line: they must never be collision-culled or thinned.
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconPadding(0f),
            PropertyFactory.visibility(Property.NONE),
        )
        if (firstLabel != null) style.addLayerBelow(routeDash, firstLabel) else style.addLayer(routeDash)
        // The nav ahead-suffix line (see ROUTE_AHEAD_SRC) — added after ROUTE_LAYER under the same
        // label anchor, so it draws ON TOP of the full (traversed-grey) line during nav.
        style.addSource(GeoJsonSource(ROUTE_AHEAD_SRC, GeoJsonOptions().withLineMetrics(true)))
        val routeAhead = LineLayer(ROUTE_AHEAD_LAYER, ROUTE_AHEAD_SRC).withProperties(
            PropertyFactory.lineColor("#1F6FEB"),
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.visibility(Property.NONE),
        )
        if (firstLabel != null) style.addLayerBelow(routeAhead, firstLabel) else style.addLayer(routeAhead)
    }
    // Greyed, tappable alternate routes — drawn BELOW the active line (Google-style).
    if (style.getSource(ALT_ROUTE_SRC) == null) {
        style.addSource(GeoJsonSource(ALT_ROUTE_SRC))
        val alt = LineLayer(ALT_ROUTE_LAYER, ALT_ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#9AA0A6"),
            PropertyFactory.lineWidth(ALT_ROUTE_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (style.getLayer(ROUTE_LAYER) != null) style.addLayerBelow(alt, ROUTE_LAYER)
        else style.addLayer(alt)
    }
    if (style.getImage(PIN_IMG) == null) style.addImage(PIN_IMG, pinBitmap())
    if (style.getSource(MARKERS_SRC) == null) {
        style.addSource(GeoJsonSource(MARKERS_SRC, GeoJsonOptions().withMaxZoom(12)))
        // Search results, Google's treatment: every result is a RED marker that keeps its
        // category glyph (rated food places get the wide rating bubble instead - see PoiIcons),
        // and the pins COLLIDE by rank instead of stacking - in a dense downtown the best
        // results keep their pins and the rest collapse to the little red dots below, expanding
        // back into pins as you zoom in. Labels sit under the pin and yield when crowded.
        PoiIcons.addResultDot(style)
        style.addLayer(
            SymbolLayer(MARKERS_DOTS_LAYER, MARKERS_SRC).withProperties(
                PropertyFactory.iconImage(PoiIcons.RESULT_DOT_IMG),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            SymbolLayer(MARKERS_LAYER, MARKERS_SRC).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(1.15f),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconIgnorePlacement(false),
                PropertyFactory.iconPadding(2f),
                PropertyFactory.symbolSortKey(Expression.get("rank")),
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(13f),
                // Labels try BELOW the pin first, then the pin's right side, then its left —
                // a below-only anchor made crowded results drop labels (or sit on a neighbour's
                // dot) when the space under the pin was taken; a side slot usually still fits.
                // (Anchor semantics: TOP = text below the point, LEFT = text right of it.)
                PropertyFactory.textVariableAnchor(
                    arrayOf(Property.TEXT_ANCHOR_TOP, Property.TEXT_ANCHOR_LEFT, Property.TEXT_ANCHOR_RIGHT),
                ),
                PropertyFactory.textRadialOffset(0.7f),
                PropertyFactory.textJustify(Property.TEXT_JUSTIFY_AUTO),
                PropertyFactory.textMaxWidth(7f),
                PropertyFactory.textOptional(true),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textColor("#3C4043"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(0.9f),
            ),
        )
    }
    // The saved parking spot: one teal "P" pin above the search pins, tappable.
    if (style.getImage(PARKING_IMG) == null) style.addImage(PARKING_IMG, parkingBitmap())
    if (style.getSource(PARKING_SRC) == null) {
        style.addSource(GeoJsonSource(PARKING_SRC, GeoJsonOptions().withMaxZoom(12)))
        style.addLayer(
            SymbolLayer(PARKING_LAYER, PARKING_SRC).withProperties(
                PropertyFactory.iconImage(PARKING_IMG),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }
    // Ambient Google POIs: small category dots (the same `vela-poi-<group>` images as the OSM POIs,
    // so they read as native map POIs) + a decluttered label. Sits just under the search pins +
    // location dot. Labels themed per-mode in applyLight/applyDark.
    if (style.getSource(AMBIENT_SRC) == null) {
        // AUDIT FIX 8 (2026-07-15): point sources cap their internal tile pyramid at z12
        // (the accuracy circle at 14) - geojson-vt re-cuts the source into tiles at every
        // integer zoom crossed during a flight, and points gain nothing past z12. Strictly
        // less re-cut + placement invalidation per zoom change; line sources keep full
        // depth for vertex precision.
        style.addSource(GeoJsonSource(AMBIENT_SRC, GeoJsonOptions().withMaxZoom(12)))
        style.addLayerBelow(
            SymbolLayer(AMBIENT_LAYER, AMBIENT_SRC).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                // Data-driven size by prominence: low-signal dots ~0.78, anchor stores (Safeway ~7,
                // malls ~9) up to ~1.3 — bigger + more legible, Google-style, at zero per-frame CPU.
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.get("prominence"),
                        Expression.stop(0.0, 0.78f), Expression.stop(8.0, 1.3f),
                    ),
                ),
                // DECLUTTER like Google: let the dots collide (hide when they'd overlap) instead of
                // stacking. allowOverlap+ignorePlacement were TRUE, so every ambient POI drew on top
                // of its neighbours — a pile at tight zooms. Collision + padding spaces them; more
                // appear as you zoom in. (Sorted by rank so the prominent ones win the slot.)
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconIgnorePlacement(false),
                PropertyFactory.iconPadding(1.5f),
                PropertyFactory.symbolSortKey(Expression.get("sort")),
                // LABEL DENSITY copies Google (user 2026-07-14): at browse zooms Google names only
                // the prominent places and lets the rest be bare icons/dots, with more named as you
                // close in - and even at z17 a chunk stays icon-only (A/B'd against the gmaps app on
                // the same downtown frame: ~7 named at z15, ~13 + bare dots at z17). Tiered by
                // zoom x prominence; thresholds map through ambientProminence (ln(reviews+1) x rating
                // factor): 6.0 ~ 400+ reviews, 5.0 ~ 120+, 3.0 ~ 20+. (7.0 was tried and named ZERO
                // downtown - the anchors mostly sit 6-7.) This is ALSO a frame win in
                // dense areas - every label is collision work with four candidate anchors, and an
                // EMPTY textField skips label placement entirely (a textOpacity of 0 would still
                // place and collide it, paying the cost invisibly).
                PropertyFactory.textField(
                    Expression.step(
                        Expression.zoom(),
                        // below z15.5: only true landmarks (malls, big-box, the 1000-review anchors)
                        Expression.switchCase(
                            Expression.gte(Expression.get("prominence"), Expression.literal(6.0)),
                            Expression.get("name"),
                            Expression.literal(""),
                        ),
                        // z15.5+: established places join
                        Expression.stop(
                            15.5f,
                            Expression.switchCase(
                                Expression.gte(Expression.get("prominence"), Expression.literal(5.0)),
                                Expression.get("name"),
                                Expression.literal(""),
                            ),
                        ),
                        // z16.5+ (street level): any real business; 0-review junk stays a bare dot
                        Expression.stop(
                            16.5f,
                            Expression.switchCase(
                                Expression.gte(Expression.get("prominence"), Expression.literal(3.0)),
                                Expression.get("name"),
                                Expression.literal(""),
                            ),
                        ),
                        // z17.5+ (single-lot zoom): everything visible gets its name
                        Expression.stop(17.5f, Expression.get("name")),
                    ),
                ),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.get("prominence"),
                        Expression.stop(0.0, 11f), Expression.stop(8.0, 14f),
                    ),
                ),
                // Google-style label placement. PREFER just to the LEFT of the icon; when that would
                // collide with a neighbour, FALL BACK to sitting UNDER the icon — text-variable-anchor
                // picks the first anchor (right = text left of point, top = text below point) that fits,
                // and hides the label (textOptional) only if neither does. The radial offset is the
                // centre→text-edge gap in ems; 1.4 sits the label right up against the dot (was 2.7 → 2.0 →
                // 1.4 across "too far" reports) while still clearing it. justify=auto so the left form
                // right-justifies and the under form centres. (Tune from a device glance if it crowds the dot.)
                // Four anchor slots (left of the icon, right of it, below, above) instead of the
                // old two — with only left/below to try, a crowded block DROPPED labels (or let
                // one sit on a neighbour's dot) when both slots were taken; a third/fourth side
                // usually still fits. Icons still collide by design; this only helps the labels
                // of the icons that DO render find a clear side (user 2026-07-10).
                PropertyFactory.textVariableAnchor(
                    arrayOf(
                        Property.TEXT_ANCHOR_RIGHT, Property.TEXT_ANCHOR_LEFT,
                        Property.TEXT_ANCHOR_TOP, Property.TEXT_ANCHOR_BOTTOM,
                    ),
                ),
                PropertyFactory.textRadialOffset(1.4f),
                PropertyFactory.textJustify(Property.TEXT_JUSTIFY_AUTO),
                PropertyFactory.textMaxWidth(7f),
                PropertyFactory.textOptional(true),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textColor("#3C4043"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(0.9f),
            ),
            MARKERS_LAYER,
        )
        // The DOT TIER, Google-style: every ambient place also draws as a small category-
        // coloured circle UNDER the icon layer. Icons collide and only the prominent
        // survive a crowded view - the losers used to VANISH; now their dot still marks
        // them (tap works, the rect query reads any layer carrying the index prop), and
        // zooming in upgrades dots to icons as collision slots free up. Circles skip the
        // collision engine entirely, so 140 of them cost ~nothing on a weak GPU (the
        // icon that renders on top simply covers its own dot - the coloured dot is the
        // marker bitmap's centre). Radius scales gently with prominence.
        style.addLayerBelow(
            CircleLayer(AMBIENT_DOT_LAYER, AMBIENT_SRC).withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("dotColor"))),
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.get("prominence"),
                        Expression.stop(0.0, 2.6f), Expression.stop(8.0, 4.2f),
                    ),
                ),
                PropertyFactory.circleStrokeWidth(1.2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.92f),
            ),
            AMBIENT_LAYER,
        )
    }
    // Traffic controls (OSM `highway=traffic_signals`/`stop`): non-interactive icons drawn at high zoom
    // BENEATH the POI dots + pins. Two images keyed by the feature "icon" prop; collision (allowOverlap
    // false) keeps a dense grid of lights legible instead of a pile. minZoom matches the fetch gate.
    if (style.getImage(SIGNAL_IMG) == null) style.addImage(SIGNAL_IMG, trafficLightBitmap())
    if (style.getImage(STOP_IMG) == null) style.addImage(STOP_IMG, stopSignBitmap())
    if (style.getSource(CONTROLS_SRC) == null) {
        style.addSource(GeoJsonSource(CONTROLS_SRC, GeoJsonOptions().withMaxZoom(12)))
        val controlsSize = Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(15.5f, 0.75f),
            Expression.stop(17f, 1.05f),
            Expression.stop(19f, 1.5f),
        )
        // The VISIBLE controls draw ABOVE the route lines and the bridge geometry but BELOW the
        // basemap text and every Vela POI layer. They used to sit at the very BOTTOM of the
        // symbol stack (under the bridges and both blue lines), so the route stripe painted
        // straight over the lights and stop signs along the drive - the icons that matter most
        // in nav were the most covered (user 2026-07-15; Google draws its signals ON the route
        // line). Street names can't be stomped by the move: the CLAIM twin below already places
        // an always-on collision box at every sign, so labels dodge sign positions no matter
        // where the visible icons paint. They still always draw (allowOverlap + ignorePlacement):
        // sparse, one per junction.
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        val visible = SymbolLayer(CONTROLS_LAYER, CONTROLS_SRC).apply {
            setMinZoom(16f)
            setProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                // Zoom-scaled so they read at nav zoom (~16-17.5) and grow as you zoom in — the flat 0.55
                // was too small to spot, especially tilted in nav (user 2026-07-06 wanted them bigger).
                PropertyFactory.iconSize(controlsSize),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPadding(2f),
            )
        }
        when {
            style.getLayer(ROUTE_AHEAD_LAYER) != null -> style.addLayerAbove(visible, ROUTE_AHEAD_LAYER)
            firstSymbol != null -> style.addLayerBelow(visible, firstSymbol)
            else -> style.addLayerBelow(visible, AMBIENT_LAYER)
        }
        // An INVISIBLE claim twin sits where the visible layer used to (above the basemap
        // labels, below Vela's own pins/POIs): it places first with a real collision box, so
        // street names shift away from sign positions instead of printing right next to or on
        // one. Vela's own layers are above it and place before it, so it can never evict a POI.
        style.addLayerBelow(
            SymbolLayer(CONTROLS_CLAIM_LAYER, CONTROLS_SRC).apply {
                setMinZoom(16f)
                setProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(controlsSize),
                    PropertyFactory.iconOpacity(0f),
                    PropertyFactory.iconAllowOverlap(true), // always claims (never yields itself)
                    PropertyFactory.iconIgnorePlacement(false), // ...and others must dodge the claim
                    PropertyFactory.iconPadding(2f),
                )
            },
            AMBIENT_LAYER,
        )
    }
    // ALPR / "Flock" surveillance cameras (community DeFlock mapping in OSM). Its own symbol
    // layer, populated only when the Settings toggle is on (empty source otherwise). Drawn BELOW
    // the ambient POIs (user 2026-07-13, was above): where a camera and a business icon/label
    // share a spot the business wins. The camera itself always draws (allowOverlap) AND claims
    // its collision box (ignorePlacement=false, the controls-claim trick): symbols placing after
    // it - the basemap street names and labels below this layer - dodge the badge instead of
    // rendering half-covered under it (user saw a camera sitting on a street name). The POI
    // stack places before this layer, so it is unaffected by the claim and still wins on top.
    if (style.getImage(FLOCK_IMG) == null) style.addImage(FLOCK_IMG, alprCameraBitmap())
    if (style.getSource(FLOCK_SRC) == null) {
        style.addSource(GeoJsonSource(FLOCK_SRC, GeoJsonOptions().withMaxZoom(12)))
        val flockSize = Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(11f, 0.55f),
            Expression.stop(14f, 0.7f),
            Expression.stop(17f, 1.0f),
            Expression.stop(19f, 1.35f),
        )
        val flockLayer =
            SymbolLayer(FLOCK_LAYER, FLOCK_SRC).apply {
                // Must match the VM's FLOCK_MIN_ZOOM fetch gate: clamped at 13.5 this layer sat on
                // fetched cameras without drawing them (z13-13.5 was a dead band, and route-overview
                // zoom showed nothing at all - the half-done state vela-dpad caught, issue #131).
                setMinZoom(11f)
                setProperties(
                    PropertyFactory.iconImage(FLOCK_IMG),
                    PropertyFactory.iconSize(flockSize),
                    PropertyFactory.iconAllowOverlap(true), // never yields itself...
                    PropertyFactory.iconIgnorePlacement(false), // ...and later symbols (street names) dodge it
                    PropertyFactory.iconPadding(2f),
                )
            }
        when {
            style.getLayer(AMBIENT_LAYER) != null -> style.addLayerBelow(flockLayer, AMBIENT_LAYER)
            style.getLayer(CONTROLS_CLAIM_LAYER) != null -> style.addLayerBelow(flockLayer, CONTROLS_CLAIM_LAYER)
            else -> style.addLayer(flockLayer)
        }
    }
    // Canonical GTFS transit stops (Transitous). One icon per station (bays dedupe in the VM);
    // replaces the OSM basemap bus icons wherever this layer has coverage (poi_transit filter flips
    // in applyData). Drawn above the ambient POIs (and above the flock layer, which yields to both).
    if (style.getImage(TRANSIT_STOP_IMG) == null) style.addImage(TRANSIT_STOP_IMG, transitStopBitmap())
    if (style.getSource(TRANSIT_STOPS_SRC) == null) {
        style.addSource(GeoJsonSource(TRANSIT_STOPS_SRC, GeoJsonOptions().withMaxZoom(12)))
        // Deliberately a touch smaller than the POI markers (stops are dense), but not tiny -
        // the first cut (0.62-1.2) read too small on device (user 2026-07-13).
        val stopSize = Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(15f, 0.78f),
            Expression.stop(17f, 1.1f),
            Expression.stop(19f, 1.4f),
        )
        style.addLayer(
            SymbolLayer(TRANSIT_STOPS_LAYER, TRANSIT_STOPS_SRC).apply {
                setMinZoom(15f)
                setProperties(
                    PropertyFactory.iconImage(TRANSIT_STOP_IMG),
                    PropertyFactory.iconSize(stopSize),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPadding(2f),
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(10.5f),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                    PropertyFactory.textColor("#7f8ba0"),
                    PropertyFactory.textHaloColor("#0e1626"),
                    PropertyFactory.textHaloWidth(1.1f),
                    // Google-style: bare badges from z15, NAMES only from z17 (user 2026-07-13) -
                    // a stop every block meant a wall of grey text at street zoom. Opacity step,
                    // not a second layer: the label still participates in collision (textOptional
                    // keeps the icon when a name can't fit), it's just invisible until close.
                    PropertyFactory.textOpacity(
                        Expression.step(Expression.zoom(), Expression.literal(0f), Expression.stop(17f, 1f)),
                    ),
                )
            },
        )
    }
    if (style.getSource(ACCURACY_SRC) == null) {
        style.addSource(GeoJsonSource(ACCURACY_SRC, GeoJsonOptions().withMaxZoom(14)))
        // The accuracy halo: a translucent disc drawn at the fix's REAL uncertainty radius, so an
        // approximate-only permission (or a weak network fix) reads as "somewhere in this
        // blob" instead of a falsely-precise dot. A polygon in meters, so it scales with zoom.
        style.addLayer(
            FillLayer(ACCURACY_LAYER, ACCURACY_SRC).withProperties(
                PropertyFactory.fillColor("#4285F4"),
                // 0.22 + a real edge line: 0.12-0.15 vanished into the dark basemap's own blue.
                PropertyFactory.fillOpacity(0.22f),
            ),
        )
        style.addLayer(
            LineLayer(ACCURACY_LAYER + "-edge", ACCURACY_SRC).withProperties(
                PropertyFactory.lineColor("#4285F4"),
                PropertyFactory.lineOpacity(0.8f),
                PropertyFactory.lineWidth(1.5f),
            ),
        )
    }
    if (style.getSource(ME_SRC) == null) {
        style.addSource(GeoJsonSource(ME_SRC, GeoJsonOptions().withMaxZoom(12)))
        // Heading beam first so it sits BENEATH the dot (Google order): the
        // translucent cone fans out from under the dot in the facing direction.
        style.addLayer(
            SymbolLayer(ME_ARROW_LAYER, ME_SRC).withProperties(
                PropertyFactory.iconImage(ME_ARROW_IMG),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        // The location dot on top — Google's location blue with a white ring.
        style.addLayer(
            CircleLayer(ME_LAYER, ME_SRC).withProperties(
                PropertyFactory.circleColor("#4285F4"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    // Highlight dot for the step being previewed from the directions list.
    if (style.getSource(PREVIEW_SRC) == null) {
        style.addSource(GeoJsonSource(PREVIEW_SRC))
        style.addLayer(
            CircleLayer(PREVIEW_LAYER, PREVIEW_SRC).withProperties(
                PropertyFactory.circleColor("#1F6FEB"),
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }
}

/**
 * Subtle terrain relief like Google Maps, from the keyless open **terrarium** DEM
 * (AWS Open Data — no key; native fetch, so no CORS concern). Inserted just under
 * the road layers so roads + labels stay crisp on top, and capped at z16 so it's
 * terrain context for the overview/regional view and gone at street level. The
 * per-theme colours/strength are set in [applyLight]/[applyDark]. Verified in a
 * MapLibre GL JS harness before shipping (same render engine as MapLibre Native).
 */
private fun ensureHillshade(style: Style) {
    if (style.getSource(DEM_SRC) == null) {
        val tiles = TileSet("2.2.0", TERRARIUM_TILES)
        tiles.encoding = "terrarium" // else MapLibre decodes the elevation as mapbox-RGB → garbage
        style.addSource(RasterDemSource(DEM_SRC, tiles, 256))
    }
    if (style.getLayer(HILLSHADE_LAYER) == null) {
        val hs = HillshadeLayer(HILLSHADE_LAYER, DEM_SRC).withProperties(
            PropertyFactory.hillshadeExaggeration(0.32f),
            PropertyFactory.hillshadeShadowColor("#6b7280"),
            PropertyFactory.hillshadeHighlightColor("#ffffff"),
            PropertyFactory.hillshadeAccentColor("#9aa0a6"),
            // OFF by default (Google doesn't shade terrain unless you ask) - the Topography toggle
            // flips it via ensureTopography. Added hidden so a fresh style starts flat.
            PropertyFactory.visibility(Property.NONE),
        )
        hs.setMaxZoom(16f)
        // Below the first road layer → above water/landuse (so terrain shades the
        // land) but under roads + labels (which stay readable).
        val firstRoad = style.layers.firstOrNull { it.id.startsWith("road") }?.id
        if (firstRoad != null) style.addLayerBelow(hs, firstRoad) else style.addLayer(hs)
    }
}

/** Show/hide the terrain-relief hillshade per the Settings > Map "Topography" toggle. Mirrors
 *  ensureTraffic/ensureTransit: called from applyData on every recomposition, so flipping the pref
 *  re-applies without a style reload. The DEM raster + layer already exist (ensureHillshade); this
 *  only flips visibility, so turning it OFF costs nothing and stops the DEM tiles fetching. */
private fun ensureTopography(style: Style, on: Boolean) {
    style.getLayer(HILLSHADE_LAYER)?.setProperties(
        PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE),
    )
}

/** Toggle Google's live-traffic raster overlay. Inserted below the route line +
 *  labels so they stay on top; keyless public tiles, removed cleanly when off. */
private fun ensureTraffic(style: Style, on: Boolean) {
    val present = style.getLayer(TRAFFIC_LAYER) != null
    if (on && !present) {
        if (style.getSource(TRAFFIC_SRC) == null) {
            style.addSource(RasterSource(TRAFFIC_SRC, TileSet("2.2.0", TRAFFIC_TILES), 256))
        }
        val layer = RasterLayer(TRAFFIC_LAYER, TRAFFIC_SRC).withProperties(
            // Subdue it: it's a browse-only overlay now (nav uses the per-segment route
            // line), and Google's baked tiles paint free-flow green everywhere — at
            // full opacity that buries the basemap and reads as noise. ~0.6 keeps the
            // red/amber congestion legible while the green recedes.
            PropertyFactory.rasterOpacity(0.6f),
        )
        // ALWAYS below the first symbol layer, so POI icons + labels stay on top and
        // the traffic tiles never render over them (the earlier "above the route line"
        // placement pushed it over POIs). With satellite on, anchor above the imagery
        // instead - the raster otherwise buries the traffic tiles entirely.
        val satTop = style.getLayer(SAT_ROADS_LAYER) ?: style.getLayer(SAT_LAYER)
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        when {
            satTop != null -> style.addLayerAbove(layer, satTop.id)
            firstSymbol != null -> style.addLayerBelow(layer, firstSymbol)
            else -> style.addLayer(layer)
        }
    } else if (!on && present) {
        style.removeLayer(TRAFFIC_LAYER)
        style.getSource(TRAFFIC_SRC)?.let { runCatching { style.removeSource(it) } }
    }
}

/** Highlight rail lines (heavy rail + subway/light-rail/tram) drawn from the basemap's own
 *  `transportation` source-layer (OpenMapTiles `class` = rail / transit), Google-transit-layer style.
 *  No new data or network — just a coloured LineLayer over the existing tiles, inserted below the first
 *  symbol layer so station/road labels stay on top. No-op if the basemap isn't OpenMapTiles (e.g. a
 *  MapTiler variant whose source id differs, or the demo style); removed cleanly when off. */
private fun ensureTransit(style: Style, on: Boolean) {
    val present = style.getLayer(TRANSIT_LAYER) != null
    if (on && !present) {
        if (style.getSource("openmaptiles") == null) return
        val layer = LineLayer(TRANSIT_LAYER, "openmaptiles").apply {
            setSourceLayer("transportation")
            // class = "rail" (heavy rail) or "transit" (subway / light_rail / tram / monorail).
            setFilter(
                Expression.any(
                    Expression.eq(Expression.get("class"), Expression.literal("rail")),
                    Expression.eq(Expression.get("class"), Expression.literal("transit")),
                ),
            )
            setProperties(
                // Subways/trams a brighter teal, heavy rail a purple — both read on light AND dark maps.
                PropertyFactory.lineColor(
                    Expression.match(
                        Expression.get("class"),
                        Expression.literal("transit"), Expression.literal(TRANSIT_SUBWAY),
                        Expression.literal(TRANSIT_RAIL),
                    ),
                ),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(8, 1.0f), Expression.stop(13, 2.4f), Expression.stop(16, 4.2f),
                    ),
                ),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        }
        // Above the satellite raster when imagery is on (the raster otherwise buries this -
        // same anchor bug the building overlay had); below the labels either way.
        val satTop = style.getLayer(SAT_ROADS_LAYER) ?: style.getLayer(SAT_LAYER)
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        when {
            satTop != null -> style.addLayerAbove(layer, satTop.id)
            firstSymbol != null -> style.addLayerBelow(layer, firstSymbol)
            else -> style.addLayer(layer)
        }
    } else if (!on && present) {
        runCatching { style.removeLayer(TRANSIT_LAYER) }
    }
}

private const val SAT_SRC = "vela-sat-src"
private const val SAT_LAYER = "vela-sat"
private const val SAT_ROADS_LAYER = "vela-sat-roads"
// Esri World Imagery, the openly usable satellite tile service (attribution shown by the map UI
// while the layer is on). z/y/x order; 19 is the safe global max.
private const val SAT_TILES = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

/** Satellite imagery under the SYMBOL stack: the raster covers the vector fills and road lines,
 *  but every label, POI, route line and Vela layer keeps drawing on top (hybrid look). Same
 *  add/remove idempotence as [ensureTransit]; removing restores the vector map untouched. */
private fun ensureSatellite(style: Style, on: Boolean) {
    val present = style.getLayer(SAT_LAYER) != null
    if (on && !present) {
        if (style.getSource(SAT_SRC) == null) {
            style.addSource(RasterSource(SAT_SRC, TileSet("2.2.0", SAT_TILES).apply { maxZoom = 19f }, 256))
        }
        val layer = RasterLayer(SAT_LAYER, SAT_SRC).withProperties(
            // Dim + desaturate a touch so the white-halo labels stay readable over bright
            // roofs/concrete (Google's hybrid does the same; full-brightness imagery drowned
            // street names, user 2026-07-13).
            PropertyFactory.rasterBrightnessMax(0.80f),
            PropertyFactory.rasterSaturation(-0.1f),
        )
        // Anchor ABOVE the building stack: that's where the basemap's geometry ends and its
        // labels begin. "Below the first symbol layer" was wrong - Liberty interleaves a low
        // symbol layer beneath the road/building fills, so the raster sank under half the
        // geometry and blue footprints + roads drew on top of the photo (user 2026-07-13).
        val geomTop = style.getLayer("building-3d") ?: style.getLayer("building")
        when {
            geomTop != null -> style.addLayerAbove(layer, geomTop.id)
            else -> style.layers.lastOrNull { it !is SymbolLayer }?.let { style.addLayerAbove(layer, it.id) }
                ?: style.addLayer(layer)
        }
        // Ghost roads over the photo (Google hybrid does this): a single translucent white line
        // layer from the basemap's transportation source, above the raster, below the labels -
        // without it the road network disappears into tree cover and the map stops being
        // navigable as a map (user 2026-07-13).
        // Re-seat the overlay lines above the fresh raster: they were anchored for the vector
        // map and the raster would bury them (transit lines vanished under imagery, user
        // 2026-07-13). Removing here lets this same pass's ensureTraffic/ensureTransit re-add
        // them with the satellite-aware anchor.
        runCatching { style.removeLayer(TRANSIT_LAYER) }
        runCatching { style.removeLayer(TRAFFIC_LAYER) }
        if (style.getLayer(SAT_ROADS_LAYER) == null && style.getSource("openmaptiles") != null) {
            val roads = LineLayer(SAT_ROADS_LAYER, "openmaptiles").apply {
                setSourceLayer("transportation")
                setProperties(
                    // Freeways read YELLOW like the Google app's hybrid layer; everything else
                    // stays the translucent white (user 2026-07-13).
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("class"),
                            Expression.literal("motorway"), Expression.literal("#F7DD7C"),
                            Expression.literal("trunk"), Expression.literal("#F7DD7C"),
                            Expression.literal("#FFFFFF"),
                        ),
                    ),
                    PropertyFactory.lineOpacity(
                        Expression.match(
                            Expression.get("class"),
                            Expression.literal("motorway"), Expression.literal(0.55f),
                            Expression.literal("trunk"), Expression.literal(0.50f),
                            Expression.literal(0.38f),
                        ),
                    ),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(
                            Expression.exponential(1.5f), Expression.zoom(),
                            Expression.stop(8, Expression.match(
                                Expression.get("class"),
                                Expression.literal("motorway"), Expression.literal(1.6f),
                                Expression.literal("trunk"), Expression.literal(1.4f),
                                Expression.literal(0.6f),
                            )),
                            Expression.stop(18, Expression.match(
                                Expression.get("class"),
                                Expression.literal("motorway"), Expression.literal(14f),
                                Expression.literal("trunk"), Expression.literal(12f),
                                Expression.literal("primary"), Expression.literal(10f),
                                Expression.literal(7f),
                            )),
                        ),
                    ),
                )
            }
            style.addLayerAbove(roads, SAT_LAYER)
        }
    } else if (!on && present) {
        runCatching { style.removeLayer(SAT_LAYER) }
        runCatching { style.removeLayer(SAT_ROADS_LAYER) }
    }
}

/**
 * Satellite label treatment: EVERY text on the map goes white with a robust black halo -
 * street names already got this look, and over imagery it's the only combination that reads
 * on both dark tree cover and bright rooftops (Google hybrid does the same for POIs, cities,
 * water, everything). Runs AFTER applyMapTheme/applyToLiberty so it overrides the category
 * tints; the satellite toggle reloads the style (styleKey carries sat=), so switching back
 * restores the normal palette from a clean slate. Shield layers are skipped - their text
 * sits INSIDE a shield icon and white-on-white would erase the route number.
 */
private fun applySatelliteLabels(style: Style) {
    style.layers.forEach { layer ->
        if (layer !is SymbolLayer) return@forEach
        if (layer.id.contains("shield")) return@forEach
        layer.setProperties(
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#000000"),
            PropertyFactory.textHaloWidth(1.8f),
        )
    }
}

/** Route shields (the I-5 / US-2 / SR badges and their international `road_N` cousins) render
 *  at Liberty's defaults: icon-size 1, 10pt regular text - small and thin next to Google's.
 *  Scale badge + text together (text 10->12.5 matches icon 1->1.25, so the ref stays centered)
 *  and bolden, like Google. The generic non-US shield layer gets the same bump, so it reads
 *  everywhere, not just on US networks. Runs after the theme pass on every style (re)load. */
private fun emphasizeShields(style: Style) {
    listOf("highway-shield-non-us", "highway-shield-us-interstate", "road_shield_us").forEach { id ->
        style.getLayer(id)?.setProperties(
            PropertyFactory.iconSize(1.25f),
            PropertyFactory.textSize(12.5f),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        )
    }
}

/**
 * Recolour the OpenFreeMap (OpenMapTiles) style for a cleaner look and a proper
 * dark theme that follows the system. We reload the style when the theme flips
 * (see styleKey), so each pass starts from Liberty's defaults — no need to undo.
 * No-ops on non-OpenMapTiles styles (e.g. the MapLibre demo basemap). Keyless.
 */
private fun applyMapTheme(style: Style, dark: Boolean) {
    if (style.getSource("openmaptiles") == null) return
    // Two compiled colour sets, picked in Settings -> Appearance (MapColors): "modern" is the
    // Google-app pixel-sampled palette, "classic" the archived pre-sample look (docs/MAP-STYLE.md).
    val classic = app.vela.ui.MapColors.classic()
    when {
        dark && classic -> applyClassicDark(style)
        dark -> applyDark(style)
        classic -> applyClassicLight(style)
        else -> applyLight(style)
    }
    PoiIcons.applyToLiberty(style, dark)
    // Ambient Google-POI labels match the ICON's category colour, Google-style — saturated in light,
    // pastel tints in dark (see PoiIcons.labelColor). Search-result pins stay plain (Google does too).
    (style.getLayer(AMBIENT_LAYER) as? SymbolLayer)?.setProperties(
        PropertyFactory.textColor(PoiIcons.ambientLabelColor(dark)),
        PropertyFactory.textHaloColor(if (dark) "#11161C" else "#FFFFFF"),
    )
    // Canonical GTFS stop names take the TRANSIT category colour per theme - blue in light,
    // its pastel tint in dark, the same grammar every POI label follows. The creation-time
    // colours in ensureLayers were hardcoded for dark (no theme there) and read as grey with
    // a navy halo on the light map (issue #71 follow-up, 2026-07-14).
    (style.getLayer(TRANSIT_STOPS_LAYER) as? SymbolLayer)?.setProperties(
        PropertyFactory.textColor(PoiIcons.labelColorFor("transit", dark)),
        PropertyFactory.textHaloColor(if (dark) "#11161C" else "#FFFFFF"),
    )
    // Search-result labels stay NEUTRAL ink - Google doesn't category-tint result labels the
    // way it tints ambient POI labels (the red pin is the result signal, not the text colour).
    (style.getLayer(MARKERS_LAYER) as? SymbolLayer)?.setProperties(
        PropertyFactory.textColor(if (dark) "#E8EAED" else "#3C4043"),
        PropertyFactory.textHaloColor(if (dark) "#11161C" else "#FFFFFF"),
    )
    // The mini-dot tier wears a land-coloured ring so dots read as crisp beads per theme.
    (style.getLayer(AMBIENT_DOT_LAYER) as? CircleLayer)?.setProperties(
        PropertyFactory.circleStrokeColor(if (dark) "#162640" else "#f8f7f7"),
    )
    // Hide Liberty's dashed clutter that Google doesn't draw: footpaths/sidewalks,
    // park outlines, the stepped admin/city/county BOUNDARY lines, and the railroad
    // cross-tie hatching (the solid rail line stays). All read as weird stray dashes.
    listOf(
        "road_path_pedestrian", "bridge_path_pedestrian", "bridge_path_pedestrian_casing", "tunnel_path_pedestrian",
        "park_outline",
        "boundary_2", "boundary_3", "boundary_disputed",
        "road_major_rail_hatching", "road_transit_rail_hatching",
        "bridge_major_rail_hatching", "bridge_transit_rail_hatching",
        "tunnel_major_rail_hatching", "tunnel_transit_rail_hatching",
    ).forEach { style.getLayer(it)?.setProperties(PropertyFactory.visibility(Property.NONE)) }
}

internal fun applyLight(style: Style) {
    // Road-name labels get a wide white halo so they stay readable over the dotted walk
    // line / route line beneath them (dark path does the same; see the symbol pass there),
    // and the BOLD font stack - Google boldens street names on the map (user 2026-07-11).
    listOf("highway-name-path", "highway-name-minor", "highway-name-major").forEach {
        style.getLayer(it)?.setProperties(
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textHaloColor("#ffffff"),
            PropertyFactory.textHaloWidth(1.9f),
        )
    }
    // Google-Maps light palette: clean white road fills on a light-grey land, with
    // every casing faded DOWN the hierarchy until minor-road casing == the land, so
    // streets are crisp white lines with NO outline (the outlines were exactly what
    // made it look un-Google). Soft-yellow motorways, neutralised landuse (no tan
    // residential/commercial blobs), subtle buildings. Tuned live in a MapLibre GL
    // JS harness against Google for reference.
    // PIXEL-SAMPLED from Google Maps (the app) on the P9 in light mode, 2026-07-11:
    // land #f8f7f7, roads ONE blue-grey fill #aab9c9 (streets AND arterials; no casing),
    // driveways/service #9bacbc, motorway #8aa4c0, buildings #e8e9ed (outline #d6d9e6),
    // vegetation #d3f8e1, water #90daee, commercial cream #fdf9ef, trails #7fcdb0.
    val land = "#f8f7f7"
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor(land))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#90daee")) // Google Maps light water (verbatim)
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#d3f8e1"), PropertyFactory.fillOpacity(1f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#d3f8e1"), PropertyFactory.fillOpacity(1f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#d3f8e1"), PropertyFactory.fillOpacity(1f))
    // Buildings (OSM footprints, already in the Liberty tiles — no key/data needed).
    // The old #e2e3e6 was a hair off the #e8eaed land, so they were ~invisible; give
    // them a touch more grey + a subtle outline so they read like Google's at z15+.
    style.getLayer("building")?.setProperties(
        PropertyFactory.fillColor("#e8e9ed"),
        PropertyFactory.fillOutlineColor("#d6d9e6"),
    )
    // Show footprints from neighbourhood zoom (Liberty hid them until ~z16-17, so
    // residential houses only appeared when zoomed way in; Google shows them earlier).
    // The bundled `building` FILL layer is minzoom 13 / maxzoom 14, and MapLibre `maxzoom`
    // is EXCLUSIVE — so `setMinZoom(14f)` alone collapsed its range to empty (14 ≤ z < 14)
    // and the crisp flat footprints NEVER painted (only the faint building-3d extrusion
    // showed → the "sparse residential" look). Re-open the top with setMaxZoom so the flat
    // fill+outline draws from z14 up (overzoomed z14 tiles fill z15+).
    style.getLayer("building")?.setMinZoom(14f)
    style.getLayer("building")?.setMaxZoom(24f)
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#e8e9ed"),
        PropertyFactory.fillExtrusionOpacity(1f),
        PropertyFactory.fillExtrusionVerticalGradient(false),
    )
    // Extrusions only once zoomed into a block — the flat fill+outline gives the footprint
    // look at browse zoom, and fill-extrusion is the per-pixel-expensive part on a Pixel 5a.
    style.getLayer("building-3d")?.setMinZoom(16f)
    // Neutralise the tan/yellow landuse fills (residential/commercial/school/…) into
    // the land — Google keeps these flat, not coloured blobs.
    // pitch/track keep their OWN colour (the sports-field accent set beside the vela-pitch
    // twin below) - the neutralise loop covered them and hid the tint (found at a park with
    // ball courts, 2026-07-11).
    val greens = setOf("park", "landcover_grass", "landcover_wood", "landuse_pitch", "landuse_track")
    style.layers.forEach { layer ->
        if (layer is FillLayer && layer.id !in greens &&
            (layer.id.startsWith("landuse") || layer.id.startsWith("landcover"))
        ) {
            layer.setProperties(PropertyFactory.fillColor(land))
        }
    }
    // Liberty fills wetlands with a fern-hatch pattern and pedestrian plazas with a
    // dotted one — Google shows both flat. Clear the pattern so the flat fill shows.
    style.getLayer("vela-wetland")?.setProperties(PropertyFactory.fillColor("#d3f8e1"), PropertyFactory.fillOpacity(1f))
    style.getLayer("vela-plaza")?.setProperties(PropertyFactory.fillColor("#dbe0e8")) // pedestrian/parking surface, sampled
    style.getLayer("vela-commercial")?.setProperties(PropertyFactory.fillColor("#fdf9ef"), PropertyFactory.fillOpacity(1f)) // cream, sampled
    // Sports fields: P9-sampled #a9eac2 (Toomey Field + the tennis centre both read it).
    style.getLayer("vela-pitch")?.setProperties(PropertyFactory.fillColor("#a9eac2"), PropertyFactory.fillOpacity(1f))
    listOf("landuse_pitch", "landuse_track").forEach { // Liberty's own pitch layers sit ABOVE the twin and covered it
        style.getLayer(it)?.setProperties(PropertyFactory.fillColor("#a9eac2"), PropertyFactory.fillOpacity(1f))
    }
    // Institutional campuses (schools/universities) wear a warm pale grey distinct from
    // the city land in the Google app (#f0eded, P9-sampled at UC Davis 2026-07-11).
    style.getLayer("landuse_school")?.setProperties(PropertyFactory.fillColor("#f0eded"), PropertyFactory.fillOpacity(1f))
    style.getLayer("vela-trails")?.setProperties(PropertyFactory.lineColor("#7fcdb0")) // sampled
    style.getLayer("vela-bikeroutes")?.setProperties(PropertyFactory.lineColor("#007b8b")) // Google's bike teal (light)
    // Roads: the app uses ONE blue-grey fill for streets and arterials alike, a deeper
    // blue for motorways, a darker tier for driveways, and NO visible casings (they
    // fade into the land, same rule as dark). All sampled.
    listOf("road_motorway", "road_motorway_link", "bridge_motorway", "bridge_motorway_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#8aa4c0"))
    }
    listOf("road_trunk_primary", "bridge_trunk_primary",
        "road_secondary_tertiary", "bridge_secondary_tertiary",
        "road_minor", "road_link", "bridge_street", "bridge_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#aab9c9"))
    }
    listOf("road_service_track", "bridge_service_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#9bacbc"))
    }
    listOf("road_motorway_casing", "road_motorway_link_casing", "bridge_motorway_casing", "bridge_motorway_link_casing",
        "road_trunk_primary_casing", "bridge_trunk_primary_casing",
        "road_secondary_tertiary_casing", "bridge_secondary_tertiary_casing",
        "road_minor_casing", "road_link_casing", "road_service_track_casing",
        "bridge_street_casing", "bridge_link_casing", "bridge_service_track_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor(land))
    }
    // Terrain relief: a soft warm-grey shadow, subtle so hills read as depth, not dirt.
    style.getLayer(HILLSHADE_LAYER)?.setProperties(
        PropertyFactory.hillshadeExaggeration(0.32f),
        PropertyFactory.hillshadeShadowColor("#6b7280"),
        PropertyFactory.hillshadeHighlightColor("#ffffff"),
        PropertyFactory.hillshadeAccentColor("#9aa0a6"),
    )
}

/** Google-Maps-dark-ish palette applied over the OpenMapTiles layers. */
internal fun applyDark(style: Style) {
    // Every dark value below is PIXEL-SAMPLED from Google Maps (the app) on the attached
    // Pixel 9, 2026-07-11: land #162640, water #000d2a, vegetation #0d3847, buildings
    // #1c3b69 (alt shade #2e3d6d), minor roads #3d5a77, arterials/motorway #476789.
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#162640"))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#000d2a"))
    style.getLayer("waterway_river")?.setProperties(PropertyFactory.lineColor("#000d2a"))
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#0d3847"), PropertyFactory.fillOpacity(1f)) // Google-app dark vegetation: a TEAL green (matched to the user's Google dark screenshot 2026-07-11), clearly lighter than the land
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#0d3847"), PropertyFactory.fillOpacity(0.9f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#0d3847"), PropertyFactory.fillOpacity(0.95f))
    listOf("road_minor", "road_secondary_tertiary", "road_link",
        "bridge_street", "bridge_secondary_tertiary", "bridge_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#3d5a77"))
    }
    // Alleys/driveways/service roads are a DARKER tier than residential streets in the
    // Google app (sampled #2a4056 beside #3d5a77 streets, P9 side-by-side 2026-07-11).
    listOf("road_service_track", "bridge_service_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#2a4056"))
    }
    listOf("road_trunk_primary", "bridge_trunk_primary").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#476789"))
    }
    listOf("road_motorway", "road_motorway_link", "bridge_motorway", "bridge_motorway_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#476789"))
    }
    // Casings blend into the night land so roads are clean (no hard outline), like
    // Google dark — the lighter road fills still read against the dark land.
    listOf("road_motorway_casing", "road_motorway_link_casing", "road_trunk_primary_casing",
        "road_secondary_tertiary_casing", "road_minor_casing", "road_link_casing", "road_service_track_casing",
        "bridge_motorway_casing", "bridge_trunk_primary_casing", "bridge_secondary_tertiary_casing",
        "bridge_street_casing", "bridge_link_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#162640"))
    }
    // Buildings a touch lighter than the #242f3e land + a lit edge, so they read in
    // dark mode instead of melting into the ground (same reasoning as the light path).
    style.getLayer("building")?.setProperties(
        PropertyFactory.fillColor("#1c3b69"),
        PropertyFactory.fillOutlineColor("#2e3d6d"),
    )
    style.getLayer("building")?.setMinZoom(14f) // houses from neighbourhood zoom (see light path)
    style.getLayer("building")?.setMaxZoom(24f) // re-open the maxzoom:14 clamp (see light path — was collapsing the flat fill to empty)
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#1c3b69"),
        PropertyFactory.fillExtrusionOpacity(1f),
        PropertyFactory.fillExtrusionVerticalGradient(false),
    )
    style.getLayer("building-3d")?.setMinZoom(16f) // extrusions only high-zoom (Pixel 5a perf)
    // Greens we keep as-is; every OTHER landuse/landcover fill (commercial, school,
    // retail, industrial, sand, …) must go dark too, or it stays a jarring cream
    // patch in dark mode.
    // pitch/track keep their OWN colour (the sports-field accent set beside the vela-pitch
    // twin below) - the neutralise loop covered them and hid the tint (found at a park with
    // ball courts, 2026-07-11).
    val greens = setOf("park", "landcover_grass", "landcover_wood", "landuse_pitch", "landuse_track")
    style.layers.forEach { layer ->
        when {
            layer is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor("#c3cad6"),
                PropertyFactory.textHaloColor("#1a2230"),
                // Road names get a WIDER halo than the 1.1 blanket: the dotted walk line and
                // the route line run right under them, and at 1.1 the text drowned in the dots
                // (user 2026-07-09). The halo is the "underlay tint" - Google does the same.
                PropertyFactory.textHaloWidth(if (layer.id.startsWith("highway-name")) 1.9f else 1.1f),
            )
            layer is FillLayer && layer.id !in greens &&
                (layer.id.startsWith("landuse") || layer.id.startsWith("landcover")) ->
                layer.setProperties(PropertyFactory.fillColor("#2a3546"), PropertyFactory.fillOpacity(0.5f))
        }
    }
    // Street names in BOLD (Google boldens them; user 2026-07-11) - a dedicated pass AFTER the
    // blanket loop so only the road-name layers get the Bold stack, not every label.
    listOf("highway-name-path", "highway-name-minor", "highway-name-major").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.textFont(arrayOf("Noto Sans Bold")))
    }
    // Drop the wetland fern-hatch + pedestrian-plaza patterns (flat, like Google dark).
    style.getLayer("vela-wetland")?.setProperties(PropertyFactory.fillColor("#0d3847"), PropertyFactory.fillOpacity(0.9f))
    style.getLayer("vela-plaza")?.setProperties(PropertyFactory.fillColor("#2a3546"))
    style.getLayer("vela-commercial")?.setProperties(PropertyFactory.fillColor("#1c2638"), PropertyFactory.fillOpacity(1f)) // = other-landuse dark, twin exists for light
    style.getLayer("vela-pitch")?.setProperties(PropertyFactory.fillColor("#0d4956"), PropertyFactory.fillOpacity(1f)) // sports fields, sampled
    listOf("landuse_pitch", "landuse_track").forEach { // Liberty's own pitch layers sit ABOVE the twin and covered it
        style.getLayer(it)?.setProperties(PropertyFactory.fillColor("#0d4956"), PropertyFactory.fillOpacity(1f))
    }
    style.getLayer("vela-trails")?.setProperties(PropertyFactory.lineColor("#167055")) // park foot trails, sampled
    style.getLayer("vela-bikeroutes")?.setProperties(PropertyFactory.lineColor("#1f8f9c")) // bike teal, lightened for the dark land
    // Terrain relief for the night palette: deep shadows + a cool blue-grey
    // highlight so ridges catch a little moonlight (a touch stronger than light).
    style.getLayer(HILLSHADE_LAYER)?.setProperties(
        PropertyFactory.hillshadeExaggeration(0.45f),
        PropertyFactory.hillshadeShadowColor("#0a1018"),
        PropertyFactory.hillshadeHighlightColor("#3a4a68"),
        PropertyFactory.hillshadeAccentColor("#0a1018"),
    )
}

/**
 * CLASSIC light: the archived pre-pixel-sample palette (commit 071c6c3, kept in
 * docs/MAP-STYLE.md) - clean white road fills with faded casings, soft-yellow
 * motorways, true greens, warm-grey land. Selectable in Settings -> Appearance;
 * the twin layers that arrived after the archive (trails/bike/pitch/commercial)
 * get harmonious colours so nothing renders unstyled.
 */
internal fun applyClassicLight(style: Style) {
    listOf("highway-name-path", "highway-name-minor", "highway-name-major").forEach {
        style.getLayer(it)?.setProperties(
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textHaloColor("#ffffff"),
            PropertyFactory.textHaloWidth(1.9f),
        )
    }
    val land = "#f2f1ee"
    val white = "#ffffff"
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor(land))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#90daee"))
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#cfeccd"), PropertyFactory.fillOpacity(1f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#d3f8e2"), PropertyFactory.fillOpacity(1f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#c9f2da"), PropertyFactory.fillOpacity(1f))
    style.getLayer("building")?.setProperties(
        PropertyFactory.fillColor("#dde1e7"),
        PropertyFactory.fillOutlineColor("#c4c9d1"),
    )
    style.getLayer("building")?.setMinZoom(14f)
    style.getLayer("building")?.setMaxZoom(24f)
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#dde1e7"),
        PropertyFactory.fillExtrusionOpacity(0.9f),
    )
    style.getLayer("building-3d")?.setMinZoom(16f)
    // Classic neutralises every non-green landuse into the land (no campus/commercial tints).
    val greens = setOf("park", "landcover_grass", "landcover_wood")
    style.layers.forEach { layer ->
        if (layer is FillLayer && layer.id !in greens &&
            (layer.id.startsWith("landuse") || layer.id.startsWith("landcover"))
        ) {
            layer.setProperties(PropertyFactory.fillColor(land))
        }
    }
    style.getLayer("vela-wetland")?.setProperties(PropertyFactory.fillColor("#cdeff0"), PropertyFactory.fillOpacity(1f))
    style.getLayer("vela-plaza")?.setProperties(PropertyFactory.fillColor("#ededed"))
    // Post-archive twins, coloured to sit quietly in the classic look: commercial/pitch blend
    // into the land (classic had no tint for them), trails keep green, bike paths keep teal.
    style.getLayer("vela-commercial")?.setProperties(PropertyFactory.fillColor(land), PropertyFactory.fillOpacity(1f))
    style.getLayer("vela-pitch")?.setProperties(PropertyFactory.fillColor("#d3f8e2"), PropertyFactory.fillOpacity(1f))
    listOf("landuse_pitch", "landuse_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.fillColor("#d3f8e2"), PropertyFactory.fillOpacity(1f))
    }
    style.getLayer("vela-trails")?.setProperties(PropertyFactory.lineColor("#7fcdb0"))
    style.getLayer("vela-bikeroutes")?.setProperties(PropertyFactory.lineColor("#007b8b"))
    listOf("road_motorway", "road_motorway_link", "bridge_motorway", "bridge_motorway_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#f9d27a"))
    }
    listOf("road_motorway_casing", "road_motorway_link_casing", "bridge_motorway_casing", "bridge_motorway_link_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#f0b85a"))
    }
    listOf("road_trunk_primary", "bridge_trunk_primary").forEach { style.getLayer(it)?.setProperties(PropertyFactory.lineColor(white)) }
    listOf("road_trunk_primary_casing", "bridge_trunk_primary_casing").forEach { style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#dadde2")) }
    listOf("road_secondary_tertiary", "bridge_secondary_tertiary").forEach { style.getLayer(it)?.setProperties(PropertyFactory.lineColor(white)) }
    listOf("road_secondary_tertiary_casing", "bridge_secondary_tertiary_casing").forEach { style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#e4e6ea")) }
    listOf("road_minor", "road_link", "road_service_track", "bridge_street", "bridge_link", "bridge_service_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor(white))
    }
    listOf("road_minor_casing", "road_link_casing", "road_service_track_casing", "bridge_street_casing", "bridge_link_casing", "bridge_service_track_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor(land))
    }
    style.getLayer(HILLSHADE_LAYER)?.setProperties(
        PropertyFactory.hillshadeExaggeration(0.32f),
        PropertyFactory.hillshadeShadowColor("#6b7280"),
        PropertyFactory.hillshadeHighlightColor("#ffffff"),
        PropertyFactory.hillshadeAccentColor("#9aa0a6"),
    )
}

/** CLASSIC dark: the archived pre-pixel-sample night palette (see applyClassicLight). */
internal fun applyClassicDark(style: Style) {
    // Classic dark = a NEUTRAL charcoal-slate identity, deliberately UNLIKE Modern's Google-navy
    // dark (Modern pixel-samples #162640 land / #1c3b69 buildings / blue roads). The two used to
    // differ, but once Modern was re-sampled to Google's blue, classic's old #242f3e blue-grey read
    // the same (user 2026-07-12: "classic looks bluish like modern, houses too"). So classic goes
    // warm-neutral: slate land, GREY buildings (no blue houses), muted-amber motorways echoing the
    // classic-light yellow, its true greens kept - a clearly separate look from Google's night navy.
    val land = "#2b2f36"     // neutral dark slate, not navy
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor(land))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#2f4a63"))       // steel blue, still reads as water
    style.getLayer("waterway_river")?.setProperties(PropertyFactory.lineColor("#2f4a63"))
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#2c4a34"), PropertyFactory.fillOpacity(1f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#2c4a34"), PropertyFactory.fillOpacity(0.9f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#274330"), PropertyFactory.fillOpacity(0.95f))
    listOf("road_minor", "road_secondary_tertiary", "road_link", "road_service_track",
        "bridge_street", "bridge_secondary_tertiary", "bridge_link", "bridge_service_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#565b64"))          // neutral grey, not blue-grey
    }
    listOf("road_trunk_primary", "bridge_trunk_primary").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#6a707b"))
    }
    listOf("road_motorway", "road_motorway_link", "bridge_motorway", "bridge_motorway_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#9c7f4f"))          // muted amber = classic yellow, dark-adjusted
    }
    listOf("road_motorway_casing", "road_motorway_link_casing", "road_trunk_primary_casing",
        "road_secondary_tertiary_casing", "road_minor_casing", "road_link_casing", "road_service_track_casing",
        "bridge_motorway_casing", "bridge_trunk_primary_casing", "bridge_secondary_tertiary_casing",
        "bridge_street_casing", "bridge_link_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor(land))
    }
    style.getLayer("building")?.setProperties(
        PropertyFactory.fillColor("#383d45"),          // warm neutral grey - kills the "blue houses"
        PropertyFactory.fillOutlineColor("#464c56"),
    )
    style.getLayer("building")?.setMinZoom(14f)
    style.getLayer("building")?.setMaxZoom(24f)
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#383d45"),
        PropertyFactory.fillExtrusionOpacity(0.9f),
    )
    style.getLayer("building-3d")?.setMinZoom(16f)
    val greens = setOf("park", "landcover_grass", "landcover_wood")
    style.layers.forEach { layer ->
        when {
            layer is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor("#cdd0d6"),
                PropertyFactory.textHaloColor("#1e2228"),
                PropertyFactory.textHaloWidth(if (layer.id.startsWith("highway-name")) 1.9f else 1.1f),
            )
            layer is FillLayer && layer.id !in greens &&
                (layer.id.startsWith("landuse") || layer.id.startsWith("landcover")) ->
                layer.setProperties(PropertyFactory.fillColor("#31363f"), PropertyFactory.fillOpacity(0.5f))
        }
    }
    // Street names bold, same rule as every other palette pass.
    listOf("highway-name-path", "highway-name-minor", "highway-name-major").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.textFont(arrayOf("Noto Sans Bold")))
    }
    style.getLayer("vela-wetland")?.setProperties(PropertyFactory.fillColor("#26403c"), PropertyFactory.fillOpacity(0.9f))
    style.getLayer("vela-plaza")?.setProperties(PropertyFactory.fillColor("#31363f"))
    // Post-archive twins (see applyClassicLight): blend or keep their semantic colour.
    style.getLayer("vela-commercial")?.setProperties(PropertyFactory.fillColor("#31363f"), PropertyFactory.fillOpacity(0.5f))
    style.getLayer("vela-pitch")?.setProperties(PropertyFactory.fillColor("#2c4a34"), PropertyFactory.fillOpacity(1f))
    listOf("landuse_pitch", "landuse_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.fillColor("#2c4a34"), PropertyFactory.fillOpacity(1f))
    }
    style.getLayer("vela-trails")?.setProperties(PropertyFactory.lineColor("#167055"))
    style.getLayer("vela-bikeroutes")?.setProperties(PropertyFactory.lineColor("#1f8f9c"))
    style.getLayer(HILLSHADE_LAYER)?.setProperties(
        PropertyFactory.hillshadeExaggeration(0.4f),
        PropertyFactory.hillshadeShadowColor("#0b0d10"),
        PropertyFactory.hillshadeHighlightColor("#4a4d55"),
        PropertyFactory.hillshadeAccentColor("#0b0d10"),
    )
}

/**
 * Tweak the MapTiler Streets style: its light variant colours motorways / major
 * roads orange (OSM-classification style). Recolour them white with a light-grey
 * casing (Google-like); dark Streets is already a calm blue-grey, kept consistent.
 * MapTiler layer ids carry spaces ("Major road", "Highway", …).
 */
private fun tuneMapTiler(style: Style, dark: Boolean) {
    val road = if (dark) "#39414e" else "#ffffff"
    val casing = if (dark) "#2a313c" else "#d6d6d4"
    listOf("Highway", "Major road", "Tunnel", "Bridge").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor(road))
    }
    listOf("Highway outline", "Major road outline", "Tunnel outline", "Bridge outline").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor(casing))
    }
    // Swap MapTiler's POI icons for our Google-style coloured markers (PoiIcons
    // registered the `vela-poi-*` images). MapTiler groups POIs by layer, so a
    // per-layer constant is enough — no class match needed.
    val poiLayers = mapOf(
        "Food" to "food", "Shopping" to "shop", "Healthcare" to "health",
        "Park" to "park", "Transport" to "transit", "Station" to "transit",
        "Education" to "edu", "Culture" to "culture", "Sport" to "sport",
        "Tourism" to "lodging", "Public" to "civic",
    )
    poiLayers.forEach { (layer, group) ->
        style.getLayer(layer)?.setProperties(
            PropertyFactory.iconImage("vela-poi-$group"),
            PropertyFactory.iconSize(0.5f),
        )
    }
}

/** Fraction (0..1) of [polyline]'s length already passed: project [me] onto the
 *  nearest segment, then measure cumulative length to that projection. */
private fun progressAlong(polyline: List<LatLng>, me: LatLng): Float {
    if (polyline.size < 2) return 0f
    val cum = DoubleArray(polyline.size)
    for (i in 1 until polyline.size) cum[i] = cum[i - 1] + polyline[i - 1].distanceTo(polyline[i])
    val total = cum.last()
    if (total <= 0.0) return 0f
    var bestD = Double.MAX_VALUE
    var bestLen = 0.0
    for (i in 1 until polyline.size) {
        val a = polyline[i - 1]
        val b = polyline[i]
        val (proj, t) = projectOnSegment(me, a, b)
        val d = me.distanceTo(proj)
        if (d < bestD) { bestD = d; bestLen = cum[i - 1] + t * a.distanceTo(b) }
    }
    return (bestLen / total).toFloat().coerceIn(0f, 1f)
}

/** Snap [me] onto the nearest point of the nav route for display — the snapped point, that
 *  segment's heading, and the metres-along of the projection — so the puck rides the road
 *  instead of wobbling with raw GPS. Only segments whose along-route range overlaps the window
 *  [[loM]‥[hiM]] are considered, so wherever the route passes near itself (a parallel return
 *  leg, switchback, cloverleaf, a doubled-back street) the global nearest-point can't yank the
 *  puck onto the wrong leg — which reads as the puck "snapping all over / going backwards",
 *  even on a normal road. Pass an infinite window (±∞) for the old global search — the graceful
 *  fallback when nothing in the window is close enough. Returns null (→ show the RAW fix) when
 *  we're not genuinely following the route, so a missed exit / off-road shows reality rather
 *  than gluing the arrow to where it "should" be: either the nearest point is farther than
 *  [maxM] (≈ one road width + GPS error), OR the device heading doesn't match the road's
 *  (you've turned off it). No GPS heading (e.g. stopped) falls back to distance only. */
private fun snapToRouteWindowed(
    me: LatLng,
    gpsBearing: Float?,
    polyline: List<LatLng>,
    cum: DoubleArray,
    loM: Double,
    hiM: Double,
    maxM: Double = 22.0,
): Triple<LatLng, Float, Double>? {
    if (polyline.size < 2 || cum.size < polyline.size) return null
    var bestD = Double.MAX_VALUE
    var bestPoint: LatLng? = null
    var bestA = polyline[0]
    var bestB = polyline[1]
    var bestM = 0.0
    for (i in 1 until polyline.size) {
        if (cum[i] < loM || cum[i - 1] > hiM) continue // segment entirely outside the window
        val a = polyline[i - 1]
        val b = polyline[i]
        val (proj, t) = projectOnSegment(me, a, b)
        val d = me.distanceTo(proj)
        if (d < bestD) {
            bestD = d; bestPoint = proj; bestA = a; bestB = b
            bestM = cum[i - 1] + t * a.distanceTo(b)
        }
    }
    val pt = bestPoint ?: return null
    if (bestD > maxM) return null
    val routeBearing = bearingDeg(bestA, bestB)
    // Heading gate: if the device is clearly NOT going the road's way, don't snap — let
    // the real position show (then the off-route reroute kicks in), Google-style.
    if (gpsBearing != null && angleDelta(gpsBearing, routeBearing) > 55f) return null
    return Triple(pt, routeBearing, bestM)
}

/** Smallest absolute difference between two compass bearings (deg), 0..180. */
private fun angleDelta(a: Float, b: Float): Float = kotlin.math.abs((a - b + 540f) % 360f - 180f)

/** Exponentially ease compass bearing [cur] toward [target] taking the short way around
 *  (so 350°→10° rotates +20°, not −340°). [tau] is the smoothing time-constant (s). */
private fun smoothBearing(cur: Float, target: Float, dt: Float, tau: Float): Float {
    val delta = ((target - cur + 540f) % 360f) - 180f // shortest signed turn, −180..180
    return ((cur + delta * (1f - kotlin.math.exp(-dt / tau))) % 360f + 360f) % 360f
}

/** Motion-model state for the nav puck (Google-style): the displayed position is a
 *  smoothed, **monotonic-forward** progress along the route that the frame ticker glides
 *  toward the latest fix, **dead-reckoned** forward at the known speed between fixes, so the
 *  puck rides forward without the per-fix teleport or the forward/backward jitter of raw
 *  "nearest point". Off-route it falls back to [raw] (honesty — see [snapToRouteWindowed]). */
private class NavPuck {
    var engaged = false           // currently following the route (snapped)
    var progressM = 0.0           // displayed metres along the route (what's drawn)
    var targetM = 0.0             // latest fix's metres along the route (where we're heading)
    var targetAtMs = 0L           // elapsedRealtime() the target was set — for dead reckoning
    var speed = 0.0               // m/s — the KALMAN speed (GPS ⊕ accelerometer), see [kalman]
    val kalman = app.vela.core.location.SpeedKalman() // GPS-fix measurement + accel prediction
    var reckonedM = 0.0           // ∫speed·dt since the last fix — the dead-reckoned advance
    var lastFixLoc: LatLng? = null // identity of the last INGESTED fix — the fix-processing block
                                   // runs in a recomposing scope, and kalman.update/reckonedM=0 are
                                   // NOT idempotent: re-running them on a mere recomposition re-injects
                                   // a stale speed and re-opens the blind reckoning window
    var displayBearing = Float.NaN // smoothed heading actually drawn (NaN = not yet seeded)
    var drawn: LatLng? = null     // last point actually drawn — the camera follows THIS, not the raw fix
    var raw: LatLng? = null       // off-route fallback position
    var rawBearing: Float? = null
    var missCount = 0             // consecutive forward-look-ahead misses (GPS spike / off-route);
                                  // HOLD + dead-reckon through a few, then disengage to re-acquire
    var fwdRejects = 0            // consecutive over-maxStep forward steps — a persistent one is
                                  // the new reality (long fix gap at speed), accept it rather than
                                  // deadlock targetM against a stale plausibility cap
    var speedAtAccept = 0.0       // kalman speed when the last fix was ACCEPTED — sizes the snap
                                  // look-ahead through an outage (the live model decays to ~0
                                  // exactly when the resume fix needs the window big)
}

/** Cumulative along-route distance (m) at each polyline vertex (cum[0] = 0). */
// The polyline currently dotted (walk/bike route) + the zoom its dots were computed at, so a
// zoom change past ~0.2 regenerates them (camera-move listener) and route swaps re-dot.
private var dashDotPoly: List<LatLng> = emptyList()
private var dashDotZoom: Double = -1e9

/** Regenerate the walk/bike dot POINTS for the current zoom: one dot every
 *  [ROUTE_DOT_SPACING_PX] screen pixels' worth of metres along the route. */
private fun regenRouteDots(map: org.maplibre.android.maps.MapLibreMap, style: Style, poly: List<LatLng>) {
    val src = style.getSourceAs<GeoJsonSource>(ROUTE_DOT_SRC) ?: return
    dashDotPoly = poly
    dashDotZoom = map.cameraPosition.zoom
    if (poly.size < 2) {
        src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        return
    }
    val mpp = map.projection.getMetersPerPixelAtLatitude(poly.first().lat)
    val spacingM = mpp * ROUTE_DOT_SPACING_PX
    val cum = cumLengths(poly)
    val total = cum.last()
    // Cap the dot count so a cross-town bike route zoomed all the way in can't explode the
    // source; past the cap the spacing grows, which only ever affects far-off-screen dots.
    val count = (total / spacingM).toInt().coerceAtMost(3000)
    val feats = ArrayList<Feature>(count + 1)
    var d = 0.0
    var i = 0
    while (d <= total && i <= count) {
        val (pt, _) = pointAtMeters(poly, cum, d)
        feats.add(Feature.fromGeometry(Point.fromLngLat(pt.lng, pt.lat)))
        d += spacingM
        i += 1
    }
    src.setGeoJson(FeatureCollection.fromFeatures(feats))
}

private fun cumLengths(poly: List<LatLng>): DoubleArray {
    val cum = DoubleArray(poly.size)
    for (i in 1 until poly.size) cum[i] = cum[i - 1] + poly[i - 1].distanceTo(poly[i])
    return cum
}

/** First vertex index at or beyond [m] along the line — the ahead-suffix starts here. */
private fun indexAtMeters(cum: DoubleArray, m: Double): Int {
    var i = 1
    while (i < cum.size - 1 && cum[i] < m) i++
    return i
}

/** Point + heading at [meters] along the route. */
private fun pointAtMeters(poly: List<LatLng>, cum: DoubleArray, meters: Double): Pair<LatLng, Float> {
    if (poly.size < 2) return (poly.firstOrNull() ?: LatLng(0.0, 0.0)) to 0f
    val total = cum.last()
    val m = meters.coerceIn(0.0, total)
    var i = 1
    while (i < poly.size - 1 && cum[i] < m) i++
    val a = poly[i - 1]
    val b = poly[i]
    val segLen = cum[i] - cum[i - 1]
    val t = if (segLen <= 0.0) 0.0 else ((m - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
    return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t) to bearingDeg(a, b)
}

/** Push a single point + heading into the location source (the puck/dot reads `bearing`). */
private fun setMeSource(style: Style, p: LatLng, bearing: Float) {
    style.getSourceAs<GeoJsonSource>(ME_SRC)?.setGeoJson(
        Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addNumberProperty("bearing", bearing) },
    )
}

/** A 64-point geodesic circle polygon of [radiusM] meters around [center] - the accuracy halo's
 *  geometry (drawn in real meters, so it grows and shrinks with the zoom like the world does). */
private fun accuracyCircle(center: LatLng, radiusM: Double): org.maplibre.geojson.Polygon {
    val latR = Math.toRadians(center.lat)
    val dLat = radiusM / 111_320.0
    val dLng = radiusM / (111_320.0 * Math.cos(latR)).coerceAtLeast(1.0)
    val pts = (0..64).map { i ->
        val a = 2.0 * Math.PI * i / 64
        Point.fromLngLat(center.lng + dLng * Math.sin(a), center.lat + dLat * Math.cos(a))
    }
    return org.maplibre.geojson.Polygon.fromLngLats(listOf(pts))
}

/** Compass bearing (deg, 0 = N) from [a] to [b]. */
private fun bearingDeg(a: LatLng, b: LatLng): Float {
    val dLng = Math.toRadians(b.lng - a.lng)
    val la = Math.toRadians(a.lat)
    val lb = Math.toRadians(b.lat)
    val y = Math.sin(dLng) * Math.cos(lb)
    val x = Math.cos(la) * Math.sin(lb) - Math.sin(la) * Math.cos(lb) * Math.cos(dLng)
    return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}

/** Closest point on segment a→b to p (equirectangular planar approx — fine over a
 *  nav-step's distances), plus the parametric position t∈[0,1] along the segment. */
private fun projectOnSegment(p: LatLng, a: LatLng, b: LatLng): Pair<LatLng, Double> {
    val k = Math.cos(Math.toRadians((a.lat + b.lat) / 2.0))
    val ax = a.lng * k; val ay = a.lat
    val bx = b.lng * k; val by = b.lat
    val px = p.lng * k; val py = p.lat
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return LatLng(ay + t * dy, (ax + t * dx) / k) to t
}

private val ROUTE_FREEFLOW = android.graphics.Color.parseColor("#1F6FEB")
private val ROUTE_DRIVEN = android.graphics.Color.parseColor("#9AA0A6")
private val TRAFFIC_MODERATE = android.graphics.Color.parseColor("#E8923D") // amber
private val TRAFFIC_HEAVY = android.graphics.Color.parseColor("#D93838")    // red
private val TRAFFIC_SEVERE = android.graphics.Color.parseColor("#A11D1D")   // dark red

private fun trafficLevelColor(level: Int): Int = when {
    level <= 1 -> TRAFFIC_MODERATE
    level == 2 -> TRAFFIC_HEAVY
    else -> TRAFFIC_SEVERE
}

/** Route line as **solid** colour bands over lineProgress (0..1 by length): grey for the
 *  driven part (< [p]); ahead, per-segment live traffic from [spans] (startFrac, endFrac,
 *  level) over a free-flow base — or the overall [routeInt] tint when there are no spans
 *  (walk/bike, or no live data). A `step` expression, so the driven/ahead boundary and
 *  the span edges are HARD — no gradient fade between colours (test-drive feedback). */
private fun routeGradient(
    p: Float,
    routeInt: Int,
    spans: List<Triple<Float, Float, Int>>,
): Expression {
    val freeflow = if (spans.isEmpty()) routeInt else ROUTE_FREEFLOW
    // Colour AT fraction f (half-open: a stop at b colours [b, next)). Driven part is grey
    // STRICTLY BEFORE p (p == 0 preview paints no grey nub), so the cut lands exactly at p.
    fun colorAt(f: Float): Int {
        if (p > 0f && f < p) return ROUTE_DRIVEN
        for ((s, e, lvl) in spans) if (f >= s && f < e) return trafficLevelColor(lvl)
        return freeflow
    }
    // EXACT breakpoints, not 256-sample slop: the driven/ahead cut at p precisely (so the
    // grey/colour boundary sits DEAD under the arrow — the old sampling put it up to
    // route-length/256 m off, which read as a soft "gradient" ahead of the arrow), plus every
    // traffic-span edge. A hard `step` at each — no fade. "We either drove it or we didn't."
    val breaks = sortedSetOf<Float>()
    if (p > 0f) breaks.add(p.coerceIn(0.0001f, 0.9999f))
    for ((s, e, _) in spans) {
        if (s in 0.0001f..0.9999f) breaks.add(s)
        if (e in 0.0001f..0.9999f) breaks.add(e)
    }
    val base = colorAt(0f)
    val stops = ArrayList<Expression.Stop>()
    var prev = base
    for (b in breaks) {
        val c = colorAt(b)
        if (c != prev) { stops.add(Expression.stop(b, Expression.color(c))); prev = c }
    }
    // A `step` line-gradient needs ≥1 stop or MapLibre rejects the whole expression
    // ("line-gradient Expected at least 4 arguments, but found only 2") — which happens on EVERY
    // route with no driven-grey and no traffic spans (any directions preview, and early nav before
    // progress > 0): the line then renders unstyled and the error spams each refresh. Seed a single
    // base-colour stop so a band-less route is a valid solid line.
    if (stops.isEmpty()) stops.add(Expression.stop(0.9999f, Expression.color(base)))
    return Expression.step(Expression.lineProgress(), Expression.color(base), *stops.toTypedArray())
}

private fun applyData(
    map: org.maplibre.android.maps.MapLibreMap,
    style: Style,
    context: android.content.Context,
    dark: Boolean,
    ambientCoversView: Boolean,
    route: List<LatLng>,
    routeColor: String,
    routeDashed: Boolean,
    trafficSpans: List<Triple<Float, Float, Int>>,
    alternates: List<Pair<Int, List<LatLng>>>,
    altColor: String,
    markers: List<MapMarker>,
    ambientPois: List<MapMarker>,
    trafficControls: List<app.vela.core.data.TrafficControl>,
    flockCameras: List<app.vela.core.data.AlprCamera>,
    transitStops: List<app.vela.core.data.transit.Transitous.MapStop>,
    me: LatLng?,
    bearing: Float?,
    meAccuracyM: Float?,
    meStale: Boolean,
    preview: LatLng?,
    routeProgress: Float,
    navMode: Boolean,
    parkingSpot: LatLng? = null,
) {
    // Accuracy halo: shown only for a vague fix (see ACCURACY_HALO_MIN_M) and never during nav,
    // where the puck snaps to the road anyway. Identity-gated like everything else here.
    val wantAcc = if (!navMode && me != null && meAccuracyM != null && meAccuracyM > ACCURACY_HALO_MIN_M) meAccuracyM else null
    if (me !== lastAccuracyLoc || wantAcc != lastAccuracyM) {
        val fc = if (wantAcc == null || me == null) FeatureCollection.fromFeatures(emptyList<Feature>())
        else FeatureCollection.fromFeature(Feature.fromGeometry(accuracyCircle(me, wantAcc.toDouble())))
        style.getSourceAs<GeoJsonSource>(ACCURACY_SRC)?.setGeoJson(fc)
        lastAccuracyLoc = me
        lastAccuracyM = wantAcc
    }
    // The parking pin (identity-gated like the markers below).
    if (!parkingApplied || parkingSpot != lastAppliedParking) {
        val fc = if (parkingSpot == null) FeatureCollection.fromFeatures(emptyList<Feature>())
        else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(parkingSpot.lng, parkingSpot.lat))))
        style.getSourceAs<GeoJsonSource>(PARKING_SRC)?.setGeoJson(fc)
        lastAppliedParking = parkingSpot
        parkingApplied = true
    }
    // Identity-gate the route geometry upload (same pattern as markers/ambient below): applyData
    // runs on EVERY recomposition — during nav that's each fix/speedo tick — and re-tessellating
    // a thousands-of-vertices linestring that hasn't changed burned frame budget exactly while
    // the 60 fps ticker eased the camera.
    if (route !== lastAppliedRouteLine) {
        val routeFc = if (route.size >= 2) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.lng, it.lat) })),
            )
        } else {
            FeatureCollection.fromFeatures(emptyList<Feature>())
        }
        style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeFc)
        // Mid-nav ROUTE SWAP (reroute / faster route): seed the ahead layer with the WHOLE new
        // route immediately — the ticker only repaints it after the puck re-engages and moves a
        // throttle unit, and until then the new geometry showed entirely traversed-grey with the
        // OLD route's blue suffix ghosted on top for a second. Progress on a fresh route ≈ 0, so
        // "everything is ahead" is the correct seed; the ticker takes over from the next engage.
        if (navMode && !routeDashed && route.size >= 2) {
            style.getSourceAs<GeoJsonSource>(ROUTE_AHEAD_SRC)?.setGeoJson(routeFc)
            val seedInt = runCatching { android.graphics.Color.parseColor(routeColor) }.getOrDefault(ROUTE_FREEFLOW)
            style.getLayer(ROUTE_AHEAD_LAYER)?.setProperties(
                PropertyFactory.visibility(Property.VISIBLE),
                PropertyFactory.lineGradient(routeGradient(0f, seedInt, trafficSpans)),
            )
        }
        lastAppliedRouteLine = route
    }
    // Route line, Google-style: the part already DRIVEN greys out behind the vehicle;
    // the part AHEAD shows live traffic PER SEGMENT — a free-flow base with amber/red
    // bands over the congested stretches (from [trafficSpans]) — or, with no live
    // data, a single congestion tint. A line-progress gradient (routeProgress =
    // fraction travelled, 0 when not navigating → nothing greyed).
    val routeInt = runCatching { android.graphics.Color.parseColor(routeColor) }
        .getOrDefault(ROUTE_FREEFLOW)
    // 0 when not navigating (no driven-grey); only floor to a visible sliver once moving.
    val p = if (routeProgress <= 0f) 0f else routeProgress.coerceIn(0.001f, 0.998f)
    if (routeDashed) {
        // Walking / biking: show the dotted line (solid colour, no traffic gradient — there
        // isn't any for foot/bike), hide the solid one. Re-dot when the route swapped or the
        // zoom moved enough to change the on-screen spacing (identity + 0.2-zoom gates keep
        // this cheap on the per-recomposition applyData path).
        style.getLayer(ROUTE_LAYER)?.setProperties(PropertyFactory.visibility(Property.NONE))
        style.getLayer(ROUTE_DASH_LAYER)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        // Walk/bike shows ONLY the dots: the solid grey alternate lines (and any leftover nav
        // ahead-suffix) read as "the car route is still drawn" next to them (user 2026-07-08).
        // Alternates stay pickable from the route list.
        style.getLayer(ALT_ROUTE_LAYER)?.setProperties(PropertyFactory.visibility(Property.NONE))
        style.getSourceAs<GeoJsonSource>(ROUTE_AHEAD_SRC)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        style.getLayer(ROUTE_AHEAD_LAYER)?.setProperties(PropertyFactory.visibility(Property.NONE))
        if (dashDotPoly !== route || kotlin.math.abs(map.cameraPosition.zoom - dashDotZoom) > 0.2) {
            regenRouteDots(map, style, route)
        }
    } else if (!navMode) {
        // Driving, not navigating (preview / route picker): the solid traffic-coloured line,
        // no driven-grey. The nav ahead-suffix layer is cleared ONCE on the nav→browse
        // transition so the last drive's remnant doesn't linger under previews.
        style.getLayer(ROUTE_DASH_LAYER)?.setProperties(PropertyFactory.visibility(Property.NONE))
        if (dashDotPoly.isNotEmpty()) regenRouteDots(map, style, emptyList())
        // Back on a solid (drive) route: the alternates line returns (walk/bike hides it).
        style.getLayer(ALT_ROUTE_LAYER)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        style.getLayer(ROUTE_LAYER)?.setProperties(
            PropertyFactory.visibility(Property.VISIBLE),
            PropertyFactory.lineGradient(routeGradient(p, routeInt, trafficSpans)),
        )
        if (lastNavRouteMode) {
            style.getSourceAs<GeoJsonSource>(ROUTE_AHEAD_SRC)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            style.getLayer(ROUTE_AHEAD_LAYER)?.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    } else {
        // NAV: the frame ticker owns the route rendering — the driven/ahead GEOMETRY split
        // (ahead suffix on ROUTE_AHEAD_LAYER, traversed grey on ROUTE_LAYER). Writing a
        // gradient from recomposition here would fight it once per fix.
        style.getLayer(ROUTE_DASH_LAYER)?.setProperties(PropertyFactory.visibility(Property.NONE))
        style.getLayer(ROUTE_LAYER)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    }
    lastNavRouteMode = navMode && !routeDashed

    val altFc = FeatureCollection.fromFeatures(
        alternates.filter { it.second.size >= 2 }.map { (idx, line) ->
            Feature.fromGeometry(
                LineString.fromLngLats(line.map { Point.fromLngLat(it.lng, it.lat) }),
            ).apply { addNumberProperty(ALT_INDEX_PROP, idx) }
        },
    )
    style.getSourceAs<GeoJsonSource>(ALT_ROUTE_SRC)?.setGeoJson(altFc)
    style.getLayer(ALT_ROUTE_LAYER)?.setProperties(PropertyFactory.lineColor(altColor))

    // Only rebuild + re-set the marker/ambient GeoJSON when the DATA actually changed. applyData runs
    // on every recomposition (a nav mySpeed tick, a mute/theme toggle, etc.), and setGeoJson forces a
    // full symbol-layer re-tessellation — redundant when the pins/POIs are identical. Structural
    // equality on the data classes is enough. The style-reload path resets these holders (the fresh
    // source is empty) so the layers always repopulate. (Big drag-smoothness win on the Pixel 5a.)
    if (markers != lastAppliedMarkers) {
        val markersFc = FeatureCollection.fromFeatures(
            markers.mapIndexed { i, m ->
                // Results arrive in relevance order, so the index IS the collision rank (lower
                // sort key places first = wins the slot). Rated food places get the rating
                // bubble, everything else the red category pin; bitmaps are generated on demand
                // per style (they're theme-dependent), see PoiIcons.ensureResultIcon.
                val iconKey = PoiIcons.resultIconKey(m.name, m.category, m.rating, m.fuelPrice)
                PoiIcons.ensureResultIcon(context, style, iconKey, dark, PoiIcons.resultBubbleLabel(m.name, m.category, m.rating, m.fuelPrice))
                Feature.fromGeometry(Point.fromLngLat(m.location.lng, m.location.lat)).apply {
                    addStringProperty("name", m.name)
                    addStringProperty("icon", iconKey)
                    addNumberProperty("rank", i)
                    addNumberProperty(MARKER_INDEX_PROP, i)
                }
            },
        )
        style.getSourceAs<GeoJsonSource>(MARKERS_SRC)?.setGeoJson(markersFc)
        lastAppliedMarkers = markers
    }

    // Ambient Google POIs → category-dot features (icon = vela-poi-<group>, label = name, prominence).
    if (ambientPois != lastAppliedAmbient) {
        val ambientFc = FeatureCollection.fromFeatures(
            ambientPois.mapIndexed { i, m ->
                Feature.fromGeometry(Point.fromLngLat(m.location.lng, m.location.lat)).apply {
                    val group = PoiIcons.groupFor(m.name, m.category)
                    addStringProperty("name", m.name)
                    addStringProperty("icon", "vela-poi-$group")
                    addStringProperty("dotColor", PoiIcons.colorFor(group)) // mini-dot tier tint
                    addNumberProperty(AMBIENT_INDEX_PROP, i)
                    // Collision priority must be STABLE across the streamed partial paints: it used
                    // to be the list index, and the pool RE-RANKS as search terms land, so the same
                    // place's priority changed on every upload and the whole layer's placement
                    // reshuffled - icons visibly consolidated and popped into each other on a cold
                    // load (user 2026-07-14). Prominence is a property of the PLACE (identical in
                    // every upload), so priorities hold still while the set grows; the index only
                    // breaks ties between equally prominent places. Lower sort key places first.
                    addNumberProperty("sort", (10.0 - m.prominence) * 1000.0 + i)
                    // Prominence drives data-driven icon/text size on the layer (anchors read bigger).
                    addNumberProperty("prominence", m.prominence)
                }
            },
        )
        style.getSourceAs<GeoJsonSource>(AMBIENT_SRC)?.setGeoJson(ambientFc)
        lastAppliedAmbient = ambientPois
    }

    // Google-first: hide the OSM *business* POIs (poi_r1/r7/r20) while EITHER the ambient Google
    // dots are up (the layers would duplicate) OR a search's result set is on the map — during
    // search only the results should read as places (Google declutters the same way). A single
    // selected place (markers.size == 1) keeps the basemap POIs: its neighbours are context, not
    // clutter. Its OWN identity gate (not the ambient one): results can appear/clear while the
    // ambient list stays empty, and the old placement inside the ambient gate would strand the
    // visibility stale. OSM transit + the rest of the basemap always stay.
    // BLEND, don't blanket-hide (user 2026-07-10): the OSM basemap POIs hide only while the
    // viewport truly sits inside the ambient fetch's covered area — pan or zoom past it and the
    // OSM icons return immediately (the ambient dots still draw where they exist, so the covered
    // core keeps Google's data and the outskirts keep OSM's, merging as fresh fetches land).
    val osmPoiVis = if (ambientCoversView || markers.size > 1) Property.NONE else Property.VISIBLE
    if (osmPoiVis != lastOsmPoiVis) {
        listOf("poi_r1", "poi_r7", "poi_r20").forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.visibility(osmPoiVis))
        }
        lastOsmPoiVis = osmPoiVis
    }
    // Stop signs + traffic lights step aside during a search too (results are the subject; the
    // junction furniture reads as clutter behind them) — but UNLIKE the basemap POIs they stay
    // up alongside the ambient dots on the browse map, so this keys on the result set alone.
    val controlsVis = if (markers.size > 1) Property.NONE else Property.VISIBLE
    if (controlsVis != lastControlsVis) {
        listOf(CONTROLS_LAYER, CONTROLS_CLAIM_LAYER).forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.visibility(controlsVis))
        }
        lastControlsVis = controlsVis
    }

    // Traffic controls (lights + stop signs) → icon features. Identity-gated like markers/ambient so a
    // nav speedo tick doesn't re-tessellate them. Empty list clears the source (e.g. zoomed back out).
    if (trafficControls != lastAppliedControls) {
        val controlsFc = FeatureCollection.fromFeatures(
            trafficControls.map { ctl ->
                Feature.fromGeometry(Point.fromLngLat(ctl.loc.lng, ctl.loc.lat)).apply {
                    addStringProperty("icon", if (ctl.stop) STOP_IMG else SIGNAL_IMG)
                }
            },
        )
        style.getSourceAs<GeoJsonSource>(CONTROLS_SRC)?.setGeoJson(controlsFc)
        lastAppliedControls = trafficControls
    }

    // ALPR/Flock cameras → icon features (identity-gated like the controls). Empty when the layer's
    // off or zoomed out, which clears the source.
    if (flockCameras != lastAppliedFlock) {
        val flockFc = FeatureCollection.fromFeatures(
            flockCameras.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.loc.lng, cam.loc.lat))
            },
        )
        style.getSourceAs<GeoJsonSource>(FLOCK_SRC)?.setGeoJson(flockFc)
        lastAppliedFlock = flockCameras
    }

    // Canonical GTFS stops (Transitous). Feature index -> the VM's stop list for the tap handler.
    if (transitStops != lastAppliedTransitStops) {
        val stopsFc = FeatureCollection.fromFeatures(
            transitStops.mapIndexed { i, st ->
                Feature.fromGeometry(Point.fromLngLat(st.lon, st.lat)).apply {
                    addNumberProperty(TRANSIT_STOP_INDEX_PROP, i)
                    addStringProperty("name", st.name)
                }
            },
        )
        style.getSourceAs<GeoJsonSource>(TRANSIT_STOPS_SRC)?.setGeoJson(stopsFc)
        lastAppliedTransitStops = transitStops
    }
    // While the canonical layer has coverage here, hide the basemap's own OSM bus icons (class
    // "bus" in poi_transit) so the same stop can't draw twice at slightly different corners;
    // rail/airport stay basemap. Original filter captured once per style load and restored when
    // coverage goes (offline in an uncached area).
    val hideOsmBus = transitStops.isNotEmpty()
    if (hideOsmBus != lastTransitBusHidden) {
        runCatching {
            (style.getLayer("poi_transit") as? SymbolLayer)?.let { poi ->
                if (origPoiTransitFilter == null) origPoiTransitFilter = poi.filter
                val orig = origPoiTransitFilter
                val noBus = Expression.neq(Expression.get("class"), Expression.literal("bus"))
                poi.setFilter(
                    when {
                        hideOsmBus && orig != null -> Expression.all(orig, noBus)
                        hideOsmBus -> noBus
                        else -> orig ?: Expression.literal(true)
                    },
                )
            }
        }
        lastTransitBusHidden = hideOsmBus
    }

    // The location source: in browse mode applyData owns it (set it from the fix here);
    // in NAV the per-frame motion-model ticker owns it (smooth glide), so don't fight it —
    // except to CLEAR it when there's no fix.
    if (!navMode || me == null) {
        val meFc = if (me != null) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(me.lng, me.lat)).apply {
                    addNumberProperty("bearing", bearing ?: 0f)
                },
            )
        } else {
            FeatureCollection.fromFeatures(emptyList<Feature>())
        }
        style.getSourceAs<GeoJsonSource>(ME_SRC)?.setGeoJson(meFc)
    }

    // Two modes, Google-style. NAV: the puck IS the position — a solid blue arrow — so
    // hide the dot and swap the heading layer's icon to the arrow. BROWSE: the blue dot
    // (grey when the fix is stale) + a faint heading cone. The cone/puck both hide while
    // stale (old bearing).
    val showPuck = navMode && me != null && bearing != null && !meStale
    style.getLayer(ME_LAYER)?.setProperties(
        PropertyFactory.circleColor(if (meStale) "#9AA0A6" else "#4285F4"),
        PropertyFactory.visibility(if (showPuck) Property.NONE else Property.VISIBLE),
    )
    style.getLayer(ME_ARROW_LAYER)?.setProperties(
        PropertyFactory.iconImage(if (navMode) NAV_PUCK_IMG else ME_ARROW_IMG),
        PropertyFactory.visibility(if (me != null && bearing != null && !meStale) Property.VISIBLE else Property.NONE),
    )

    val previewFc = if (preview != null) {
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(preview.lng, preview.lat)))
    } else {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }
    style.getSourceAs<GeoJsonSource>(PREVIEW_SRC)?.setGeoJson(previewFc)
}

/** Google-style heading beam: a translucent blue cone whose apex sits at the
 *  location dot (bitmap centre) and fans out toward north (0°); rotated by the
 *  device bearing + drawn beneath the dot, it reads like Google's "flashlight"
 *  direction indicator rather than a hard arrow. */
private fun arrowBitmap(): Bitmap {
    val size = 132
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val tipY = 8f
    val path = Path().apply {
        moveTo(cx, cx)               // apex at centre (under the dot)
        lineTo(cx - 36f, tipY)
        quadTo(cx, tipY - 7f, cx + 36f, tipY)
        close()
    }
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                cx, cx, cx, tipY,
                android.graphics.Color.argb(150, 66, 133, 244),
                android.graphics.Color.argb(0, 66, 133, 244),
                android.graphics.Shader.TileMode.CLAMP,
            )
        },
    )
    return bmp
}

/** Navigation puck: a WHITE chevron inside a filled BRIGHT-NAVY circle with a soft drop shadow
 *  and NO white ring (user 2026-07-11: bigger, drop the ring, brighter navy blue). Points up
 *  (north) so `iconRotate(bearing)` aims it down the heading. */
private fun navPuckBitmap(): Bitmap {
    val size = 176 // +30% per user 2026-07-15 (was 136, 112, 96)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val r = 65f
    // Soft drop shadow, offset slightly down (blur needs a software canvas - this is one).
    canvas.drawCircle(
        cx, cy + 5f, r,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(70, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(10f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        },
    )
    // The bright-navy disc - no white ring this time (user call). #1a46e5 = a vivid, deep blue.
    canvas.drawCircle(
        cx, cy, r,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#1a46e5") },
    )
    // White chevron/arrow, centred, pointing up - scaled up with the bigger disc.
    val arrow = Path().apply {
        moveTo(cx, cy - 32f)          // tip
        lineTo(cx + 27f, cy + 26f)    // bottom-right
        lineTo(cx, cy + 12f)          // notch
        lineTo(cx - 27f, cy + 26f)    // bottom-left
        close()
    }
    canvas.drawPath(
        arrow,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        },
    )
    return bmp
}

/** A Google-style red map pin with a white centre dot, anchored at its bottom tip. */
/** The walk/bike route dot: route-blue fill with a WHITE outline (Google's look — the ring
 *  keeps the chain readable over dark roads and the blue casing alike). Colours are baked in
 *  (not SDF-tinted): an SDF is single-colour, and the walk/bike line is always route-blue. */
private fun routeDotBitmap(): Bitmap {
    val d = 26
    val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F6FEB.toInt() }
    canvas.drawCircle(d / 2f, d / 2f, d / 2f - 1f, white)
    canvas.drawCircle(d / 2f, d / 2f, d / 2f - 4f, blue)
    return bmp
}

private fun pinBitmap(): Bitmap {
    val w = 60
    val h = 80
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = w / 2f
    val headR = w * 0.38f
    val headCy = headR + 4f
    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEA4335.toInt() }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    // Tail: a triangle from the lower edge of the head down to the tip.
    val tail = Path().apply {
        moveTo(cx - headR * 0.72f, headCy + headR * 0.70f)
        lineTo(cx + headR * 0.72f, headCy + headR * 0.70f)
        lineTo(cx, h - 3f)
        close()
    }
    canvas.drawPath(tail, red)
    canvas.drawCircle(cx, headCy, headR, red)
    canvas.drawCircle(cx, headCy, headR * 0.40f, white)
    return bmp
}

/** The parking pin: same silhouette as the search pin, teal head with a bold white "P". */
private fun parkingBitmap(): Bitmap {
    val w = 60
    val h = 80
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = w / 2f
    val headR = w * 0.38f
    val headCy = headR + 4f
    val teal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00897B.toInt() }
    val tail = Path().apply {
        moveTo(cx - headR * 0.72f, headCy + headR * 0.70f)
        lineTo(cx + headR * 0.72f, headCy + headR * 0.70f)
        lineTo(cx, h - 3f)
        close()
    }
    canvas.drawPath(tail, teal)
    canvas.drawCircle(cx, headCy, headR, teal)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = headR * 1.25f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("P", cx, headCy - (text.ascent() + text.descent()) / 2f, text)
    return bmp
}

/** A small traffic-light housing (white-rimmed dark rounded rect + red/amber/green dots) for the
 *  map-drawn signal layer. Sized to read as a recognisable stoplight at a ~0.55 icon scale, z16+. */
private fun trafficLightBitmap(): Bitmap {
    val w = 30
    val h = 60
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt() }
    c.drawRoundRect(1f, 1f, w - 1f, h - 1f, 8f, 8f, white) // white rim for contrast on any basemap
    c.drawRoundRect(3f, 3f, w - 3f, h - 3f, 6f, 6f, body)  // dark housing
    val cx = w / 2f
    val r = 6f
    c.drawCircle(cx, h * 0.26f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
    c.drawCircle(cx, h * 0.50f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt() })
    c.drawCircle(cx, h * 0.74f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF43A047.toInt() })
    return bmp
}

/** A small red stop-sign octagon (white rim + "STOP") for the map-drawn stop layer. */
private fun stopSignBitmap(): Bitmap {
    val s = 46
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = s / 2f
    val cy = s / 2f
    fun octagon(radius: Float) = Path().apply {
        for (k in 0 until 8) {
            val a = Math.toRadians(22.5 + k * 45)
            val x = cx + radius * Math.cos(a).toFloat()
            val y = cy + radius * Math.sin(a).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    c.drawPath(octagon(cx - 1f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }) // rim
    c.drawPath(octagon(cx - 4f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() }) // red field
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 11f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    c.drawText("STOP", cx, cy + 4f, label)
    return bmp
}

/** ALPR / "Flock" surveillance-camera marker: a maroon rounded badge with a white CCTV-camera glyph,
 *  deliberately distinct from the POI dots and the traffic controls so a plate reader reads as a
 *  "watch out" pin, not a place. */
private fun alprCameraBitmap(): Bitmap {
    val s = 46
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val maroon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7B1FA2.toInt() } // purple: distinct from red controls
    // Rounded-square badge (white rim + purple field).
    c.drawRoundRect(2f, 2f, s - 2f, s - 2f, 11f, 11f, white)
    c.drawRoundRect(4.5f, 4.5f, s - 4.5f, s - 4.5f, 9f, 9f, maroon)
    // A simple CCTV camera in white: body, lens, and a mount arm.
    val body = android.graphics.RectF(12f, 18f, 30f, 27f)
    c.drawRoundRect(body, 2f, 2f, white)
    c.drawCircle(31f, 22.5f, 4.2f, white) // lens housing at the front
    c.drawCircle(31f, 22.5f, 2.0f, maroon) // lens glass
    // Mount: a short arm up to the top rail.
    val arm = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2.4f; style = Paint.Style.STROKE }
    c.drawLine(17f, 18f, 17f, 12f, arm)
    c.drawLine(12f, 12f, 24f, 12f, arm)
    return bmp
}

/** Canonical GTFS stop badge: a small blue circle with a white bus glyph, the same marker
 *  language as the ALPR badge but round + transit-blue so it reads as a stop, not a POI. */
private fun transitStopBitmap(): Bitmap {
    val s = 40
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt() } // transit blue
    val cx = s / 2f
    c.drawCircle(cx, cx, cx - 1.5f, white) // rim
    c.drawCircle(cx, cx, cx - 3.5f, blue)
    // Bus glyph in white: body, windshield band, wheels.
    val body = android.graphics.RectF(12f, 11f, 28f, 26f)
    c.drawRoundRect(body, 3.5f, 3.5f, white)
    val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue.color }
    c.drawRect(14f, 15f, 26f, 20.5f, band) // window band
    c.drawCircle(15.5f, 27.5f, 2.2f, white) // wheels
    c.drawCircle(24.5f, 27.5f, 2.2f, white)
    return bmp
}
