package app.vela.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import app.vela.core.data.RouteEngine
import app.vela.core.data.tiles.MapStyle
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import app.vela.core.model.Route
import app.vela.core.model.bearingTo
import app.vela.core.model.destinationPoint
import app.vela.core.nav.NavSession
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng as MLLatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Renders the REAL styled Vela map (OpenFreeMap Liberty vector — streets, labels, POIs) onto an
 * Android Auto template surface via MapLibre's stable public [MapSnapshotter] (off-screen map →
 * Bitmap), with the route + puck overlaid. Not 60 fps (a snapshot is ~100–300 ms), but for a nav
 * map that follows position it's a genuine moving map.
 *
 * Two modes, driven by [NavSession.navigating]:
 *  - **Nav** — heading-up, camera looks AHEAD of the puck (puck sits in the lower third so you see
 *    the road you're driving into), zoom tightens as you slow, the route line is drawn colored by
 *    live traffic, and a current-speed badge shows.
 *  - **Browse** — north-up, centered on you, NO route (so a finished trip's line doesn't linger).
 *
 * At night the snapshot is tinted dark (a real dark vector style via the snapshotter is a follow-up).
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val locationProvider: LocationProvider,
    private val navSession: NavSession,
    private val routeEngine: RouteEngine? = null,
) : SurfaceCallback {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var surface: android.view.Surface? = null
    private var width = 0
    private var height = 0

    private var snapshotter: MapSnapshotter? = null
    private var snapWidth = 0
    private var snapHeight = 0
    private var lastSnapshot: MapSnapshot? = null
    private var rendering = false
    private var dirty = false

    // Camera / motion state. `puck`/`bearing` are the DISPLAYED (smoothed) values the draw + camera
    // code reads; `targetPuck`/`targetBearing` are the latest GPS truth. A steady ticker glides the
    // displayed values toward the targets between the ~1 Hz fixes, so the map no longer lurches once
    // a second (the "constantly moving around / choppy" report) — it dead-reckons like a real nav map.
    @Volatile private var puck: LatLng? = null
    @Volatile private var targetPuck: LatLng? = null
    @Volatile private var speedMps: Double = 0.0
    @Volatile private var speedLimitKmh: Double? = null
    private var center: LatLng? = null
    private var zoom = 16.5
    private var bearing = 0.0
    private var targetBearing = 0.0
    private var following = true // pan turns this off in browse
    private var tickerJob: Job? = null
    // The host's VISIBLE area of the surface (the templates cover the rest with cards); the puck is
    // framed inside it, not inside the raw surface (user 2026-09-21: the arrow hung off the edge).
    @Volatile private var visible: Rect? = null
    // The phone's between-fix glide (ui/map/FollowEstimator): integrate speed along the course and
    // fold each fix in as a correction over ~0.9 s, instead of easing 28% of the gap per tick
    // toward a point that jumps once a second (the "camera snapping every few seconds").
    private val estimator = app.vela.ui.map.FollowEstimator()
    private var lastTickMs = 0L
    private var zoomTarget = 16.5 // the speed-tiered nav zoom eases toward this; it used to step
    @Volatile private var themed = false // Vela's palette applied to the snapshotter's style
    // Which look the palette was applied FOR. The car flips day/night on its own (the head unit's
    // light sensor or clock), and a palette applied once at style load stayed light through a
    // drive into the evening (user 2026-09-22, stock Pixel 9); requestRender re-applies it when
    // carContext.isDarkMode no longer matches.
    @Volatile private var themedNight: Boolean? = null
    // The Vela puck (the same bitmap the phone draws, ui/map/navPuckBitmap), scaled to the screen
    // once per size; the car used to draw its own green chevron (user: "not our pretty puck").
    private var puckBitmap: android.graphics.Bitmap? = null
    private var puckBitmapPx = 0

    // Preview mode: draw this route framed (no puck-follow) — used by the route-preview screen.
    @Volatile private var previewRoute: Route? = null
    @Volatile private var lastPanMs = 0L // last user pan/zoom; auto-recenter kicks in after RECENTER_MS

    private companion object {
        const val RECENTER_MS = 6000L // auto-recenter this long after a pan
        const val TICK_MS = 70L       // render-loop cadence (snapshots gate the real fps below this)
        const val BEARING_EASE = 0.22
        const val ZOOM_EASE = 0.06    // per tick, so a speed tier change glides over a second or so
        const val PUCK_DOWN = 0.72    // the puck sits this far down the VISIBLE area while following in nav
        const val ATTRIBUTION = "\u00a9 OpenStreetMap" // the phone's map_osm_attribution, verbatim
        const val STOPPED_MPS = 1.0   // below this, treat as parked: don't trust GPS-course noise
        const val SNAP_MAX_M = 40.0   // map-match to the route only within this distance (else off-route)
    }

    private fun navigating() = navSession.state.value.navigating

    /** Follow the puck (browse north-up / nav heading-up) — the landing + active-nav screens. */
    fun follow() {
        previewRoute = null
        overview = false
        following = true
        requestRender()
    }

    // OVERVIEW: the whole remaining route framed north-up; the auto-recenter leaves it alone until
    // the driver taps it off (or recenters). The phone has the same toggle.
    @Volatile private var overview = false
    fun toggleOverview() {
        overview = !overview
        following = !overview
        if (!overview) lastPanMs = 0L
        requestRender()
    }

    /** Step the zoom (map-control buttons); pins follow off briefly so the change is visible. */
    fun zoomBy(delta: Double) {
        zoom = (zoom + delta).coerceIn(2.0, 20.0)
        lastPanMs = android.os.SystemClock.uptimeMillis()
        following = false
        requestRender()
    }

    /** Frame [route] on the surface for the route-preview screen (no live follow). */
    fun showPreview(route: Route?) {
        previewRoute = route
        following = false
        frameRoute(route)
        requestRender()
    }

    private val drivenPaint = strokePaint("#7b8494", 14f)
    private val bgPaint = Paint().apply { color = Color.parseColor("#0f1420") }
    // Traffic colors (match the phone route line): free-flow blue → amber → red.
    private val trafficPaints = mapOf(
        0 to strokePaint("#4c8dff", 15f), // free-flowing
        1 to strokePaint("#f9a825", 15f), // moderate
        2 to strokePaint("#e53935", 15f), // heavy
        3 to strokePaint("#8e1414", 15f), // severe
    )
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#cc1b1f2b") }
    private val badgeNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 42f; isFakeBoldText = true
    }
    private val badgeUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#b9c0cc"); textAlign = Paint.Align.CENTER; textSize = 18f
    }
    private val limitDisc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val limitRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#d32f2f"); style = Paint.Style.STROKE; strokeWidth = 7f
    }
    private val limitNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111"); textAlign = Paint.Align.CENTER; textSize = 36f; isFakeBoldText = true
    }

    // Night tint: darken + slight blue base so the map isn't a blinding white slab after dark.
    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            0.34f, 0f, 0f, 0f, 6f,
            0f, 0.34f, 0f, 0f, 10f,
            0f, 0f, 0.40f, 0f, 24f,
            0f, 0f, 0f, 1f, 0f,
        )),
    )
    private val nightBitmapPaint = Paint().apply { colorFilter = nightFilter }
    private val dayBitmapPaint = Paint()

    private fun strokePaint(hex: String, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(hex); style = Paint.Style.STROKE; strokeWidth = w
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // The HOST's day/night signal, not a wall-clock guess (a fixed 6/19 split is wrong across
    // seasons and latitudes; the car host already computes this from location + time).
    /** Whether the car map draws dark. The PHONE's theme choice decides when it is explicit
     *  (Light, Dark, AMOLED; Auto follows the sun the phone already computes), and only the
     *  "System" choice defers to the car's own day/night. A driver who set Vela to dark got a
     *  light car map because the head unit said day (user 2026-09-22); Google's app follows the
     *  car, but Vela has a theme setting and the car is one more screen it applies to. */
    private fun isNight(): Boolean = when (app.vela.ui.theme.AppTheme.mode.value) {
        app.vela.ui.theme.ThemeMode.LIGHT -> false
        app.vela.ui.theme.ThemeMode.DARK, app.vela.ui.theme.ThemeMode.AMOLED -> true
        app.vela.ui.theme.ThemeMode.AUTO -> app.vela.ui.theme.AppTheme.night.value
        else -> runCatching { carContext.isDarkMode }.getOrDefault(false)
    }

    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            locationProvider.updates().collect { loc ->
                // Puck feed gate: once we have a good GPS puck, IGNORE network/fused/coarse fixes. The
                // phone interleaves gps(hAcc≈3 m) with network/fused fixes (hAcc 18–86 m) even while
                // parked, and taking them ungated jumped the arrow around a stationary car. The nav
                // engine already gates the same way (GPS-only, ≤50 m). Before any puck (bootstrap),
                // accept anything so browse shows a location immediately.
                val goodGps = loc.provider == android.location.LocationManager.GPS_PROVIDER &&
                    (!loc.hasAccuracy() || loc.accuracy <= 50f)
                if (!goodGps && puck != null) return@collect
                val raw = LatLng(loc.latitude, loc.longitude)
                // Map-match to the route while navigating so the puck rides the road (Google/Waze do
                // this); off-route/far falls back to the raw fix.
                val here = if (navigating()) snapToRoute(raw) ?: raw else raw
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                val course = if (loc.hasBearing() && speedMps > STOPPED_MPS) loc.bearing.toDouble() else routeHeading(here)
                estimator.onFix(here.lat, here.lng, speedMps, course, android.os.SystemClock.elapsedRealtime())
                targetPuck = here
                if (puck == null) puck = here // first fix: snap into place, don't glide in from null
                // Posted speed limit (offline graph's max_speed) while navigating — null off-graph/online.
                // The graph LocationIndex snap runs OFF the main thread (this collector is on
                // Main.immediate; a synchronous mmap snap every fix would jank the render loop).
                speedLimitKmh = if (navigating())
                    withContext(Dispatchers.Default) { runCatching { routeEngine?.currentRoadLimit(here.lat, here.lng) }.getOrNull() }
                else null
                if (previewRoute != null && !navigating()) { requestRender(); return@collect } // preview owns the camera
                // Auto-recenter a few seconds after the user pans (Google-style: pan to look around, then snap back).
                if (!following && !overview && android.os.SystemClock.uptimeMillis() - lastPanMs > RECENTER_MS) following = true
                if (following) {
                    // Heading source (the ticker eases toward this, so it never snap-rotates):
                    //  1. Moving with a TRUSTWORTHY GPS course → the actual travel direction (what the
                    //     phone nav uses; correct even when off-route). A stationary or low-confidence
                    //     bearing is noise (bAcc seen at 134° while parked) — that spun the map, so it's
                    //     rejected via the speed + bearing-accuracy gate.
                    //  2. Else the route SEGMENT bearing (stable road direction; a nearest-vertex search
                    //     flickered between adjacent points and swung the view even while driving).
                    //  3. Else hold the last heading — never chase noise.
                    targetBearing = if (navigating()) {
                        val trustCourse = speedMps > STOPPED_MPS && loc.hasBearing() &&
                            (!loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees <= 45f)
                        if (trustCourse) loc.bearing.toDouble() else routeHeading(here) ?: targetBearing
                    } else 0.0 // browse = north-up
                }
                // Render once per fix too (not only from the ticker): draw() reads LIVE state, so a
                // route swap / reroute / faster-route adoption, per-span traffic recolor, the speed
                // badge, and the auto-recenter flip must repaint even when the puck is momentarily
                // stationary (fixes still arrive ~1 Hz while parked). The ticker adds the smooth
                // between-fix interpolation on top; both go through the rendering/dirty guard.
                requestRender()
            }
        }
        startTicker()
    }

    /** Steady loop that eases the displayed puck/bearing toward the GPS targets, so the map glides
     *  between the ~1 Hz fixes instead of lurching once a second. Renders only when something moved
     *  (a standstill draws nothing new); snapshots cap the true fps well below the tick rate, but each
     *  frame lands partway to the target, which reads as smooth motion rather than a jump. */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                var moved = false
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = if (lastTickMs == 0L) 0.0 else ((now - lastTickMs) / 1000.0).coerceAtMost(0.5)
                lastTickMs = now
                estimator.step(dt, now)
                val p = puck
                if (!estimator.lat.isNaN() && p != null) {
                    val nlat = estimator.lat; val nlng = estimator.lng
                    if (abs(nlat - p.lat) > 1e-7 || abs(nlng - p.lng) > 1e-7) { puck = LatLng(nlat, nlng); moved = true }
                }
                val db = shortestAngleDelta(bearing, targetBearing)
                if (abs(db) > 0.2) { bearing = normalizeAngle(bearing + db * BEARING_EASE); moved = true }
                if (abs(zoomTarget - zoom) > 0.004) { zoom += (zoomTarget - zoom) * ZOOM_EASE; moved = true }
                if (following && previewRoute == null) puck?.let { center = it }
                if (moved) requestRender()
                kotlinx.coroutines.delay(TICK_MS)
            }
        }
    }

    /** Signed shortest angular delta a→b in degrees, in (-180, 180]. */
    private fun shortestAngleDelta(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d < -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    private fun normalizeAngle(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        tickerJob?.cancel()
        tickerJob = null
        // Session teardown: release the native snapshotter too (onSurfaceDestroyed may not fire on an
        // abnormal projection end). Safe here because stop() is session-scoped, not per-screen.
        runCatching { snapshotter?.cancel() }
        snapshotter = null
        lastSnapshot = null
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        width = container.width
        height = container.height
        // Clear any in-flight-render state: a surface swap (screen transition) cancels the previous
        // snapshot before its callback fires, so `rendering` would otherwise stay stuck true forever
        // and every future requestRender would no-op — the map freezes after a screen change.
        rendering = false; dirty = false
        if (width <= 0 || height <= 0) return
        runCatching { MapLibre.getInstance(carContext) }
        center = center ?: puck ?: locationProvider.lastKnown()
        // Reuse the existing snapshotter when the surface size is unchanged. A screen transition
        // (Main→Preview→ActiveNav) re-delivers onSurfaceAvailable at the SAME size; recreating the
        // snapshotter each time span up a fresh `vela-car-map` virtual display and reloaded the whole
        // style (the repeated CompositionEngine createDisplay churn = a visible map flash/reload).
        val s = snapshotter
        if (s != null && snapWidth == width && snapHeight == height) {
            requestRender()
            return
        }
        runCatching { s?.cancel() }
        // Same style resolution as the phone map: MapFonts' Roboto-patched Liberty when its
        // cache is ready (read + handed over as JSON - the snapshotter has no file:// branch
        // of its own), else the plain URL. Without this the car screen kept Noto after the
        // phone flipped to Roboto.
        val effectiveStyle = app.vela.ui.map.MapFonts.effective(MapStyle.LIBERTY.uri)
        val patchedJson = if (effectiveStyle.startsWith("file://")) {
            runCatching { java.io.File(effectiveStyle.removePrefix("file://")).readText() }
                .getOrNull()?.takeIf { it.isNotBlank() }
        } else null
        // The layer ids of the style being loaded, for the palette's blanket passes: the patched
        // JSON when there is one, else the bundled Liberty asset (the same layer ids as the live
        // style; it is the phone's offline fallback for the same reason).
        styleLayerIds = runCatching {
            val json = patchedJson ?: carContext.assets.open("styles/liberty-roboto.json").bufferedReader().use { it.readText() }
            val layers = org.json.JSONObject(json).getJSONArray("layers")
            (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        }.getOrDefault(emptyList())
        val opts = MapSnapshotter.Options(width, height)
            .let { if (patchedJson != null) it.withStyleJson(patchedJson) else it.withStyle(MapStyle.LIBERTY.uri) }
            .withPixelRatio(1.0f)
            .withLogo(false)
        themed = false
        snapshotter = runCatching { QuietSnapshotter(carContext, opts) }.getOrNull()?.also { s2 ->
            // Vela's own palette on the car map (the same applyMapTheme the phone runs): until
            // 2026-09-21 the car drew stock Liberty under a darkening color filter, which read as
            // "a weird theme that is not ours" (user). The observer fires once the style is in.
            s2.setObserver(object : MapSnapshotter.Observer {
                override fun onDidFinishLoadingStyle() {
                    applyTheme(s2)
                    requestRender()
                }
                override fun onStyleImageMissing(id: String) {}
            })
        }
        snapWidth = width; snapHeight = height
        requestRender()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
        runCatching { snapshotter?.cancel() }
        snapshotter = null
        snapWidth = 0; snapHeight = 0
        lastSnapshot = null
        rendering = false; dirty = false // the canceled snapshot's callback won't fire — unstick the flag
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); requestRender() }
    override fun onStableAreaChanged(stableArea: Rect) { requestRender() }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val snap = lastSnapshot ?: return
        following = false
        lastPanMs = android.os.SystemClock.uptimeMillis()
        val cx = width / 2f; val cy = height / 2f
        val ll = runCatching { snap.latLngForPixel(PointF(cx + distanceX, cy + distanceY)) }.getOrNull() ?: return
        center = LatLng(ll.latitude, ll.longitude)
        requestRender()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        following = false // else the next nav frame overwrites the pinch zoom with navZoom()
        lastPanMs = android.os.SystemClock.uptimeMillis()
        zoom = (zoom + ln(scaleFactor.toDouble()) / ln(2.0)).coerceIn(2.0, 20.0)
        requestRender()
    }

    /** Zoom that tightens as you slow (more detail at junctions) and widens at speed. */
    private fun navZoom(): Double {
        val kmh = speedMps * 3.6
        return when {
            kmh < 15 -> 17.5
            kmh < 40 -> 17.0
            kmh < 70 -> 16.3
            kmh < 100 -> 15.7
            else -> 15.2
        }
    }

    private var styleLayerIds: List<String> = emptyList()

    /** Vela's palette on the snapshotter's style, for the car's current day/night. Logged under
     *  `VelaCar`, with the failure when it throws: a drive on a stock Pixel 9 (2026-09-22) showed
     *  a light map that did not look like Vela's light palette at all, and a swallowed exception
     *  here is the one explanation the log could not rule out. */
    private fun applyTheme(s: MapSnapshotter) {
        val night = isNight()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val host = app.vela.ui.map.SnapshotterHost(s, styleLayerIds)
        val ok = runCatching {
            app.vela.ui.map.applyMapTheme(
                host,
                dark = night,
                amoled = night && app.vela.ui.theme.AppTheme.mode.value == app.vela.ui.theme.ThemeMode.AMOLED,
            )
        }
        ok.onFailure { android.util.Log.w("VelaCar", "theme failed (dark=$night, ${styleLayerIds.size} ids): $it") }
        ok.onSuccess { android.util.Log.i("VelaCar", "theme applied dark=$night layers=${host.layers.size} in ${android.os.SystemClock.elapsedRealtime() - t0} ms") }
        themed = true
        themedNight = night
    }

    private fun requestRender() {
        val snap = snapshotter
        val here = center
        if (snap == null || here == null) return
        if (rendering) { dirty = true; return }
        rendering = true
        // The car flipped day/night since the palette went on: re-theme before this frame.
        if (themed && themedNight != isNight()) applyTheme(snap)

        val nav = navigating()
        if (nav && overview) navSession.state.value.route?.let { r -> frameRoute(remainingRoute(r)); zoomTarget = zoom }
        val follow = following // false while the user has panned (until auto-recenter)
        zoomTarget = if (nav && follow) navZoom() else zoom
        // FRAME INSIDE THE VISIBLE AREA. The camera target lands at the bitmap's center, but the host
        // covers part of the surface with its cards, so the point the driver should see (the puck,
        // low in the view while following in nav; the center otherwise) is placed at a pixel inside
        // the visible rect and the target is moved by that pixel offset, rotated into the map's
        // heading. Meters per pixel use MapLibre's 512 px tiles (78271.517 at z0), not the 256 px
        // constant that put the puck twice as far down as intended, off the bottom edge.
        val vis = visible?.takeIf { !it.isEmpty && it.width() > 0 && it.height() > 0 } ?: Rect(0, 0, width, height)
        val mpp = 78271.517 * cos(Math.toRadians(here.lat)) / Math.pow(2.0, zoom)
        val wantX = vis.exactCenterX()
        val wantY = if (nav && follow) vis.top + vis.height() * PUCK_DOWN.toFloat() else vis.exactCenterY()
        val dx = (wantX - width / 2f).toDouble() * mpp   // the shown point right of center: target goes left
        val dy = (wantY - height / 2f).toDouble() * mpp  // the shown point below center: target goes ahead
        val heading = if (nav && follow) bearing else 0.0
        val anchor = if (nav && follow) (puck ?: here) else here
        val target = anchor.destinationPoint(dy, heading).destinationPoint(dx, heading - 90.0)

        val cam = CameraPosition.Builder()
            .target(MLLatLng(target.lat, target.lng))
            .zoom(zoom)
            .bearing(if (nav && follow) bearing else 0.0)
            .tilt(0.0)
            .build()
        runCatching {
            snap.setSize(width, height)
            snap.setCameraPosition(cam)
            snap.start({ result ->
                rendering = false
                // THE STYLE OBSERVER NEVER FIRED IN PRACTICE (Gearslip preview, 2026-09-22: no
                // theme line in the log, the map drawn as stock Liberty under the old darkening
                // filter): a style handed over as JSON finishes parsing before setObserver runs.
                // A returned snapshot proves the style is loaded, so the palette goes on here on
                // the first one, and that frame is thrown away for a themed one.
                if (!themed) {
                    applyTheme(snap)
                    requestRender()
                    return@start
                }
                lastSnapshot = result
                draw(result)
                if (dirty) { dirty = false; requestRender() }
            }, { rendering = false })
        }.onFailure { rendering = false }
    }

    private fun draw(snap: MapSnapshot) {
        val s = surface ?: return
        if (!s.isValid || width <= 0 || height <= 0) return
        val bmp = runCatching { snap.bitmap }.getOrNull()
        val canvas: Canvas = try { s.lockCanvas(null) } catch (t: Throwable) { return }
        try {
            if (bmp != null) {
                // The darkening filter was the night look before the palette could be applied; a
                // themed style is drawn as is.
                val paint = if (isNight() && !themed) nightBitmapPaint else dayBitmapPaint
                canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(0, 0, width, height), paint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            }
            val sx = if (bmp != null) width.toFloat() / bmp.width else 1f
            val sy = if (bmp != null) height.toFloat() / bmp.height else 1f
            // Each overlay guarded so a projection hiccup in one can't blank the rest.
            val preview = previewRoute
            if (navigating()) {
                runCatching { drawRoute(canvas, snap, sx, sy) }
                runCatching { drawCorridor(canvas, snap, sx, sy) }
                runCatching { drawSpeed(canvas) }
            } else if (preview != null && preview.polyline.size >= 2) {
                // Preview screen: the whole route in blue, framed.
                runCatching { canvas.drawPath(pathOf(snap, preview.polyline, preview.polyline.indices, sx, sy), trafficPaints[0]!!) }
            }
            runCatching { drawPuck(canvas, snap, sx, sy) } // puck always drawn
            runCatching { drawAttribution(canvas) }
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun project(snap: MapSnapshot, p: LatLng, sx: Float, sy: Float): PointF? {
        val px = runCatching { snap.pixelForLatLng(MLLatLng(p.lat, p.lng)) }.getOrNull() ?: return null
        return PointF(px.x * sx, px.y * sy)
    }

    private fun drawRoute(canvas: Canvas, snap: MapSnapshot, sx: Float, sy: Float) {
        val route = navSession.state.value.route ?: return
        val poly = route.polyline
        if (poly.size < 2) return
        val here = puck
        var splitI = 0
        if (here != null) {
            var best = Double.MAX_VALUE
            for (i in poly.indices) {
                val d = hypot(poly[i].lat - here.lat, poly[i].lng - here.lng)
                if (d < best) { best = d; splitI = i }
            }
        }
        // Draw the WHOLE route ahead in blue first (robust baseline — always shows), gray behind.
        if (splitI > 0) canvas.drawPath(pathOf(snap, poly, 0..splitI, sx, sy), drivenPaint)
        if (splitI < poly.lastIndex) canvas.drawPath(pathOf(snap, poly, splitI..poly.lastIndex, sx, sy), trafficPaints[0]!!)

        // Overlay per-span traffic color on the ahead portion (best-effort; the blue baseline shows
        // regardless if this finds nothing).
        val spans = route.trafficSpans
        if (spans.isEmpty() || route.distanceMeters <= 0) return
        val cum = DoubleArray(poly.size)
        for (i in 1 until poly.size) cum[i] = cum[i - 1] + haversine(poly[i - 1], poly[i])
        fun levelAt(m: Double): Int {
            for (sp in spans) if (m >= sp.startMeters && m < sp.startMeters + sp.lengthMeters) return sp.level.coerceIn(1, 3)
            return 0
        }
        for (i in splitI until poly.lastIndex) {
            val lvl = levelAt((cum[i] + cum[i + 1]) / 2.0)
            if (lvl == 0) continue // free-flow already blue from the baseline
            val a = project(snap, poly[i], sx, sy) ?: continue
            val b = project(snap, poly[i + 1], sx, sy) ?: continue
            canvas.drawLine(a.x, a.y, b.x, b.y, trafficPaints[lvl] ?: trafficPaints[0]!!)
        }
    }

    private fun pathOf(snap: MapSnapshot, poly: List<LatLng>, range: IntRange, sx: Float, sy: Float): Path {
        val p = Path(); var started = false
        for (i in range) {
            val pt = project(snap, poly[i], sx, sy) ?: continue
            if (!started) { p.moveTo(pt.x, pt.y); started = true } else p.lineTo(pt.x, pt.y)
        }
        return p
    }

    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f; setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    /** The ODbL credit, ours and only ours: the library's own overlay ([QuietSnapshotter]) printed
     *  every source's attribution string, which on this basemap is a line of tile-provider names
     *  (user 2026-09-21, "the watermark is wrong and says way more than just OpenStreetMap"). The
     *  phone map shows the same single line. Drawn inside the visible area. */
    private fun drawAttribution(canvas: Canvas) {
        val vis = visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
        attributionPaint.textSize = (minOf(width, height) / 45f).coerceIn(9f, 14f)
        canvas.drawText(ATTRIBUTION, vis.left + 8f, vis.bottom - 8f, attributionPaint)
    }

    private fun drawPuck(canvas: Canvas, snap: MapSnapshot, sx: Float, sy: Float) {
        val here = puck ?: return
        val pt = project(snap, here, sx, sy) ?: return
        // Sized to the SCREEN, not a fixed pixel count: a fixed 22 px radius was a fifth of the
        // height of a 480 px head unit (user 2026-09-19, "the puck on the car stereo is huge").
        // A fortieth of the short side reads like the phone's puck (about 5% of the screen).
        // Settings > Navigation > Puck size (PuckStyle) scales it the same way it scales the phone's.
        // The phone's puck bitmap (disc, shadow, chevron), not a chevron of the car's own; the
        // bitmap's arrow points up, so it turns by the heading against the camera's bearing:
        // straight up in heading-up nav, by the course in a north-up view. An eighth of the
        // short side, about what the phone draws relative to its width (user 2026-09-22: the
        // fortieth-based size read too small on the car), times the Settings puck size.
        val px = (minOf(width, height) / 8f * app.vela.ui.PuckStyle.scale()).roundToInt().coerceIn(24, 220)
        val bmp = puckBitmap?.takeIf { puckBitmapPx == px } ?: android.graphics.Bitmap.createScaledBitmap(
            app.vela.ui.map.navPuckBitmap(scale = 1f), px, px, true,
        ).also { puckBitmap = it; puckBitmapPx = px }
        val camBearing = if (navigating() && following && previewRoute == null) bearing else 0.0
        canvas.save()
        canvas.rotate((bearing - camBearing).toFloat(), pt.x, pt.y)
        canvas.drawBitmap(bmp, pt.x - px / 2f, pt.y - px / 2f, dayBitmapPaint)
        canvas.restore()
    }

    /** Bottom-right current-speed badge (km/h or mph per the user's units), plus a Google-style
     *  round speed-limit sign to its left when the road's posted limit is known (offline graph). */
    private fun drawSpeed(canvas: Canvas) {
        val imperial = app.vela.ui.Units.imperial.value
        val v = if (imperial) speedMps * 2.236936 else speedMps * 3.6
        val num = v.roundToInt().coerceAtLeast(0)
        val unit = if (imperial) "mph" else "km/h"
        // Inside the host's VISIBLE area, not the surface's corner: the template's map action
        // strip (overview, zoom) sits over the surface's bottom right, and the badge drew under
        // it (user 2026-09-22, stock Pixel 9). Scaled like the puck so a small unit keeps room.
        val vis = visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
        val rad = (minOf(width, height) / 12f).coerceIn(30f, 46f)
        val cx = vis.right - rad - 16f; val cy = vis.bottom - rad - 20f
        canvas.drawRoundRect(RectF(cx - rad, cy - rad, cx + rad, cy + rad), 20f, 20f, badgeBg)
        canvas.drawText(num.toString(), cx, cy + 6f, badgeNum)
        canvas.drawText(unit, cx, cy + 30f, badgeUnit)

        // Speed-limit sign (white disc, red ring) to the left of the speed badge.
        speedLimitKmh?.let { kmh ->
            val limit = (if (imperial) kmh / 1.609344 else kmh).roundToInt()
            if (limit <= 0) return
            val lx = cx - 2 * rad - 18f; val ly = cy; val lr = 42f
            canvas.drawCircle(lx, ly, lr, limitDisc)
            canvas.drawCircle(lx, ly, lr, limitRing)
            canvas.drawText(limit.toString(), lx, ly + 14f, limitNum)
        }
    }

    /** Center the camera on [route] and pick a zoom that fits its bounding box (preview screen). */
    /** The route from the puck onward (the whole route until the puck is on it). */
    private fun remainingRoute(route: Route): Route {
        val p = puck ?: return route
        val poly = route.polyline
        if (poly.size < 2) return route
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in poly.indices) { val d = poly[i].distanceTo(p); if (d < bestD) { bestD = d; best = i } }
        return if (bestD > 200.0 || best >= poly.size - 1) route else route.copy(polyline = listOf(p) + poly.drop(best + 1))
    }

    // Corridor furniture the phone's nav map draws, from the same data (CarBridge): lights, stop
    // signs, speed cameras, plus the plate cameras along the route straight off the bundled set.
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private var flockRouteKey: Int = 0
    private var flockAlong: List<LatLng> = emptyList()
    private fun drawCorridor(canvas: Canvas, snap: MapSnapshot, sx: Float, sy: Float) {
        if (zoom < 13.5) return
        val r = (minOf(width, height) / 90f).coerceIn(4f, 9f)
        fun dot(at: LatLng, color: Int) {
            val pt = project(snap, at, sx, sy) ?: return
            glyphFill.color = color
            canvas.drawCircle(pt.x, pt.y, r, glyphFill)
            canvas.drawCircle(pt.x, pt.y, r, glyphRing)
        }
        app.vela.car.CarBridge.controls.value.forEach { c ->
            dot(c.loc, when (c.kind) {
                app.vela.core.data.TrafficControl.Kind.SIGNAL -> Color.parseColor("#F9C74F")
                app.vela.core.data.TrafficControl.Kind.STOP -> Color.parseColor("#D93838")
                app.vela.core.data.TrafficControl.Kind.RAIL_CROSSING -> Color.parseColor("#37474F")
                app.vela.core.data.TrafficControl.Kind.SPEED_HUMP -> Color.parseColor("#E8923D")
            })
        }
        app.vela.car.CarBridge.speedCameras.value.forEach { dot(it, Color.parseColor("#FB8C00")) }
        if (app.vela.ui.Flock.on.value) {
            val route = navSession.state.value.route
            val key = System.identityHashCode(route)
            if (route != null && key != flockRouteKey && app.vela.data.FlockCameras.isLoaded) {
                flockRouteKey = key
                flockAlong = runCatching { app.vela.data.FlockCameras.along(route.polyline).map { it.loc } }.getOrDefault(emptyList())
            }
            flockAlong.forEach { dot(it, Color.parseColor("#8E24AA")) }
        }
    }

    private fun frameRoute(route: Route?) {
        val poly = route?.polyline ?: return
        if (poly.isEmpty()) return
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for (p in poly) {
            minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat)
            minLng = Math.min(minLng, p.lng); maxLng = Math.max(maxLng, p.lng)
        }
        val c = LatLng((minLat + maxLat) / 2, (minLng + maxLng) / 2)
        center = c
        val span = Math.max(maxLat - minLat, (maxLng - minLng) * cos(Math.toRadians(c.lat))).coerceAtLeast(0.0015)
        zoom = (ln(360.0 / (span * 1.7)) / ln(2.0)).coerceIn(3.0, 16.0)
        bearing = 0.0
    }

    /** Heading = the bearing of the route SEGMENT the puck is on, not the bearing to the nearest
     *  vertex. A nearest-vertex search flickers between adjacent points on the ~2 m GPS jitter you get
     *  while parked, swinging the whole heading-up view ("moving in all directions in the driveway").
     *  The segment bearing is stable: small jitter keeps you on the same segment, so the heading holds. */
    private fun routeHeading(here: LatLng): Double? {
        val poly = navSession.state.value.route?.polyline ?: return null
        if (poly.size < 2) return null
        var bestSeg = 0; var best = Double.MAX_VALUE
        for (i in 0 until poly.lastIndex) {
            val d = distToSegment(here, poly[i], poly[i + 1])
            if (d < best) { best = d; bestSeg = i }
        }
        return poly[bestSeg].bearingTo(poly[bestSeg + 1])
    }

    /** Approx point→segment distance in a local equirectangular frame (lng scaled by cos lat). Good
     *  enough to pick the nearest segment; not used for display meters. */
    private fun distToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = a.lng * cosLat; val ay = a.lat
        val bx = b.lng * cosLat; val by = b.lat
        val px = p.lng * cosLat; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** MAP MATCHING: project [here] onto the nearest ROUTE segment and return the on-road point — but
     *  only when it's within [SNAP_MAX_M] of the route (you're on it). This is how Google/Waze keep the
     *  puck glued to the road and glide it smoothly; it also fully absorbs residual GPS jitter and any
     *  stray coarse fix. Off-route / far (before a reroute lands) → null, so the raw fix is used and a
     *  genuine deviation still reads as off the line. */
    private fun snapToRoute(here: LatLng): LatLng? {
        val poly = navSession.state.value.route?.polyline ?: return null
        if (poly.size < 2) return null
        val cosLat = cos(Math.toRadians(here.lat))
        val px = here.lng * cosLat; val py = here.lat
        var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
        for (i in 0 until poly.lastIndex) {
            val a = poly[i]; val b = poly[i + 1]
            val ax = a.lng * cosLat; val ay = a.lat
            val dx = b.lng * cosLat - ax; val dy = b.lat - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val projx = ax + t * dx; val projy = ay + t * dy
            val d = hypot(px - projx, py - projy)
            if (d < best) { best = d; bx = projx; by = projy }
        }
        if (best * 111_320.0 > SNAP_MAX_M) return null // too far from the route → keep the raw fix
        return LatLng(by, bx / cosLat) // unproject the equirectangular point back to lat/lng
    }

    private fun haversine(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val la = Math.toRadians(a.lat); val lb = Math.toRadians(b.lat)
        val h = Math.sin(dLat / 2).let { it * it } + cos(la) * cos(lb) * Math.sin(dLng / 2).let { it * it }
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }
}

/** A [MapSnapshotter] that does not stamp the library's logo and attribution overlay onto the
 *  bitmap: `addOverlay` is the protected hook that draws both, and `withLogo(false)` only ever
 *  removed the logo. The car renderer draws its own single-line credit instead. */
private class QuietSnapshotter(context: android.content.Context, options: Options) : MapSnapshotter(context, options) {
    override fun addOverlay(mapSnapshot: MapSnapshot) {}
}
