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
 *    the road you're driving into), zoom tightens as you slow, the route line is drawn coloured by
 *    live traffic, and a current-speed badge shows.
 *  - **Browse** — north-up, centred on you, NO route (so a finished trip's line doesn't linger).
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

    // Preview mode: draw this route framed (no puck-follow) — used by the route-preview screen.
    @Volatile private var previewRoute: Route? = null
    @Volatile private var lastPanMs = 0L // last user pan/zoom; auto-recenter kicks in after RECENTER_MS

    private companion object {
        const val RECENTER_MS = 6000L // auto-recenter this long after a pan
        const val TICK_MS = 70L       // render-loop cadence (snapshots gate the real fps below this)
        const val PUCK_EASE = 0.28    // fraction of the remaining gap closed per tick (~1 s to converge)
        const val BEARING_EASE = 0.22
        const val STOPPED_MPS = 1.0   // below this, treat as parked: don't trust GPS-course noise
        const val SNAP_MAX_M = 40.0   // map-match to the route only within this distance (else off-route)
    }

    private fun navigating() = navSession.state.value.navigating

    /** Follow the puck (browse north-up / nav heading-up) — the landing + active-nav screens. */
    fun follow() {
        previewRoute = null
        following = true
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
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2ee6a6") }
    private val puckStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#0f1420") }
    // Traffic colours (match the phone route line): free-flow blue → amber → red.
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
    private fun isNight(): Boolean = runCatching { carContext.isDarkMode }.getOrDefault(false)

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
                targetPuck = here
                if (puck == null) puck = here // first fix: snap into place, don't glide in from null
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
                // Posted speed limit (offline graph's max_speed) while navigating — null off-graph/online.
                // The graph LocationIndex snap runs OFF the main thread (this collector is on
                // Main.immediate; a synchronous mmap snap every fix would jank the render loop).
                speedLimitKmh = if (navigating())
                    withContext(Dispatchers.Default) { runCatching { routeEngine?.currentRoadLimit(here.lat, here.lng) }.getOrNull() }
                else null
                if (previewRoute != null && !navigating()) { requestRender(); return@collect } // preview owns the camera
                // Auto-recenter a few seconds after the user pans (Google-style: pan to look around, then snap back).
                if (!following && android.os.SystemClock.uptimeMillis() - lastPanMs > RECENTER_MS) following = true
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
                // route swap / reroute / faster-route adoption, per-span traffic recolour, the speed
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
                val tp = targetPuck; val p = puck
                if (tp != null && p != null) {
                    val nlat = p.lat + (tp.lat - p.lat) * PUCK_EASE
                    val nlng = p.lng + (tp.lng - p.lng) * PUCK_EASE
                    if (abs(nlat - p.lat) > 1e-7 || abs(nlng - p.lng) > 1e-7) { puck = LatLng(nlat, nlng); moved = true }
                }
                val db = shortestAngleDelta(bearing, targetBearing)
                if (abs(db) > 0.2) { bearing = normalizeAngle(bearing + db * BEARING_EASE); moved = true }
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
        val opts = MapSnapshotter.Options(width, height)
            .let { if (patchedJson != null) it.withStyleJson(patchedJson) else it.withStyle(MapStyle.LIBERTY.uri) }
            .withPixelRatio(1.0f)
            .withLogo(false)
        snapshotter = runCatching { MapSnapshotter(carContext, opts) }.getOrNull()
        snapWidth = width; snapHeight = height
        requestRender()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
        runCatching { snapshotter?.cancel() }
        snapshotter = null
        snapWidth = 0; snapHeight = 0
        lastSnapshot = null
        rendering = false; dirty = false // the cancelled snapshot's callback won't fire — unstick the flag
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) { requestRender() }
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

    private fun requestRender() {
        val snap = snapshotter
        val here = center
        if (snap == null || here == null) return
        if (rendering) { dirty = true; return }
        rendering = true

        val nav = navigating()
        val follow = following // false while the user has panned (until auto-recenter)
        if (nav && follow) zoom = navZoom()
        // Look-ahead: while following in nav, push the camera target forward along the heading so the
        // puck sits in the lower third (Google-style). Browse/panned keeps the plain centre.
        val target = if (nav && follow && puck != null) {
            val mpp = 156543.03392 * cos(Math.toRadians(here.lat)) / Math.pow(2.0, zoom)
            val aheadMeters = height * 0.22 * mpp // ~22% of the view up-screen
            puck!!.destinationPoint(aheadMeters, bearing)
        } else here

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
                val paint = if (isNight()) nightBitmapPaint else dayBitmapPaint
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
                runCatching { drawSpeed(canvas) }
            } else if (preview != null && preview.polyline.size >= 2) {
                // Preview screen: the whole route in blue, framed.
                runCatching { canvas.drawPath(pathOf(snap, preview.polyline, preview.polyline.indices, sx, sy), trafficPaints[0]!!) }
            }
            runCatching { drawPuck(canvas, snap, sx, sy) } // puck always drawn
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
        // Draw the WHOLE route ahead in blue first (robust baseline — always shows), grey behind.
        if (splitI > 0) canvas.drawPath(pathOf(snap, poly, 0..splitI, sx, sy), drivenPaint)
        if (splitI < poly.lastIndex) canvas.drawPath(pathOf(snap, poly, splitI..poly.lastIndex, sx, sy), trafficPaints[0]!!)

        // Overlay per-span traffic colour on the ahead portion (best-effort; the blue baseline shows
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

    private fun drawPuck(canvas: Canvas, snap: MapSnapshot, sx: Float, sy: Float) {
        val here = puck ?: return
        val pt = project(snap, here, sx, sy) ?: return
        val r = 22f
        val path = Path().apply {
            moveTo(pt.x, pt.y - r)
            lineTo(pt.x + r * 0.8f, pt.y + r * 0.7f)
            lineTo(pt.x, pt.y + r * 0.3f)
            lineTo(pt.x - r * 0.8f, pt.y + r * 0.7f)
            close()
        }
        canvas.drawPath(path, puckPaint)
        canvas.drawPath(path, puckStroke)
    }

    /** Bottom-right current-speed badge (km/h or mph per the user's units), plus a Google-style
     *  round speed-limit sign to its left when the road's posted limit is known (offline graph). */
    private fun drawSpeed(canvas: Canvas) {
        val imperial = app.vela.ui.Units.imperial.value
        val v = if (imperial) speedMps * 2.236936 else speedMps * 3.6
        val num = v.roundToInt().coerceAtLeast(0)
        val unit = if (imperial) "mph" else "km/h"
        val cx = width - 62f; val cy = height - 66f; val rad = 46f
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
     *  enough to pick the nearest segment; not used for display metres. */
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
