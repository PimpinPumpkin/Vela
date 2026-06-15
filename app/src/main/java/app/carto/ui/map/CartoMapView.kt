package app.carto.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.carto.core.model.LatLng
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

private const val ROUTE_SRC = "carto-route-src"
private const val ROUTE_LAYER = "carto-route"
private const val MARKERS_SRC = "carto-markers-src"
private const val MARKERS_LAYER = "carto-markers"
private const val ME_SRC = "carto-me-src"
private const val ME_LAYER = "carto-me"
private const val ME_ARROW_LAYER = "carto-me-arrow"
private const val ME_ARROW_IMG = "carto-arrow"

/**
 * MapLibre wrapped for Compose. Three camera behaviours:
 *  - [navMode]: heading-up, tilted, close follow (drives like a nav app);
 *  - a fresh route preview: fit the whole route to the screen once;
 *  - otherwise: gentle north-up follow of the camera target.
 * The location dot also shows a heading arrow when a GPS bearing is available.
 */
@Composable
fun CartoMapView(
    styleUri: String,
    myLocation: LatLng?,
    myBearing: Float?,
    cameraTarget: LatLng?,
    routePolyline: List<LatLng>,
    markers: List<LatLng>,
    navMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var appliedStyleUri by remember { mutableStateOf<String?>(null) }
    var lastCameraTarget by remember { mutableStateOf<LatLng?>(null) }
    var lastFittedRouteKey by remember { mutableStateOf<Int?>(null) }

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
                applyData(style, routePolyline, markers, myLocation, myBearing)
            }
        } else {
            styleRef?.let { applyData(it, routePolyline, markers, myLocation, myBearing) }
        }

        when {
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
}

private fun applyData(
    style: Style,
    route: List<LatLng>,
    markers: List<LatLng>,
    me: LatLng?,
    bearing: Float?,
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
        markers.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) },
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
