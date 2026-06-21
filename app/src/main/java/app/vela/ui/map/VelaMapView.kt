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
private const val ALT_ROUTE_SRC = "vela-alt-route-src"
private const val ALT_ROUTE_LAYER = "vela-alt-route"
private const val ALT_INDEX_PROP = "vela-alt-index"
private const val MARKERS_SRC = "vela-markers-src"
private const val MARKERS_LAYER = "vela-markers"
private const val PIN_IMG = "vela-pin"
private const val MARKER_INDEX_PROP = "vela-marker-index"
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
// Google's LIVE traffic, as a raster overlay (congestion-coloured roads +
// incidents) — the web map's own `/maps/vt` tile, which is a public, keyless PNG
// on www.google.com (the same host we already scrape). The trimmed `pb` (no map
// version epoch, so it doesn't rot): `!2straffic` = the traffic layer, `!1e2` =
// overlay. Standard XYZ tile coords (`!1i{z}!2i{x}!3i{y}`).
private const val TRAFFIC_TILES =
    "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
        "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"

/** A tappable search-result pin on the map. */
data class MapMarker(val name: String, val location: LatLng)

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
    locationStale: Boolean = false,
    cameraTarget: LatLng?,
    cameraBottomInsetPx: Int = 0,
    routePolyline: List<LatLng>,
    routeColor: String,
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
    previewTarget: LatLng?,
    onPoiTap: (name: String, location: LatLng) -> Unit,
    onMarkerTap: (index: Int) -> Unit,
    onCameraIdle: (center: LatLng) -> Unit,
    onMapLongPress: (location: LatLng) -> Unit,
    onViewport: (south: Double, west: Double, north: Double, east: Double, zoom: Double) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Push MapLibre's compass below the status bar (it defaults to the top-right
    // corner, which sits *under* the status bar).
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val compassTopPx = statusBarTopPx + with(density) { 8.dp.roundToPx() }
    val compassRightPx = with(density) { 8.dp.roundToPx() }
    val poiTap = rememberUpdatedState(onPoiTap)
    val markerTap = rememberUpdatedState(onMarkerTap)
    val cameraIdle = rememberUpdatedState(onCameraIdle)
    val longPress = rememberUpdatedState(onMapLongPress)
    val navPanned = rememberUpdatedState(onNavPanned)
    val scaleChanged = rememberUpdatedState(onScaleChanged)
    val selectAlt = rememberUpdatedState(onSelectAlternate)
    val navModeHolder = rememberUpdatedState(navMode)
    val viewport = rememberUpdatedState(onViewport)
    val gestureMove = remember { booleanArrayOf(false) }
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var appliedStyleKey by remember { mutableStateOf<String?>(null) }
    var lastCameraTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastInsetPx by remember { mutableStateOf(-1) }
    var lastFittedRouteKey by remember { mutableStateOf<Int?>(null) }
    var lastFittedMarkersKey by remember { mutableStateOf<Int?>(null) }
    var lastPreviewTarget by remember { mutableStateOf<LatLng?>(null) }
    // The last fix we actually re-pointed the nav camera at, so we can skip the
    // redundant re-animations that make the follow shimmer/lag (see the nav branch).
    var lastNavTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastNavBearing by remember { mutableStateOf<Float?>(null) }
    val navPuck = remember { NavPuck() }
    val routeCum = remember(routePolyline) { cumLengths(routePolyline) }

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
        if (!navMode) return@LaunchedEffect
        navPuck.engaged = false
        var lastNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastNanos = now
            val style = styleRef ?: continue
            if (navPuck.engaged && routePolyline.size >= 2) {
                // Dead-reckon the goal forward from the last fix at the known speed (capped
                // at 2 s so a dropped GPS signal can't run the puck away down the route).
                val sinceFix = ((android.os.SystemClock.elapsedRealtime() - navPuck.targetAtMs) / 1000.0).coerceIn(0.0, 2.0)
                val predicted = navPuck.targetM + navPuck.speed * sinceFix
                val eased = navPuck.progressM + (predicted - navPuck.progressM) * (1f - kotlin.math.exp(-dt / 0.25f))
                navPuck.progressM = maxOf(navPuck.progressM, eased) // monotonic — never backward
                val (pt, segBrg) = pointAtMeters(routePolyline, routeCum, navPuck.progressM)
                navPuck.displayBearing = if (navPuck.displayBearing.isNaN()) segBrg
                    else smoothBearing(navPuck.displayBearing, segBrg, dt, 0.2f)
                navPuck.drawn = pt // the camera follows this smoothed point, not the raw fix
                setMeSource(style, pt, navPuck.displayBearing)
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
        if (mapRef == null) {
            mv.getMapAsync { map ->
                map.uiSettings.isLogoEnabled = false
                // Hide the bottom-left attribution "ⓘ" — open-tile attribution lives
                // in Settings → About instead, so the map stays clean (Google-style).
                map.uiSettings.isAttributionEnabled = false
                // Tap a labelled POI on the map to open it.
                map.addOnMapClickListener { tapped ->
                    val p = map.projection.toScreenLocation(tapped)
                    // Generous hit radius (~16dp) so taps near a POI icon register —
                    // a tight box made the bigger markers feel un-tappable.
                    val r = density.density * 24f
                    val feats = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r))
                    // Our own search-result pins take priority over basemap POI labels.
                    val pin = feats.firstOrNull { it.hasProperty(MARKER_INDEX_PROP) }
                    if (pin != null) {
                        markerTap.value(pin.getNumberProperty(MARKER_INDEX_PROP).toInt())
                        return@addOnMapClickListener true
                    }
                    // Tap a greyed alternate route line to switch to it (Google-style).
                    val altHit = map.queryRenderedFeatures(
                        RectF(p.x - r, p.y - r, p.x + r, p.y + r), ALT_ROUTE_LAYER,
                    ).firstOrNull { it.hasProperty(ALT_INDEX_PROP) }
                    if (altHit != null) {
                        selectAlt.value(altHit.getNumberProperty(ALT_INDEX_PROP).toInt())
                        return@addOnMapClickListener true
                    }
                    // POIs are named Points; some only carry name:latin/name:en, so
                    // try those too — more icons become directly tappable that way.
                    fun nameOf(f: Feature): String? = sequenceOf("name", "name:latin", "name:en")
                        .firstOrNull { f.hasProperty(it) && !f.getStringProperty(it).isNullOrBlank() }
                        ?.let { f.getStringProperty(it) }
                    val hit = feats.firstOrNull { it.geometry() is Point && nameOf(it) != null }
                    when {
                        hit != null -> {
                            val pt = hit.geometry() as Point
                            poiTap.value(nameOf(hit)!!, LatLng(pt.latitude(), pt.longitude()))
                            true
                        }
                        // An unnamed POI icon (has a class but no name — an apartment
                        // gym, an unnamed park/playground, …) used to be a dead tap.
                        // Reverse-geocode the spot to a pin + address, like a long-press.
                        feats.any { it.geometry() is Point && it.hasProperty("class") } -> {
                            longPress.value(LatLng(tapped.latitude, tapped.longitude))
                            true
                        }
                        else -> false
                    }
                }
                // Only flag camera settling when the user dragged the map (not
                // our own programmatic framing) → drives "Search this area".
                map.addOnCameraMoveStartedListener { reason ->
                    gestureMove[0] = reason ==
                        MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                    // While navigating, a manual pan detaches the follow-camera so
                    // you can look around; a "Re-center" button then reattaches it.
                    if (gestureMove[0] && navModeHolder.value) navPanned.value()
                }
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
                        scaleChanged.value(map.projection.getMetersPerPixelAtLatitude(t.latitude))
                    }
                    Unit
                }
                map.addOnCameraMoveListener { reportScale() }
                reportScale()
                // Press-and-hold anywhere → drop a pin and reverse-geocode it.
                map.addOnMapLongClickListener { p ->
                    longPress.value(LatLng(p.latitude, p.longitude))
                    true
                }
                mapRef = map
            }
        }
        val map = mapRef ?: return@AndroidView
        // Keep the compass clear of the status bar (insets are ready post-layout).
        map.uiSettings.setCompassMargins(0, compassTopPx, compassRightPx, 0)

        // Fraction of the route already driven (for the traversed-grey gradient) —
        // 0 unless we're navigating and on the line.
        val routeProgress =
            if (navMode && myLocation != null && routePolyline.size >= 2) progressAlong(routePolyline, myLocation)
            else 0f
        // Smooth the nav puck (test-drive feedback: "the dot was jumping all over"): snap
        // it onto the route so lateral GPS jitter can't throw it off the road, and face it
        // down the road (steadier than raw GPS bearing). Off-route / not navigating → raw.
        val snap = if (navMode && myLocation != null && routePolyline.size >= 2)
            snapToRoute(myLocation, myBearing, routePolyline) else null
        val displayLoc = snap?.first ?: myLocation
        val displayBearing = snap?.second ?: myBearing
        // Feed this fix to the puck motion model (the frame ticker above does the gliding).
        if (navMode && snap != null) {
            val m = nearestMeters(routePolyline, routeCum, snap.first)
            val now = android.os.SystemClock.elapsedRealtime()
            if (!navPuck.engaged || m < navPuck.targetM - 200.0) {
                // First on-route fix, or a big jump *back* (a reroute / new route) — re-anchor.
                navPuck.progressM = m; navPuck.targetM = m; navPuck.engaged = true
            } else {
                // Only advance the target by a *plausible* amount, so a route that passes
                // near itself (switchback / cloverleaf / parallel return leg) can't teleport
                // the puck — and the camera that follows it — onto the far leg. Generous:
                // speed × elapsed × 2.5 + 60 m, so a real GPS gap (large elapsed) still
                // catches up, but a 1-second nearest-point that lands a kilometre ahead is
                // rejected and dead reckoning carries the puck until a sane fix arrives.
                val dtFix = ((now - navPuck.targetAtMs) / 1000.0).coerceIn(0.0, 10.0)
                val maxStep = navPuck.speed.coerceAtLeast(1.0) * dtFix * 2.5 + 60.0
                if (m - navPuck.targetM in 0.0..maxStep) navPuck.targetM = m
            }
            navPuck.targetAtMs = now // anchor for dead reckoning
            navPuck.speed = (mySpeed ?: 0f).toDouble().coerceAtLeast(0.0)
        } else {
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
                PoiIcons.addTo(context, style)
                if (applyKeylessTheme) applyMapTheme(style, darkTheme) else tuneMapTiler(style, darkTheme)
                applyData(style, routePolyline, routeColor, routeTrafficSpans, alternates, altColor, markers, displayLoc, displayBearing, locationStale, previewTarget, routeProgress, navMode)
                ensureTraffic(style, trafficOn)
            }
        } else {
            styleRef?.let {
                applyData(it, routePolyline, routeColor, routeTrafficSpans, alternates, altColor, markers, displayLoc, displayBearing, locationStale, previewTarget, routeProgress, navMode)
                ensureTraffic(it, trafficOn)
            }
        }

        if (previewTarget == null) lastPreviewTarget = null
        // Shift the map's optical centre up by the bottom-sheet height so the
        // focused pin sits in the *visible* strip above the place sheet instead of
        // being hidden behind it. Padding is the map's single source of truth, so
        // every camera move below respects it. Reset to 0 when no sheet is up.
        if (cameraBottomInsetPx != lastInsetPx) {
            lastInsetPx = cameraBottomInsetPx
            map.setPadding(0, 0, 0, cameraBottomInsetPx)
            lastCameraTarget = null // re-frame the current target against the new inset
        }
        when {
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
                // This `update` block re-runs on *every* recomposition (several a
                // second during nav), and each animateCamera restarts a fresh ease —
                // stacking them is what made the follow lag and feel "wacky". Only
                // re-point when the fix actually moved (>4 m) or turned (>2°); GPS
                // jitter at a standstill is otherwise an endless shimmer. A snappier
                // ease (550 ms) then keeps the dot under the camera instead of trailing.
                // Follow the SAME smoothed point the puck is drawn at (monotonic + eased),
                // not the raw snapped fix — so the camera and puck move as one and the map
                // can't lurch to a far spot when nearest-point is briefly ambiguous.
                val loc = (if (navPuck.engaged) navPuck.drawn else null) ?: displayLoc ?: myLocation
                val brg = (if (navPuck.engaged && !navPuck.displayBearing.isNaN()) navPuck.displayBearing else null)
                    ?: displayBearing ?: lastNavBearing ?: 0f
                val moved = lastNavTarget?.let { it.distanceTo(loc) > 4.0 } ?: true
                val turned = lastNavBearing?.let { kotlin.math.abs(((brg - it + 540f) % 360f) - 180f) > 2f } ?: true
                if (moved || turned) {
                    lastNavTarget = loc
                    lastNavBearing = brg
                    // Speed-adaptive zoom (Google-style): pull back on the highway to
                    // see further ahead, tighten up on slow city streets.
                    val speed = mySpeed ?: 0f // m/s
                    val zoom = when {
                        speed > 25f -> 15.0  // ~56+ mph — freeway
                        speed > 12f -> 16.0  // ~27+ mph — arterial
                        speed > 4f -> 16.8   // city street
                        else -> 17.3         // crawling / stopped
                    }
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(MLLatLng(loc.lat, loc.lng))
                                .zoom(zoom)
                                .tilt(55.0)
                                .bearing(brg.toDouble())
                                .build(),
                        ),
                        550,
                    )
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
                if (markers.size == 1) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            MLLatLng(markers[0].location.lat, markers[0].location.lng), 15.0,
                        ),
                    )
                } else {
                    val builder = MLLatLngBounds.Builder()
                    markers.forEach { builder.include(MLLatLng(it.location.lat, it.location.lng)) }
                    runCatching {
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 160), 700)
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
                setMinZoom(17f)
                setProperties(
                    PropertyFactory.textField(Expression.get("housenumber")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textColor("#8a8a8a"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(1f),
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
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstLabel != null) style.addLayerBelow(routeLine, firstLabel) else style.addLayer(routeLine)
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

private fun applyLight(style: Style) {
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
    style.getLayer("building")?.setMinZoom(14f)
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#dde1e7"),
        PropertyFactory.fillExtrusionOpacity(0.95f),
    )
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
private fun applyDark(style: Style) {
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
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#323f54"),
        PropertyFactory.fillExtrusionOpacity(0.95f),
    )
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

/** Snap [me] onto the nearest point of the nav route for display — the snapped point plus
 *  that segment's heading — so the puck rides the road instead of wobbling with raw GPS.
 *  Returns null (→ show the RAW fix) when we're not genuinely following the route, so a
 *  missed exit / off-road shows reality rather than gluing the arrow to where it "should"
 *  be: either the nearest point is farther than [maxM] (≈ one road width + GPS error), OR
 *  the device heading doesn't match the road's (you've turned off it). No GPS heading
 *  (e.g. stopped) falls back to distance only. */
private fun snapToRoute(me: LatLng, gpsBearing: Float?, polyline: List<LatLng>, maxM: Double = 22.0): Pair<LatLng, Float>? {
    if (polyline.size < 2) return null
    var bestD = Double.MAX_VALUE
    var bestPoint: LatLng? = null
    var bestA = polyline[0]
    var bestB = polyline[1]
    for (i in 1 until polyline.size) {
        val a = polyline[i - 1]
        val b = polyline[i]
        val (proj, _) = projectOnSegment(me, a, b)
        val d = me.distanceTo(proj)
        if (d < bestD) { bestD = d; bestPoint = proj; bestA = a; bestB = b }
    }
    val pt = bestPoint ?: return null
    if (bestD > maxM) return null
    val routeBearing = bearingDeg(bestA, bestB)
    // Heading gate: if the device is clearly NOT going the road's way, don't snap — let
    // the real position show (then the off-route reroute kicks in), Google-style.
    if (gpsBearing != null && angleDelta(gpsBearing, routeBearing) > 55f) return null
    return pt to routeBearing
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
 *  "nearest point". Off-route it falls back to [raw] (honesty — see [snapToRoute]). */
private class NavPuck {
    var engaged = false           // currently following the route (snapped)
    var progressM = 0.0           // displayed metres along the route (what's drawn)
    var targetM = 0.0             // latest fix's metres along the route (where we're heading)
    var targetAtMs = 0L           // elapsedRealtime() the target was set — for dead reckoning
    var speed = 0.0               // m/s, for the inter-fix dead reckoning
    var displayBearing = Float.NaN // smoothed heading actually drawn (NaN = not yet seeded)
    var drawn: LatLng? = null     // last point actually drawn — the camera follows THIS, not the raw fix
    var raw: LatLng? = null       // off-route fallback position
    var rawBearing: Float? = null
}

/** Cumulative along-route distance (m) at each polyline vertex (cum[0] = 0). */
private fun cumLengths(poly: List<LatLng>): DoubleArray {
    val cum = DoubleArray(poly.size)
    for (i in 1 until poly.size) cum[i] = cum[i - 1] + poly[i - 1].distanceTo(poly[i])
    return cum
}

/** Metres along the route to the nearest projection of [p]. */
private fun nearestMeters(poly: List<LatLng>, cum: DoubleArray, p: LatLng): Double {
    if (poly.size < 2) return 0.0
    var bestD = Double.MAX_VALUE
    var bestM = 0.0
    for (i in 1 until poly.size) {
        val a = poly[i - 1]
        val b = poly[i]
        val (proj, t) = projectOnSegment(p, a, b)
        val d = p.distanceTo(proj)
        if (d < bestD) { bestD = d; bestM = cum[i - 1] + t * a.distanceTo(b) }
    }
    return bestM
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
    fun colorAt(f: Float): Int {
        // Only grey the driven part once actually moving (p > 0); a static preview
        // (p == 0) must not paint a grey nub at the very start of the line.
        if (p > 0f && f <= p) return ROUTE_DRIVEN
        for ((s, e, lvl) in spans) if (f >= s && f < e) return trafficLevelColor(lvl)
        return freeflow
    }
    val n = 256 // fine sampling; a stop is emitted only where the colour actually changes
    val base = colorAt(0f)
    val stops = ArrayList<Expression.Stop>()
    var prev = base
    for (i in 1..n) {
        val f = i / n.toFloat()
        val c = colorAt(f)
        if (c != prev) { stops.add(Expression.stop(f, Expression.color(c))); prev = c }
    }
    return Expression.step(Expression.lineProgress(), Expression.color(base), *stops.toTypedArray())
}

private fun applyData(
    style: Style,
    route: List<LatLng>,
    routeColor: String,
    trafficSpans: List<Triple<Float, Float, Int>>,
    alternates: List<Pair<Int, List<LatLng>>>,
    altColor: String,
    markers: List<MapMarker>,
    me: LatLng?,
    bearing: Float?,
    meStale: Boolean,
    preview: LatLng?,
    routeProgress: Float,
    navMode: Boolean,
) {
    val routeFc = if (route.size >= 2) {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.lng, it.lat) })),
        )
    } else {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }
    style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeFc)
    // Route line, Google-style: the part already DRIVEN greys out behind the vehicle;
    // the part AHEAD shows live traffic PER SEGMENT — a free-flow base with amber/red
    // bands over the congested stretches (from [trafficSpans]) — or, with no live
    // data, a single congestion tint. A line-progress gradient (routeProgress =
    // fraction travelled, 0 when not navigating → nothing greyed).
    val routeInt = runCatching { android.graphics.Color.parseColor(routeColor) }
        .getOrDefault(ROUTE_FREEFLOW)
    // 0 when not navigating (no driven-grey); only floor to a visible sliver once moving.
    val p = if (routeProgress <= 0f) 0f else routeProgress.coerceIn(0.001f, 0.998f)
    style.getLayer(ROUTE_LAYER)?.setProperties(
        PropertyFactory.lineGradient(routeGradient(p, routeInt, trafficSpans)),
    )

    val altFc = FeatureCollection.fromFeatures(
        alternates.filter { it.second.size >= 2 }.map { (idx, line) ->
            Feature.fromGeometry(
                LineString.fromLngLats(line.map { Point.fromLngLat(it.lng, it.lat) }),
            ).apply { addNumberProperty(ALT_INDEX_PROP, idx) }
        },
    )
    style.getSourceAs<GeoJsonSource>(ALT_ROUTE_SRC)?.setGeoJson(altFc)
    style.getLayer(ALT_ROUTE_LAYER)?.setProperties(PropertyFactory.lineColor(altColor))

    val markersFc = FeatureCollection.fromFeatures(
        markers.mapIndexed { i, m ->
            Feature.fromGeometry(Point.fromLngLat(m.location.lng, m.location.lat)).apply {
                addStringProperty("name", m.name)
                addNumberProperty(MARKER_INDEX_PROP, i)
            }
        },
    )
    style.getSourceAs<GeoJsonSource>(MARKERS_SRC)?.setGeoJson(markersFc)

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
