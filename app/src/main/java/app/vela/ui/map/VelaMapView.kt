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
private const val PIN_IMG = "vela-pin"
private const val MARKER_INDEX_PROP = "vela-marker-index"
// Ambient Google POIs — small category dots (reusing PoiIcons' `vela-poi-<group>` images), the
// "Google for the businesses" layer that replaces the OSM business POIs on the bare browse map.
private const val AMBIENT_SRC = "vela-ambient-src"
private const val AMBIENT_LAYER = "vela-ambient"
private const val CONTROLS_SRC = "vela-controls-src" // OSM traffic lights + stop signs drawn at high zoom
private const val CONTROLS_LAYER = "vela-controls"
private const val SIGNAL_IMG = "vela-signal"
private const val STOP_IMG = "vela-stop"
private const val AMBIENT_INDEX_PROP = "vela-ambient-index"
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
data class MapMarker(val name: String, val location: LatLng, val category: String? = null, val prominence: Double = 0.0)

// Last marker/ambient lists actually pushed to the GeoJSON sources, so applyData can skip a redundant
// setGeoJson (a full symbol re-tessellation) when they're unchanged. Nulled on style reload (the fresh
// source is empty and must repopulate). Single map instance, so file scope is fine.
private var lastAppliedMarkers: List<MapMarker>? = null
private var lastAppliedAmbient: List<MapMarker>? = null
private var lastAppliedControls: List<app.vela.core.data.TrafficControl>? = null
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
    recenterTick: Int = 0, // bumped on each recenter tap → force a move even if already "centered"
    cameraBottomInsetPx: Int = 0,
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
    frameMarkers: Boolean,
    navMode: Boolean,
    navFollowing: Boolean = true,
    onNavPanned: () -> Unit = {},
    onScaleChanged: (metersPerPixel: Double) -> Unit = {},
    darkTheme: Boolean,
    applyKeylessTheme: Boolean,
    trafficOn: Boolean,
    transitOn: Boolean = false, // highlight rail (train + subway/tram) lines from the basemap tiles
    previewTarget: LatLng?,
    onPoiTap: (name: String, location: LatLng) -> Unit,
    onMarkerTap: (index: Int) -> Unit,
    ambientPois: List<MapMarker> = emptyList(),
    onAmbientTap: (index: Int) -> Unit = {},
    buildingOverlays: List<String> = emptyList(), // full pmtiles:// source URIs (file:// downloaded / https:// streamed)
    addressOverlays: List<String> = emptyList(), // pmtiles:// URIs for house-number labels (streamed, OpenAddresses)
    trafficControls: List<app.vela.core.data.TrafficControl> = emptyList(), // OSM lights + stop signs drawn at high zoom
    navBannerBottomPx: Int = 0, // measured screen-Y of the maneuver banner's bottom edge; drops the compass below it during nav
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
        else -> statusBarTopPx + gap8Px
    }
    val compassRightPx = with(density) { 8.dp.roundToPx() }
    val poiTap = rememberUpdatedState(onPoiTap)
    val markerTap = rememberUpdatedState(onMarkerTap)
    val ambientTap = rememberUpdatedState(onAmbientTap)
    val cameraIdle = rememberUpdatedState(onCameraIdle)
    val longPress = rememberUpdatedState(onMapLongPress)
    val addrLabelTap = rememberUpdatedState(onAddressLabelTap)
    val navPanned = rememberUpdatedState(onNavPanned)
    val scaleChanged = rememberUpdatedState(onScaleChanged)
    val selectAlt = rememberUpdatedState(onSelectAlternate)
    val navModeHolder = rememberUpdatedState(navMode)
    val navFollowingHolder = rememberUpdatedState(navFollowing)
    val myBearingHolder = rememberUpdatedState(myBearing) // vehicle course for the accel projection
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
        listOf("poi_r1", "poi_r7", "poi_r20", "poi_transit").forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.visibility(vis))
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
        style.layers.filter { it.id.startsWith("vela-ovl-") }.forEach { runCatching { style.removeLayer(it) } }
        style.sources.filter { it.id.startsWith("vela-ovl-src-") }.forEach { runCatching { style.removeSource(it) } }
        val fill = if (darkTheme) "#323f54" else "#dde1e7" // == the OSM building fill (applyLight/applyDark)
        val line = if (darkTheme) "#3f4e66" else "#c4c9d1"
        val below = style.getLayer("building")?.id // beneath OSM buildings so they win wherever OSM has them
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

    // House-number labels from the open ADDRESS overlay (OpenAddresses PMTiles of points): a SymbolLayer of the
    // `number` field, STREAMED for the region in view — fills in house numbers where OSM has no `addr:housenumber`
    // (the same gap the building overlay fills for footprints). Matched to the basemap `vela-housenumber` style
    // (Noto Sans 10, grey + white halo). minZoom 17.5 so numbers only appear at street level (Google-style) and
    // collision thins dense blocks. INSERTED BELOW the traffic-controls layer (which sits below the ambient POI
    // icons) — NOT addLayer/top: MapLibre places symbols TOPMOST-LAYER-FIRST, so numbers stacked above the
    // ambient layer grabbed their collision boxes before the business icons placed, EVICTING them at z16+
    // (device-reproduced: Applebee's icon on the "5710" building vanished the moment numbers appeared; small
    // neighbours survived because the prominence-scaled big icons collide the most). Below the icons, numbers
    // place last and yield — Google's exact behaviour (a house number never displaces a business icon).
    LaunchedEffect(addressOverlays, styleRef, darkTheme) {
        val style = styleRef ?: return@LaunchedEffect
        style.layers.filter { it.id.startsWith("vela-addr-") }.forEach { runCatching { style.removeLayer(it) } }
        style.sources.filter { it.id.startsWith("vela-addr-src-") }.forEach { runCatching { style.removeSource(it) } }
        // The overlay statewide data covers what OSM has too — hide the basemap number layer while the overlay
        // is active, or the SAME address renders twice at a slight offset (device-seen: "5611" / "5607" doubled).
        style.getLayer("vela-housenumber")?.setProperties(
            PropertyFactory.visibility(if (addressOverlays.isEmpty()) Property.VISIBLE else Property.NONE),
        )
        val txt = if (darkTheme) "#9aa0a6" else "#8a8a8a"
        val halo = if (darkTheme) "#1b2432" else "#ffffff"
        addressOverlays.forEachIndexed { i, uri ->
            runCatching {
                val srcId = "vela-addr-src-$i"
                style.addSource(VectorSource(srcId, uri))
                val layer =
                    SymbolLayer("vela-addr-$i", srcId).apply {
                        setSourceLayer("address") // tippecanoe layer name (build-address-region.sh: -l address)
                        setMinZoom(17.5f) // Google shows house numbers only at STREET level (~z17.5-18); 16 carpeted the map in numbers ("too soon")
                        setProperties(
                            PropertyFactory.textField(Expression.get("number")),
                            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                            PropertyFactory.textSize(10f),
                            PropertyFactory.textColor(txt),
                            PropertyFactory.textHaloColor(halo),
                            PropertyFactory.textHaloWidth(1f),
                            // Numbers still YIELD to icons/labels (allow-overlap stays false), but they
                            // never enter the collision index themselves: nothing needs to dodge a house
                            // number, and keeping hundreds of them out of the index makes each placement
                            // pass at street zoom cheaper (they're the densest symbols on screen there).
                            PropertyFactory.textIgnorePlacement(true),
                        )
                    }
                if (style.getLayer(CONTROLS_LAYER) != null) {
                    style.addLayerBelow(layer, CONTROLS_LAYER) // below controls → below ambient icons (see above)
                } else {
                    style.addLayer(layer) // controls layer missing (defensive) — top is better than absent
                }
            }
        }
    }

    // "3D buildings" setting → the basemap's building-3d fill-extrusion layer (z16+).
    // Extrusion is the most fragment-expensive thing the map draws, so this is the direct
    // lever for zoomed-in pan stutter on weaker GPUs. applyLight/applyDark colour the layer
    // but never touch visibility, so this effect owns it (re-applied on style reload too).
    val buildings3d = app.vela.ui.Buildings3d.on.value
    LaunchedEffect(buildings3d, styleRef) {
        styleRef?.getLayer("building-3d")?.setProperties(
            PropertyFactory.visibility(if (buildings3d) Property.VISIBLE else Property.NONE),
        )
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
            navPuck.kalman.reset() // nav ended — don't carry a stale speed into the next trip
            return@LaunchedEffect
        }
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
                if (cam != null && navFollowingHolder.value && !scaling[0]) {
                    val sp = navPuck.speed.toFloat().coerceIn(0f, 30f)
                    navZoomSpeed[0] += (sp - navZoomSpeed[0]) * (1f - kotlin.math.exp(-dtE / 0.6f))
                    val tgtZoom = if (!navUserZoom[0].isNaN()) navUserZoom[0]
                        else 17.3 - (navZoomSpeed[0] / 30f) * (17.3 - 15.0)
                    if (camState[0].isNaN()) { // (re)seed from the live camera for a smooth hand-off
                        val cp = cam.cameraPosition
                        camState[0] = cp.target?.latitude ?: pt.lat
                        camState[1] = cp.target?.longitude ?: pt.lng
                        camState[2] = cp.bearing
                        camState[3] = if (cp.zoom > 1.0) cp.zoom else tgtZoom
                    }
                    val k = (1f - kotlin.math.exp(-dtE / 0.12f)).toDouble()
                    camState[0] += (pt.lat - camState[0]) * k
                    camState[1] += (pt.lng - camState[1]) * k
                    val db = ((navPuck.displayBearing.toDouble() - camState[2] + 540.0) % 360.0) - 180.0 // shortest arc
                    camState[2] = (camState[2] + db * k + 360.0) % 360.0
                    camState[3] += (tgtZoom - camState[3]) * k
                    cam.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(MLLatLng(camState[0], camState[1]))
                                .bearing(camState[2])
                                .zoom(camState[3])
                                .tilt(55.0)
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
                map.setMaxPitchPreference(70.0)
                // Tap a labelled POI on the map to open it. (Named so the D-pad
                // controller's OK-at-crosshair runs the EXACT same resolution path;
                // docs/dpad.md.)
                val handleTap = handleTap@{ tapped: MLLatLng ->
                    val p = map.projection.toScreenLocation(tapped)
                    // Generous hit radius (~16dp) so taps near a POI icon register —
                    // a tight box made the bigger markers feel un-tappable.
                    val r = density.density * 24f
                    val feats = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r))
                    // Our own search-result pins take priority over basemap POI labels.
                    val pin = feats.firstOrNull { it.hasProperty(MARKER_INDEX_PROP) }
                    if (pin != null) {
                        markerTap.value(pin.getNumberProperty(MARKER_INDEX_PROP).toInt())
                        return@handleTap true
                    }
                    // An ambient Google POI dot — opens the place (priority over basemap POI labels).
                    val amb = feats.firstOrNull { it.hasProperty(AMBIENT_INDEX_PROP) }
                    if (amb != null) {
                        ambientTap.value(amb.getNumberProperty(AMBIENT_INDEX_PROP).toInt())
                        return@handleTap true
                    }
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
                    val hit = feats.firstOrNull { it.geometry() is Point && nameOf(it) != null }
                    if (hit != null) {
                        val pt = hit.geometry() as Point
                        poiTap.value(nameOf(hit)!!, LatLng(pt.latitude(), pt.longitude()))
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
                        map.queryRenderedFeatures(box, *addrLayers).firstOrNull { it.geometry() is Point }
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
                        if (navModeHolder.value && !scaling[0]) {
                            navPanned.value()
                            navUserZoom[0] = Double.NaN
                        }
                    }
                    override fun onMoveEnd(detector: MoveGestureDetector) {}
                })
                map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                    override fun onScaleBegin(detector: StandardScaleGestureDetector) { scaling[0] = true }
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
                mapRef = map
            }
        }
        val map = mapRef ?: return@AndroidView
        // Keep the compass clear of the status bar (insets are ready post-layout).
        map.uiSettings.setCompassMargins(0, compassTopPx, compassRightPx, 0)

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
        val styleKey = "$styleUri|dark=$darkTheme"
        if (appliedStyleKey != styleKey) {
            appliedStyleKey = styleKey
            val builder = if (styleUri.startsWith("asset://")) {
                // Bundled style JSON (Liberty re-pointed at Roboto glyphs). Its
                // tile/sprite/glyph URLs are absolute, so it still loads keyless.
                val json = context.assets.open(styleUri.removePrefix("asset://"))
                    .bufferedReader().use { it.readText() }
                Style.Builder().fromJson(json)
            } else {
                Style.Builder().fromUri(styleUri)
            }
            map.setStyle(builder) { style ->
                styleRef = style
                ensureLayers(style)
                lastAppliedMarkers = null // fresh style = empty sources; force applyData to repopulate
                lastAppliedAmbient = null
                lastAppliedControls = null
                lastAppliedRouteLine = null
                lastGradM[0] = -1e9 // force the nav split to re-render on the fresh style
                PoiIcons.addTo(context, style)
                if (applyKeylessTheme) applyMapTheme(style, darkTheme) else tuneMapTiler(style, darkTheme)
                applyData(map, style, routePolyline, routeColor, routeDashed, routeTrafficSpans, alternates, altColor, markers, ambientPois, trafficControls, displayLoc, displayBearing, locationStale, previewTarget, routeProgress, navMode)
                ensureTraffic(style, trafficOn)
                ensureTransit(style, transitOn)
            }
        } else {
            styleRef?.let {
                applyData(map, it, routePolyline, routeColor, routeDashed, routeTrafficSpans, alternates, altColor, markers, ambientPois, trafficControls, displayLoc, displayBearing, locationStale, previewTarget, routeProgress, navMode)
                ensureTraffic(it, trafficOn)
                ensureTransit(it, transitOn)
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
                    val zoom = if (cameraBottomInsetPx > 0) 16.5 else 15.0
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(MLLatLng(t.lat, t.lng), zoom), 500)
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
                            else 17.3 - (navZoomSpeed[0] / 30f) * (17.3 - 15.0)
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(MLLatLng(loc.lat, loc.lng))
                                    .zoom(zoom).tilt(55.0).bearing(brg.toDouble()).build(),
                            ),
                            550,
                        )
                    }
                }
            }

            routePolyline.size >= 2 && routePolyline.hashCode() != lastFittedRouteKey -> {
                lastFittedRouteKey = routePolyline.hashCode()
                val builder = MLLatLngBounds.Builder()
                routePolyline.forEach { builder.include(MLLatLng(it.lat, it.lng)) }
                // Reserve room at the bottom for the directions panel so the whole route
                // (and its greyed alternates) frames ABOVE it, not behind it (Google-style).
                val pad = 140
                val bottom = if (cameraBottomInsetPx > 0) cameraBottomInsetPx + pad else pad
                runCatching {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(builder.build(), pad, pad, pad, bottom), 800,
                    )
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

            else -> {
                val target = cameraTarget ?: myLocation
                if (target != null && target != lastCameraTarget) {
                    lastCameraTarget = target
                    // Zoom in closer when a place sheet is up (focusing a single pin),
                    // looser for a plain recenter.
                    val zoom = if (cameraBottomInsetPx > 0) 16.5 else 14.5
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(MLLatLng(target.lat, target.lng), zoom),
                    )
                }
            }
        }
    }
}

