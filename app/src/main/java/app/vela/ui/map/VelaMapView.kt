package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vela.core.model.LatLng
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MLLatLng
import org.maplibre.android.geometry.LatLngBounds as MLLatLngBounds

private const val ROUTE_SRC = "vela-route-src"
private const val ROUTE_LAYER = "vela-route"
private const val MARKERS_SRC = "vela-markers-src"
private const val MARKERS_LAYER = "vela-markers"
private const val PIN_IMG = "vela-pin"
private const val MARKER_INDEX_PROP = "vela-marker-index"
private const val ME_SRC = "vela-me-src"
private const val ME_LAYER = "vela-me"
private const val ME_ARROW_LAYER = "vela-me-arrow"
private const val ME_ARROW_IMG = "vela-arrow"
private const val PREVIEW_SRC = "vela-preview-src"
private const val PREVIEW_LAYER = "vela-preview"

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
    cameraTarget: LatLng?,
    routePolyline: List<LatLng>,
    markers: List<MapMarker>,
    frameMarkers: Boolean,
    navMode: Boolean,
    darkTheme: Boolean,
    applyKeylessTheme: Boolean,
    previewTarget: LatLng?,
    onPoiTap: (name: String, location: LatLng) -> Unit,
    onMarkerTap: (index: Int) -> Unit,
    onCameraIdle: (center: LatLng) -> Unit,
    onMapLongPress: (location: LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val poiTap = rememberUpdatedState(onPoiTap)
    val markerTap = rememberUpdatedState(onMarkerTap)
    val cameraIdle = rememberUpdatedState(onCameraIdle)
    val longPress = rememberUpdatedState(onMapLongPress)
    val gestureMove = remember { booleanArrayOf(false) }
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var appliedStyleKey by remember { mutableStateOf<String?>(null) }
    var lastCameraTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastFittedRouteKey by remember { mutableStateOf<Int?>(null) }
    var lastFittedMarkersKey by remember { mutableStateOf<Int?>(null) }
    var lastPreviewTarget by remember { mutableStateOf<LatLng?>(null) }

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
                // Tap a labelled POI on the map to open it.
                map.addOnMapClickListener { tapped ->
                    val p = map.projection.toScreenLocation(tapped)
                    val r = 22f
                    val feats = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r))
                    // Our own search-result pins take priority over basemap POI labels.
                    val pin = feats.firstOrNull { it.hasProperty(MARKER_INDEX_PROP) }
                    if (pin != null) {
                        markerTap.value(pin.getNumberProperty(MARKER_INDEX_PROP).toInt())
                        return@addOnMapClickListener true
                    }
                    val hit = feats.firstOrNull { it.geometry() is Point && it.hasProperty("name") }
                    if (hit != null) {
                        val pt = hit.geometry() as Point
                        poiTap.value(hit.getStringProperty("name"), LatLng(pt.latitude(), pt.longitude()))
                        true
                    } else {
                        false
                    }
                }
                // Only flag camera settling when the user dragged the map (not
                // our own programmatic framing) → drives "Search this area".
                map.addOnCameraMoveStartedListener { reason ->
                    gestureMove[0] = reason ==
                        MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                }
                map.addOnCameraIdleListener {
                    if (gestureMove[0]) {
                        gestureMove[0] = false
                        map.cameraPosition.target?.let { t ->
                            cameraIdle.value(LatLng(t.latitude, t.longitude))
                        }
                    }
                }
                // Press-and-hold anywhere → drop a pin and reverse-geocode it.
                map.addOnMapLongClickListener { p ->
                    longPress.value(LatLng(p.latitude, p.longitude))
                    true
                }
                mapRef = map
            }
        }
        val map = mapRef ?: return@AndroidView

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
                applyData(style, routePolyline, markers, myLocation, myBearing, previewTarget)
            }
        } else {
            styleRef?.let { applyData(it, routePolyline, markers, myLocation, myBearing, previewTarget) }
        }

        if (previewTarget == null) lastPreviewTarget = null
        when {
            // Previewing a step takes over the camera (and holds, suppressing
            // nav-follow) so you can look ahead at where you'd turn.
            previewTarget != null -> {
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

            navMode && myLocation != null -> {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(MLLatLng(myLocation.lat, myLocation.lng))
                            .zoom(17.0)
                            .tilt(55.0)
                            .bearing((myBearing ?: 0f).toDouble())
                            .build(),
                    ),
                    900,
                )
            }

            routePolyline.size >= 2 && routePolyline.hashCode() != lastFittedRouteKey -> {
                lastFittedRouteKey = routePolyline.hashCode()
                val builder = MLLatLngBounds.Builder()
                routePolyline.forEach { builder.include(MLLatLng(it.lat, it.lng)) }
                runCatching {
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 140), 800)
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
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(MLLatLng(target.lat, target.lng), 14.5),
                    )
                }
            }
        }
    }
}

