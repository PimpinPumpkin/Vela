package app.carto.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.carto.core.model.LatLng
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MLLatLng

private const val ROUTE_SRC = "carto-route-src"
private const val ROUTE_LAYER = "carto-route"
private const val MARKERS_SRC = "carto-markers-src"
private const val MARKERS_LAYER = "carto-markers"
private const val ME_SRC = "carto-me-src"
private const val ME_LAYER = "carto-me"

/**
 * MapLibre Native wrapped for Compose. The route, search markers and the
 * "blue dot" are plain GeoJSON sources we update directly — deliberately not
 * MapLibre's LocationComponent, whose location-engine handshake is overkill
 * when [LocationProvider][app.carto.core.location.LocationProvider] already owns
 * the fix stream.
 */
@Composable
fun CartoMapView(
    styleUri: String,
    myLocation: LatLng?,
    cameraTarget: LatLng?,
    routePolyline: List<LatLng>,
    markers: List<LatLng>,
    followMe: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // MapLibre must be initialised before a MapView is inflated.
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var appliedStyleUri by remember { mutableStateOf<String?>(null) }
    var lastCameraTarget by remember { mutableStateOf<LatLng?>(null) }

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
                mapRef = map
            }
        }
        val map = mapRef ?: return@AndroidView

        if (appliedStyleUri != styleUri) {
            appliedStyleUri = styleUri
            map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                styleRef = style
                ensureLayers(style)
                applyData(style, routePolyline, markers, myLocation)
            }
        } else {
            styleRef?.let { applyData(it, routePolyline, markers, myLocation) }
        }

        val target = if (followMe) myLocation else (cameraTarget ?: myLocation)
        if (target != null && target != lastCameraTarget) {
            lastCameraTarget = target
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    MLLatLng(target.lat, target.lng),
                    if (followMe) 16.5 else 14.5,
                ),
            )
        }
    }
}

private fun ensureLayers(style: Style) {
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
    if (style.getSource(MARKERS_SRC) == null) {
        style.addSource(GeoJsonSource(MARKERS_SRC))
        style.addLayer(
            CircleLayer(MARKERS_LAYER, MARKERS_SRC).withProperties(
                PropertyFactory.circleColor("#14857A"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
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
    }
}

private fun applyData(
    style: Style,
    route: List<LatLng>,
    markers: List<LatLng>,
    me: LatLng?,
) {
    // Each source gets a uniform FeatureCollection — mixing geometry types
    // across an if/else lands on the GeoJson supertype, which setGeoJson has no
    // overload for.
    val routeFc = if (route.size >= 2) {
        FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.lng, it.lat) })),
        )
    } else {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }
    style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeFc)

    val markersFc = FeatureCollection.fromFeatures(
        markers.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) },
    )
    style.getSourceAs<GeoJsonSource>(MARKERS_SRC)?.setGeoJson(markersFc)

    val meFc = if (me != null) {
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(me.lng, me.lat)))
    } else {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    }
    style.getSourceAs<GeoJsonSource>(ME_SRC)?.setGeoJson(meFc)
}