private fun ensureLayers(style: Style) {
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
                // 17.5 matches Google: house numbers only at true street level, not neighbourhood zoom
                // (16 carpeted the map — user 2026-07-06). Keep in lockstep with the vela-addr overlay.
                setMinZoom(17.5f)
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
            PropertyFactory.lineWidth(6f),
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
            PropertyFactory.lineWidth(6f),
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
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (style.getLayer(ROUTE_LAYER) != null) style.addLayerBelow(alt, ROUTE_LAYER)
        else style.addLayer(alt)
    }
    if (style.getImage(PIN_IMG) == null) style.addImage(PIN_IMG, pinBitmap())
    if (style.getSource(MARKERS_SRC) == null) {
        style.addSource(GeoJsonSource(MARKERS_SRC))
        style.addLayer(
            SymbolLayer(MARKERS_LAYER, MARKERS_SRC).withProperties(
                PropertyFactory.iconImage(PIN_IMG),
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
        style.addSource(GeoJsonSource(AMBIENT_SRC))
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
                PropertyFactory.textField(Expression.get("name")),
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
                PropertyFactory.textVariableAnchor(
                    arrayOf(Property.TEXT_ANCHOR_RIGHT, Property.TEXT_ANCHOR_TOP),
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
    }
    // Traffic controls (OSM `highway=traffic_signals`/`stop`): non-interactive icons drawn at high zoom
    // BENEATH the POI dots + pins. Two images keyed by the feature "icon" prop; collision (allowOverlap
    // false) keeps a dense grid of lights legible instead of a pile. minZoom matches the fetch gate.
    if (style.getImage(SIGNAL_IMG) == null) style.addImage(SIGNAL_IMG, trafficLightBitmap())
    if (style.getImage(STOP_IMG) == null) style.addImage(STOP_IMG, stopSignBitmap())
    if (style.getSource(CONTROLS_SRC) == null) {
        style.addSource(GeoJsonSource(CONTROLS_SRC))
        style.addLayerBelow(
            SymbolLayer(CONTROLS_LAYER, CONTROLS_SRC).apply {
                setMinZoom(16f)
                setProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    // Zoom-scaled so they read at nav zoom (~16-17.5) and grow as you zoom in — the flat 0.55
                    // was too small to spot, especially tilted in nav (user 2026-07-06 wanted them bigger).
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(15.5f, 0.75f),
                            Expression.stop(17f, 1.05f),
                            Expression.stop(19f, 1.5f),
                        ),
                    ),
                    // Traffic controls are SPARSE (one per junction) — draw them all instead of letting the
                    // denser POI layer collide them away, so they're actually visible on the browse map too
                    // (Google shows all of them at street zoom). This reverses the earlier "collision keeps a
                    // dense grid legible" — the user wants to see them, and at z16+ junctions are well-separated.
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPadding(2f),
                )
            },
            AMBIENT_LAYER,
        )
    }
    if (style.getSource(ME_SRC) == null) {
        style.addSource(GeoJsonSource(ME_SRC))
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
        )
        hs.setMaxZoom(16f)
        // Below the first road layer → above water/landuse (so terrain shades the
        // land) but under roads + labels (which stay readable).
        val firstRoad = style.layers.firstOrNull { it.id.startsWith("road") }?.id
        if (firstRoad != null) style.addLayerBelow(hs, firstRoad) else style.addLayer(hs)
    }
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
        // placement pushed it over POIs).
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
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
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
    } else if (!on && present) {
        runCatching { style.removeLayer(TRANSIT_LAYER) }
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
    if (dark) applyDark(style) else applyLight(style)
    PoiIcons.applyToLiberty(style, dark)
    // Ambient Google-POI labels match the ICON's category colour, Google-style — saturated in light,
    // pastel tints in dark (see PoiIcons.labelColor). Search-result pins stay plain (Google does too).
    (style.getLayer(AMBIENT_LAYER) as? SymbolLayer)?.setProperties(
        PropertyFactory.textColor(PoiIcons.ambientLabelColor(dark)),
        PropertyFactory.textHaloColor(if (dark) "#11161C" else "#FFFFFF"),
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
    // Google-Maps light palette: clean white road fills on a light-grey land, with
    // every casing faded DOWN the hierarchy until minor-road casing == the land, so
    // streets are crisp white lines with NO outline (the outlines were exactly what
    // made it look un-Google). Soft-yellow motorways, neutralised landuse (no tan
    // residential/commercial blobs), subtle buildings. Tuned live in a MapLibre GL
    // JS harness against Google for reference.
    val land = "#e8eaed"
    val white = "#ffffff"
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor(land))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#a9d3f0"))
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#cfeccd"), PropertyFactory.fillOpacity(1f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#cfeccd"), PropertyFactory.fillOpacity(0.7f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#c4e6bf"), PropertyFactory.fillOpacity(0.7f))
    // Buildings (OSM footprints, already in the Liberty tiles — no key/data needed).
    // The old #e2e3e6 was a hair off the #e8eaed land, so they were ~invisible; give
    // them a touch more grey + a subtle outline so they read like Google's at z15+.
    style.getLayer("building")?.setProperties(
        PropertyFactory.fillColor("#dde1e7"),
        PropertyFactory.fillOutlineColor("#c4c9d1"),
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
        PropertyFactory.fillExtrusionColor("#dde1e7"),
        PropertyFactory.fillExtrusionOpacity(0.9f),
    )
    // Extrusions only once zoomed into a block — the flat fill+outline gives the footprint
    // look at browse zoom, and fill-extrusion is the per-pixel-expensive part on a Pixel 5a.
    style.getLayer("building-3d")?.setMinZoom(16f)
    // Neutralise the tan/yellow landuse fills (residential/commercial/school/…) into
    // the land — Google keeps these flat, not coloured blobs.
    val greens = setOf("park", "landcover_grass", "landcover_wood")
    style.layers.forEach { layer ->
        if (layer is FillLayer && layer.id !in greens &&
            (layer.id.startsWith("landuse") || layer.id.startsWith("landcover"))
        ) {
            layer.setProperties(PropertyFactory.fillColor(land))
        }
    }
    // Liberty fills wetlands with a fern-hatch pattern and pedestrian plazas with a
    // dotted one — Google shows both flat. Clear the pattern so the flat fill shows.
    style.getLayer("landcover_wetland")?.setProperties(
        PropertyFactory.fillColor("#d6e8d0"),
        PropertyFactory.fillPattern(Expression.literal("")),
    )
    style.getLayer("road_area_pattern")?.setProperties(
        PropertyFactory.fillColor("#ededed"),
        PropertyFactory.fillPattern(Expression.literal("")),
    )
    // Roads — white fills, soft-yellow motorways; casings fade to nothing on minor
    // roads. Bridges mirror their road tier so overpasses match.
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
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#242f3e"))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#17263c"))
    style.getLayer("waterway_river")?.setProperties(PropertyFactory.lineColor("#17263c"))
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#1c3326"), PropertyFactory.fillOpacity(0.7f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#1c3326"), PropertyFactory.fillOpacity(0.5f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#1a3023"), PropertyFactory.fillOpacity(0.6f))
    listOf("road_minor", "road_secondary_tertiary", "road_link", "road_service_track",
        "bridge_street", "bridge_secondary_tertiary", "bridge_link", "bridge_service_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#49536a"))
    }
    listOf("road_trunk_primary", "bridge_trunk_primary").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#5e6a85"))
    }
    listOf("road_motorway", "road_motorway_link", "bridge_motorway", "bridge_motorway_link").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#6f7a96"))
    }
    // Casings blend into the night land so roads are clean (no hard outline), like
    // Google dark — the lighter road fills still read against the dark land.
    listOf("road_motorway_casing", "road_motorway_link_casing", "road_trunk_primary_casing",
        "road_secondary_tertiary_casing", "road_minor_casing", "road_link_casing", "road_service_track_casing",
        "bridge_motorway_casing", "bridge_trunk_primary_casing", "bridge_secondary_tertiary_casing",
        "bridge_street_casing", "bridge_link_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#242f3e"))
    }
    // Buildings a touch lighter than the #242f3e land + a lit edge, so they read in
    // dark mode instead of melting into the ground (same reasoning as the light path).
    style.getLayer("building")?.setProperties(
        PropertyFactory.fillColor("#323f54"),
        PropertyFactory.fillOutlineColor("#3f4e66"),
    )
    style.getLayer("building")?.setMinZoom(14f) // houses from neighbourhood zoom (see light path)
    style.getLayer("building")?.setMaxZoom(24f) // re-open the maxzoom:14 clamp (see light path — was collapsing the flat fill to empty)
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#323f54"),
        PropertyFactory.fillExtrusionOpacity(0.9f),
    )
    style.getLayer("building-3d")?.setMinZoom(16f) // extrusions only high-zoom (Pixel 5a perf)
    // Greens we keep as-is; every OTHER landuse/landcover fill (commercial, school,
    // retail, industrial, sand, …) must go dark too, or it stays a jarring cream
    // patch in dark mode.
    val greens = setOf("park", "landcover_grass", "landcover_wood")
    style.layers.forEach { layer ->
        when {
            layer is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor("#c3cad6"),
                PropertyFactory.textHaloColor("#1a2230"),
                PropertyFactory.textHaloWidth(1.1f),
            )
            layer is FillLayer && layer.id !in greens &&
                (layer.id.startsWith("landuse") || layer.id.startsWith("landcover")) ->
                layer.setProperties(PropertyFactory.fillColor("#2a3546"), PropertyFactory.fillOpacity(0.5f))
        }
    }
    // Drop the wetland fern-hatch + pedestrian-plaza patterns (flat, like Google dark).
    style.getLayer("landcover_wetland")?.setProperties(
        PropertyFactory.fillColor("#1c3326"),
        PropertyFactory.fillPattern(Expression.literal("")),
    )
    style.getLayer("road_area_pattern")?.setProperties(
        PropertyFactory.fillColor("#2a3546"),
        PropertyFactory.fillPattern(Expression.literal("")),
    )
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
    route: List<LatLng>,
    routeColor: String,
    routeDashed: Boolean,
    trafficSpans: List<Triple<Float, Float, Int>>,
    alternates: List<Pair<Int, List<LatLng>>>,
    altColor: String,
    markers: List<MapMarker>,
    ambientPois: List<MapMarker>,
    trafficControls: List<app.vela.core.data.TrafficControl>,
    me: LatLng?,
    bearing: Float?,
    meStale: Boolean,
    preview: LatLng?,
    routeProgress: Float,
    navMode: Boolean,
) {
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
                Feature.fromGeometry(Point.fromLngLat(m.location.lng, m.location.lat)).apply {
                    addStringProperty("name", m.name)
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
                    addStringProperty("name", m.name)
                    addStringProperty("icon", "vela-poi-${PoiIcons.groupFor(m.name, m.category)}")
                    addNumberProperty(AMBIENT_INDEX_PROP, i)
                    // Collision priority: the data source returns the most prominent places first, so a
                    // lower index wins its slot (MapLibre places lower symbol-sort-key first).
                    addNumberProperty("sort", i)
                    // Prominence drives data-driven icon/text size on the layer (anchors read bigger).
                    addNumberProperty("prominence", m.prominence)
                }
            },
        )
        style.getSourceAs<GeoJsonSource>(AMBIENT_SRC)?.setGeoJson(ambientFc)
        // Google-first: while ambient Google POIs are showing, hide the OSM *business* POIs
        // (poi_r1/r7/r20) so the layers don't duplicate. OSM transit + the whole OSM basemap stay; when
        // there are no ambient POIs (zoomed out / offline / nav / search) the OSM POIs come back.
        val osmPoiVis = if (ambientPois.isNotEmpty()) Property.NONE else Property.VISIBLE
        listOf("poi_r1", "poi_r7", "poi_r20").forEach { id ->
            style.getLayer(id)?.setProperties(PropertyFactory.visibility(osmPoiVis))
        }
        lastAppliedAmbient = ambientPois
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

/** Google-style navigation puck: a solid blue chevron/arrowhead with a white outline,
 *  centred on the position and pointing up (north) so `iconRotate(bearing)` aims it down
 *  the heading. Replaces the dot during nav (test-drive feedback: "we need an arrow"). */
private fun navPuckBitmap(): Bitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val path = Path().apply {
        moveTo(cx, 12f)                 // tip (top / north)
        lineTo(cx + 27f, size - 16f)    // bottom-right
        lineTo(cx, size - 30f)          // chevron notch
        lineTo(cx - 27f, size - 16f)    // bottom-left
        close()
    }
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeJoin = Paint.Join.ROUND
        },
    )
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#4285F4")
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
