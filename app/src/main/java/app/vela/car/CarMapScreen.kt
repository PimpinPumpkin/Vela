package app.vela.car

import android.app.Presentation
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import app.vela.R
import app.vela.core.data.tiles.MapStyle
import app.vela.core.nav.NavSession
import app.vela.ui.Units
import app.vela.ui.map.applyDark
import app.vela.ui.map.applyLight
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.ln
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng as MLLatLng

/**
 * The one car screen: the live Vela map on the car display, plus the current
 * maneuver card while navigating. State is the same [NavSession] singleton the
 * phone drives - start a route on the phone and the car shows it; the phone
 * keeps feeding location and speaking (audio routes to the car on its own).
 *
 * Rendering: the Car App Library gives templated apps a raw [SurfaceCallback]
 * surface for the map. MapLibre wants a real View, so the renderer wraps the
 * surface in a [VirtualDisplay] and shows a [Presentation] holding a plain
 * [MapView] on it - the standard trick for surface-drawn car maps. Pan/zoom
 * gestures from the car screen arrive as [SurfaceCallback.onScroll]/[onScale]
 * and move the MapLibre camera by hand.
 */
class CarMapScreen(
    carContext: CarContext,
    private val navSession: NavSession,
) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarMapRenderer(carContext)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        // Mirror nav state onto the car map + template. distinctUntilChanged via the
        // snapshot key so per-fix updates only redraw what changed (the template call
        // is cheap, but be polite to the host's update pipeline anyway).
        var lastKey: List<Any?>? = null
        navSession.state
            .onEach { s ->
                renderer.setNav(s.navigating, s.route?.polyline)
                val key = listOf(s.navigating, s.maneuverText, (s.nav.distanceToNextManeuver / 20).toInt())
                if (key != lastKey) { lastKey = key; invalidate() }
            }
            .launchIn(lifecycle.coroutineScope)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        renderer.teardown()
    }

    override fun onGetTemplate(): Template {
        val s = navSession.state.value
        val builder = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.mapscreen_recenter))
                            .setOnClickListener { renderer.recenter() }
                            .build(),
                    )
                    .addAction(Action.Builder().setTitle("+").setOnClickListener { renderer.zoomBy(1.0) }.build())
                    .addAction(Action.Builder().setTitle("−").setOnClickListener { renderer.zoomBy(-1.0) }.build())
                    .build(),
            )
            .setPanModeListener { panning -> if (panning) renderer.stopFollowing() }
        if (s.navigating && s.maneuverText.isNotBlank()) {
            builder.setNavigationInfo(
                RoutingInfo.Builder()
                    .setCurrentStep(
                        Step.Builder(s.maneuverText).build(),
                        carDistance(s.nav.distanceToNextManeuver),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    /** Meters → a car-host [Distance] in the user's display units (Google-style rounding). */
    private fun carDistance(meters: Double): Distance = if (Units.imperial.value) {
        val feet = meters * 3.28084
        if (feet < 1000) {
            val stepped = if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50
            Distance.create(stepped.toDouble(), Distance.UNIT_FEET)
        } else {
            Distance.create(meters / 1609.344, Distance.UNIT_MILES)
        }
    } else {
        if (meters < 1000) {
            Distance.create(maxOf(10.0, (meters / 10).roundToInt() * 10.0), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
        }
    }
}

/**
 * Owns the car surface: virtual display + Presentation + MapView, the location
 * puck (its own AOSP LocationManager listener - the car screen must work even
 * when the phone UI is closed), the route line, and camera follow.
 */
private class CarMapRenderer(private val carContext: CarContext) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var follow = true
    private var framedOnce = false
    private var lastFix: Location? = null
    private var navigating = false
    private var routePts: List<app.vela.core.model.LatLng>? = null

    private val locListener = LocationListener { loc ->
        lastFix = loc
        applyFix(loc, animate = true)
    }

    // ---- SurfaceCallback ----

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        MapLibre.getInstance(carContext.applicationContext)
        val dm = carContext.getSystemService<DisplayManager>() ?: return
        val vd = dm.createVirtualDisplay(
            "vela-car-map",
            surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        virtualDisplay = vd
        val pres = Presentation(carContext, vd.display)
        val mv = MapView(pres.context)
        pres.setContentView(mv)
        pres.show()
        presentation = pres
        mapView = mv
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        mv.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(MapStyle.DEFAULT.uri)) { st ->
                style = st
                // Same runtime recolour as the phone map, keyed to the CAR's day/night.
                runCatching { if (carContext.isDarkMode) applyDark(st) else applyLight(st) }
                ensureOwnLayers(st)
                pushRoute()
                lastFix?.let { applyFix(it, animate = false) }
            }
        }
        startLocation()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = teardown()

    override fun onScroll(distanceX: Float, distanceY: Float) {
        follow = false
        val m = map ?: return
        val proj = m.projection
        val center = proj.toScreenLocation(m.cameraPosition.target ?: return)
        val moved = proj.fromScreenLocation(PointF(center.x + distanceX, center.y + distanceY))
        m.moveCamera(CameraUpdateFactory.newLatLng(moved))
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        map?.moveCamera(CameraUpdateFactory.zoomBy(ln(scaleFactor.toDouble()) / ln(2.0)))
    }

    override fun onFling(velocityX: Float, velocityY: Float) { /* scroll deltas cover it */ }

    // ---- driven by the screen ----

    fun setNav(nav: Boolean, polyline: List<app.vela.core.model.LatLng>?) {
        val routeChanged = polyline !== routePts
        navigating = nav
        routePts = polyline
        if (routeChanged) pushRoute()
        if (nav) follow = true
    }

    fun recenter() {
        follow = true
        lastFix?.let { applyFix(it, animate = true) }
    }

    fun stopFollowing() { follow = false }

    fun zoomBy(delta: Double) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(delta))
    }

    fun teardown() {
        stopLocation()
        map = null
        style = null
        mapView?.let { runCatching { it.onPause(); it.onStop(); it.onDestroy() } }
        mapView = null
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
    }

    // ---- internals ----

    private fun ensureOwnLayers(st: Style) {
        st.addSource(GeoJsonSource(ROUTE_SOURCE))
        st.addSource(GeoJsonSource(PUCK_SOURCE))
        st.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#4285F4"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        st.addLayer(
            CircleLayer(PUCK_HALO_LAYER, PUCK_SOURCE).withProperties(
                PropertyFactory.circleRadius(16f),
                PropertyFactory.circleColor("#4285F4"),
                PropertyFactory.circleOpacity(0.2f),
            ),
        )
        st.addLayer(
            CircleLayer(PUCK_LAYER, PUCK_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor("#4285F4"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
    }

    private fun pushRoute() {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        val pts = routePts
        if (pts.isNullOrEmpty()) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            src.setGeoJson(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) }))
        }
    }

    private fun applyFix(loc: Location, animate: Boolean) {
        val st = style ?: return
        st.getSourceAs<GeoJsonSource>(PUCK_SOURCE)?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
        if (!follow) return
        val m = map ?: return
        val target = MLLatLng(loc.latitude, loc.longitude)
        val cam = if (navigating) {
            CameraPosition.Builder()
                .target(target)
                .zoom(16.5)
                .tilt(45.0)
                .bearing(if (loc.hasBearing() && loc.speed > 1f) loc.bearing.toDouble() else m.cameraPosition.bearing)
                .build()
        } else {
            val zoom = if (framedOnce) m.cameraPosition.zoom else 15.0
            framedOnce = true
            CameraPosition.Builder().target(target).zoom(zoom).tilt(0.0).bearing(0.0).build()
        }
        if (animate) m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 900)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    private fun startLocation() {
        val lm = carContext.getSystemService<LocationManager>() ?: return
        // Same AOSP-only rule as the phone (never Fused). Permission was granted in the
        // phone app; if it somehow isn't, the map just shows without a puck.
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastFix = it }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
        }
    }

    private fun stopLocation() {
        runCatching { carContext.getSystemService<LocationManager>()?.removeUpdates(locListener) }
    }

    private companion object {
        const val ROUTE_SOURCE = "vela-car-route-src"
        const val ROUTE_LAYER = "vela-car-route"
        const val PUCK_SOURCE = "vela-car-loc-src"
        const val PUCK_HALO_LAYER = "vela-car-loc-halo"
        const val PUCK_LAYER = "vela-car-loc"
    }
}