private fun ensureLayers(style: Style) {
    if (style.getImage(ME_ARROW_IMG) == null) style.addImage(ME_ARROW_IMG, arrowBitmap())

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
        style.addSource(GeoJsonSource(ROUTE_SRC))
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
                PropertyFactory.lineColor("#1F6FEB"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
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
        style.addLayer(
            CircleLayer(ME_LAYER, ME_SRC).withProperties(
                PropertyFactory.circleColor("#1F6FEB"),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            SymbolLayer(ME_ARROW_LAYER, ME_SRC).withProperties(
                PropertyFactory.iconImage(ME_ARROW_IMG),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
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
 * Recolour the OpenFreeMap (OpenMapTiles) style for a cleaner look and a proper
 * dark theme that follows the system. We reload the style when the theme flips
 * (see styleKey), so each pass starts from Liberty's defaults — no need to undo.
 * No-ops on non-OpenMapTiles styles (e.g. the MapLibre demo basemap). Keyless.
 */
private fun applyMapTheme(style: Style, dark: Boolean) {
    if (style.getSource("openmaptiles") == null) return
    if (dark) applyDark(style) else applyLight(style)
}

private fun applyLight(style: Style) {
    // A cool light-grey land so the white roads actually read (Liberty's
    // near-white background hid them — Google uses grey land for the same reason).
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#eaecef"))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#a7cfeb"))
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#c8e6b9"), PropertyFactory.fillOpacity(0.85f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#c4e2a8"), PropertyFactory.fillOpacity(0.5f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#aedb91"), PropertyFactory.fillOpacity(0.55f))
    style.getLayer("building")?.setProperties(PropertyFactory.fillColor("#e2ddd2"))
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#e2ddd2"),
        PropertyFactory.fillExtrusionOpacity(0.92f),
    )
    // White roads with a clearly-visible grey casing.
    listOf("road_motorway", "road_trunk_primary", "road_secondary_tertiary", "road_minor").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#ffffff"))
    }
    listOf(
        "road_motorway_casing", "road_trunk_primary_casing",
        "road_secondary_tertiary_casing", "road_minor_casing",
    ).forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#c3c7cd"))
    }
}

/** Google-Maps-dark-ish palette applied over the OpenMapTiles layers. */
private fun applyDark(style: Style) {
    style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#242f3e"))
    style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#17263c"))
    style.getLayer("waterway_river")?.setProperties(PropertyFactory.lineColor("#17263c"))
    style.getLayer("landuse_residential")?.setProperties(
        PropertyFactory.fillColor("#2a3447"),
        PropertyFactory.fillOpacity(0.4f),
    )
    style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#1c3326"), PropertyFactory.fillOpacity(0.7f))
    style.getLayer("landcover_grass")?.setProperties(PropertyFactory.fillColor("#1c3326"), PropertyFactory.fillOpacity(0.5f))
    style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#1a3023"), PropertyFactory.fillOpacity(0.6f))
    listOf("road_minor", "road_secondary_tertiary", "road_link", "road_service_track").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#49536a"))
    }
    style.getLayer("road_trunk_primary")?.setProperties(PropertyFactory.lineColor("#5e6a85"))
    style.getLayer("road_motorway")?.setProperties(PropertyFactory.lineColor("#6f7a96"))
    listOf("road_motorway_casing", "road_trunk_primary_casing", "road_secondary_tertiary_casing").forEach {
        style.getLayer(it)?.setProperties(PropertyFactory.lineColor("#1b212c"))
    }
    style.getLayer("building")?.setProperties(PropertyFactory.fillColor("#2b3647"))
    style.getLayer("building-3d")?.setProperties(
        PropertyFactory.fillExtrusionColor("#2b3647"),
        PropertyFactory.fillExtrusionOpacity(0.9f),
    )
    // Light labels with a dark halo so place names read on the dark basemap.
    style.layers.forEach { layer ->
        if (layer is SymbolLayer) {
            layer.setProperties(
                PropertyFactory.textColor("#c3cad6"),
                PropertyFactory.textHaloColor("#1a2230"),
                PropertyFactory.textHaloWidth(1.1f),
            )
        }
    }
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

private fun applyData(
    style: Style,
    route: List<LatLng>,
    markers: List<MapMarker>,
    me: LatLng?,
    bearing: Float?,
    preview: LatLng?,
) {
    val routeFc = if (route.size >= 2) {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.lng, it.lat) })),
        )
    } else {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }
    style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeFc)

    val markersFc = FeatureCollection.fromFeatures(
        markers.mapIndexed { i, m ->
            Feature.fromGeometry(Point.fromLngLat(m.location.lng, m.location.lat)).apply {
                addStringProperty("name", m.name)
                addNumberProperty(MARKER_INDEX_PROP, i)
            }
        },
    )
    style.getSourceAs<GeoJsonSource>(MARKERS_SRC)?.setGeoJson(markersFc)

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

    style.getLayer(ME_ARROW_LAYER)?.setProperties(
        PropertyFactory.visibility(if (me != null && bearing != null) Property.VISIBLE else Property.NONE),
    )

    val previewFc = if (preview != null) {
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(preview.lng, preview.lat)))
    } else {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }
    style.getSourceAs<GeoJsonSource>(PREVIEW_SRC)?.setGeoJson(previewFc)
}

/** A small upward-pointing arrow (north = 0°) for the heading indicator. */
private fun arrowBitmap(): Bitmap {
    val size = 48
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(size / 2f, 5f)
        lineTo(size * 0.78f, size * 0.82f)
        lineTo(size / 2f, size * 0.64f)
        lineTo(size * 0.22f, size * 0.82f)
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F6FEB.toInt() })
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
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
