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
import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Renders the REAL styled Vela map (OpenFreeMap Liberty vector — streets, labels, POIs) onto an
 * Android Auto template surface via MapLibre's stable public [MapSnapshotter] (off-screen map →
 * Bitmap), with the route + puck overlaid. Not 60 fps (a snapshot is ~100–300 ms), but for a nav
 * map that follows position it's a genuine moving map.
 *
 * Following, the camera is heading-up and tilted ([CarFraming.NAV_TILT_DEG]) with the arrow low
 * in the view, zoomed by speed, during a drive and once the car is moving with no route (the
 * phone's free-drive follow); north-up, flat and centered while parked with no route, with the
 * phone's "Keep north up" on, and after a pan. The route line is colored by live traffic during a
 * drive; Google's traffic raster shows with no route when the phone's Traffic layer is on.
 *
 * Vela's own palette goes on the snapshotter's style ([applyTheme], the phone's applyMapTheme), dark at
 * night by the host's day/night signal; the old darkening color filter only covers a frame drawn
 * before the palette is applied. The phone's runtime layers (the plaza and parking fills, the
 * traffic raster) are written into the style JSON first ([CarStyle]).
 */
class CarMapRenderer(
    // A CarContext for Android Auto; any Context for a surface of Vela's own (the cluster map).
    private val context: Context,
    private val locationProvider: LocationProvider,
    private val navSession: NavSession,
    private val routeEngine: RouteEngine? = null,
    // The speed badge and the limit sign. Off on a cluster, which shows the car's own.
    private val badges: Boolean = true,
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

    // Camera / motion state. `puck`/`heading` are the DISPLAYED (smoothed) values the draw + camera
    // code reads; `targetPuck`/`targetHeading` are the latest GPS truth. A steady ticker glides the
    // displayed values toward the targets between the ~1 Hz fixes, so the map no longer lurches once
    // a second (the "constantly moving around / choppy" report) — it dead-reckons like a real nav map.
    @Volatile private var puck: LatLng? = null
    @Volatile private var targetPuck: LatLng? = null
    @Volatile private var speedMps: Double = 0.0
    @Volatile private var speedLimitKmh: Double? = null
    private var center: LatLng? = null
    private var zoom = 16.5
    private var showingWorld = false // the no-location placeholder view is up
    // The arrow's heading (the GPS course, or the route's direction), always; the camera's bearing
    // is a separate eased value, so a north-up view still turns the arrow and the switch to
    // heading-up rotates the map in instead of snapping it.
    private var heading = 0.0
    private var targetHeading = 0.0
    private var camBearing = 0.0
    private var tilt = 0.0
    // Zoom to the next turn on the car (followZoom): the camera's turn toward the maneuver it
    // frames, added to the heading by the ticker, and the maneuver's point cached per step.
    private var turnYaw = 0.0
    private var turnRoute: Route? = null
    private var turnStep = -1
    private var turnPoint: LatLng? = null
    private var following = true // pan turns this off in browse
    // The phone's free-drive latch: once the smoothed speed passes DRIVE_LATCH_MPS with a course
    // known, the view is heading-up with no route and stays so at a red light; it lets go when the
    // follow ends (a pan, a zoom button, the overview).
    private var driving = false
    private var driveSpeed = 0.0
    private var lastFix: LatLng? = null
    private var lastFixMs = 0L
    // The drive-mode screen (no route) shows the speed badge like the drive screen does.
    @Volatile private var driveScreen = false
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
    // the host's isDarkMode no longer matches.
    @Volatile private var themedNight: Boolean? = null
    // The Vela puck (the same bitmap the phone draws, ui/map/navPuckBitmap), scaled to the screen
    // once per size; the car used to draw its own green chevron (user: "not our pretty puck").
    private var puckBitmap: android.graphics.Bitmap? = null
    private var puckBitmapPx = 0

    // Preview mode: draw this route framed (no puck-follow) — used by the route-preview screen.
    @Volatile private var previewRoute: Route? = null
    @Volatile private var previewOthers: List<Route> = emptyList()
    @Volatile private var resultPins: List<LatLng> = emptyList()
    @Volatile private var lastPanMs = 0L // last user pan/zoom; auto-recenter kicks in after RECENTER_MS
    // A fling's remaining glide, surface pixels per second (right is east, down is south).
    private var flingVx = 0.0
    private var flingVy = 0.0

    companion object {
        private val PIN_FILL = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ea4335") }
        private val PIN_RING = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private val PIN_TEXT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }

        /** A red disc with a white ring and a number: one result, on the map and on its row. */
        fun drawNumberedPin(canvas: Canvas, cx: Float, cy: Float, r: Float, n: Int) {
            PIN_RING.strokeWidth = r * 0.2f
            PIN_TEXT.textSize = r * 1.15f
            canvas.drawCircle(cx, cy, r, PIN_FILL)
            canvas.drawCircle(cx, cy, r, PIN_RING)
            canvas.drawText(n.toString(), cx, cy + r * 0.4f, PIN_TEXT)
        }

        /** [drawNumberedPin] as a bitmap, for the result's row in the list. */
        fun pinBitmap(n: Int, px: Int = 72): android.graphics.Bitmap {
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            drawNumberedPin(Canvas(bmp), px / 2f, px / 2f, px * 0.42f, n)
            return bmp
        }

        const val RECENTER_MS = 6000L // auto-recenter this long after a pan
        val LIMIT_INK = Color.parseColor("#111111")
        val LIMIT_OVER = Color.parseColor("#e8514a") // the phone's over-the-limit red
        val WORLD_CENTER = LatLng(20.0, 0.0)
        const val WORLD_ZOOM = 1.5
        const val TICK_MS = 70L      // render-loop cadence (snapshots gate the real fps below this)
        const val BEARING_EASE = 0.22
        const val CAM_BEARING_EASE = 0.3 // the camera onto the arrow's heading, and back to north
        const val ZOOM_EASE = 0.06    // per tick, so a speed change glides over a second or so
        const val TILT_EASE = 0.05    // per tick: the tilt comes in over about two seconds
        const val PUCK_DOWN = 0.72    // the puck sits this far down the VISIBLE area in a heading-up view
        const val TURN_MARGIN_PX = 40.0 // the next maneuver is framed this far under the top of the visible area
        const val PUCK_AIM_PX = 40.0  // how far ahead of the arrow, in ground pixels, its heading is sampled for its turn on screen
        const val DRIVE_LATCH_MPS = 2.5 // the phone's free-drive latch speed, on the smoothed speed
        const val DRIVE_SPEED_TAU_S = 1.0 // the smoothing behind it
        const val FIX_SPEED_MAX_MPS = 70.0 // a speed made from two fixes past this is a jump, not motion
        const val FIX_COURSE_MIN_M = 3.0   // a course made from two fixes needs this much travel
        const val ATTRIBUTION = "\u00a9 OpenStreetMap" // the phone's map_osm_attribution, verbatim

        /** A traffic light is drawn this many times as tall as a stop sign, its glyph's own
         *  proportions (`trafficLightBitmap` is 30 x 60, `stopSignBitmap` 46 x 46). */
        const val LIGHT_TALL = 1.4f

        /**
         * A stop sign's size on a car screen whose short side is [shortSidePx], at [zoom]: a
         * twentieth of the short side, so wider than the 20 px route line on the 720 px and
         * 480 px profiles, scaled with zoom the way the phone scales them (`controlsSize`: about
         * 0.7 at 14, 1 at 15.5, 1.3 at 17) and kept between 16 and 56 px.
         */
        fun controlGlyphPx(shortSidePx: Int, zoom: Double): Float {
            val k = when {
                zoom <= 14.0 -> 0.7
                zoom >= 17.0 -> 1.3
                zoom <= 15.5 -> 0.7 + (zoom - 14.0) / 1.5 * 0.3
                else -> 1.0 + (zoom - 15.5) / 1.5 * 0.3
            }
            return (shortSidePx / 20f * k.toFloat()).coerceIn(16f, 56f)
        }
        const val STOPPED_MPS = 1.0   // below this, treat as parked: don't trust GPS-course noise
        const val SNAP_MAX_M = 40.0   // map-match to the route only within this distance (else off-route)
        const val FLING_TAU_S = 0.35  // a fling's speed falls to a third in this long
        const val FLING_STOP_PX_S = 40.0 // and stops below this
    }

    private fun navigating() = navSession.state.value.navigating

    private var courseKnown = false
    private var heldStill = false // the at-rest sensor is held while a drive is on
    private var routeCum: Pair<app.vela.core.model.Route, DoubleArray>? = null // the route's cumulative meters, for the heading at its progress

    /** Whether the following view turns with the car: during a drive, or moving with no route,
     *  unless the phone's Settings > Map "Keep north up" is on. */
    private fun headingUp(): Boolean =
        following && previewRoute == null && !overview && !app.vela.ui.NorthLock.on.value && (navigating() || driving)

    /** The drive-mode screen is up: no route, the map alone, the speed badge drawn. */
    fun setDriveScreen(on: Boolean) { driveScreen = on; requestRender() }

    /** Follow the puck (browse north-up / nav heading-up) — the landing + active-nav screens. */
    fun follow() {
        flingVx = 0.0; flingVy = 0.0
        previewRoute = null
        previewOthers = emptyList()
        resultPins = emptyList()
        overview = false
        following = true
        if (driveSpeed > DRIVE_LATCH_MPS && courseKnown) driving = true // already moving: heading-up at once
        requestRender()
    }

    // OVERVIEW: the whole remaining route framed north-up; the auto-recenter leaves it alone until
    // the driver taps it off (or recenters). The phone has the same toggle.
    @Volatile private var overview = false
    fun toggleOverview() {
        flingVx = 0.0; flingVy = 0.0
        overview = !overview
        following = !overview
        if (!overview) lastPanMs = 0L
        requestRender()
    }

    /** Step the zoom (map-control buttons); pins follow off briefly so the change is visible. */
    fun zoomBy(delta: Double) {
        flingVx = 0.0; flingVy = 0.0
        zoom = (zoom + delta).coerceIn(2.0, 20.0)
        lastPanMs = android.os.SystemClock.uptimeMillis()
        following = false
        requestRender()
    }

    /** Nearby results as numbered pins, framed together with the car, so a driver can see which
     *  way each row of the list is. The numbers match [pinBitmap] on the rows. [follow] clears them. */
    fun showResults(points: List<LatLng>) {
        flingVx = 0.0; flingVy = 0.0
        previewRoute = null
        previewOthers = emptyList()
        resultPins = points
        following = points.isEmpty()
        if (points.isNotEmpty()) framePoints(points + listOfNotNull(puck))
        requestRender()
    }

    /** Frame [route] on the surface for the route-preview screen (no live follow). [others] are
     *  the routes not picked, drawn in gray under it so the choice can be seen on the map. */
    fun showPreview(route: Route?, others: List<Route> = emptyList()) {
        flingVx = 0.0; flingVy = 0.0
        previewRoute = route
        previewOthers = others
        resultPins = emptyList()
        following = false
        frameRoute(route)
        requestRender()
    }

    private val drivenPaint = strokePaint("#7b8494", 18f)
    private val otherRoutePaint = strokePaint("#8a93a3", 14f)
    private val pausedPaint = strokePaint("#9c8ad6", 20f) // the phone's paused-drive lavender
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ea4335") }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val pinDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bgPaint = Paint().apply { color = Color.parseColor("#0f1420") }
    // Traffic colors (match the phone route line): free-flow blue → amber → red.
    private val trafficPaints = mapOf(
        0 to strokePaint("#1f6feb", 20f), // free-flowing, the phone route's blue; wider and darker like Google's car line (user 2026-10-15)
        1 to strokePaint("#f9a825", 20f), // moderate
        2 to strokePaint("#e53935", 20f), // heavy
        3 to strokePaint("#8e1414", 20f), // severe
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
    private val limitEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#202124"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val limitWord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3c4043"); textAlign = Paint.Align.CENTER; textSize = 13f; isFakeBoldText = true
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
    private fun isNight(): Boolean {
        // A look Vela does not paint is as dark as it comes.
        val look = app.vela.ui.MapLook.style.value
        if (!look.painted) return look.darkLook
        return when (app.vela.ui.theme.AppTheme.mode.value) {
            app.vela.ui.theme.ThemeMode.LIGHT -> false
            app.vela.ui.theme.ThemeMode.DARK, app.vela.ui.theme.ThemeMode.AMOLED -> true
            app.vela.ui.theme.ThemeMode.AUTO -> app.vela.ui.theme.AppTheme.night.value
            else -> runCatching {
                (context as? CarContext)?.isDarkMode
                    ?: ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            }.getOrDefault(false)
        }
    }

    fun start() {
        collectJob?.cancel()
        collectJob = scope.launch {
            CarLocationAccess.granted.first { it } // asked for on the landing screen if missing
            locationProvider.updates().collect { loc ->
                // Puck feed gate: once we have a good GPS puck, IGNORE network/fused/coarse fixes. The
                // phone interleaves gps(hAcc≈3 m) with network/fused fixes (hAcc 18–86 m) even while
                // parked, and taking them ungated jumped the arrow around a stationary car. The nav
                // engine already gates the same way (GPS-only, ≤50 m). Before any puck (bootstrap),
                // accept anything so browse shows a location immediately.
                val goodGps = (loc.provider == android.location.LocationManager.GPS_PROVIDER ||
                    loc.provider == app.vela.core.location.SIM_PROVIDER || loc.provider == app.vela.core.location.REPLAY_PROVIDER) &&
                    (!loc.hasAccuracy() || loc.accuracy <= 50f)
                if (!goodGps && puck != null) return@collect
                val raw = LatLng(loc.latitude, loc.longitude)
                // Map-match to the route while navigating so the puck rides the road (Google/Waze do
                // this); off-route/far falls back to the raw fix.
                val here = if (navigating()) snapToRoute(raw) ?: raw else raw
                // A fix without a speed (a provider that does not report one) read as parked, so
                // the following zoom stayed at its closest: the speed then comes from the distance
                // since the last fix.
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val dtFix = if (lastFixMs == 0L) 0.0 else (nowMs - lastFixMs) / 1000.0
                val prev = lastFix?.takeIf { dtFix in 0.2..5.0 }
                val movedM = prev?.let { raw.distanceTo(it) } ?: 0.0
                speedMps = when {
                    loc.hasSpeed() -> loc.speed.toDouble()
                    prev != null -> (movedM / dtFix).coerceIn(0.0, FIX_SPEED_MAX_MPS)
                    else -> 0.0
                }
                // A wide fix under SLOW_MPS, or any crawl while the phone is at rest, is a parked
                // car: its speed reads 0 and its course is not believed (issues #818, #819).
                val wide = loc.hasAccuracy() && loc.accuracy > app.vela.core.location.Stillness.ACC_TRUST_M
                if (speedMps < app.vela.core.location.Stillness.SLOW_MPS && (wide || app.vela.core.location.Stillness.still)) speedMps = 0.0
                val nav = navigating()
                if (nav != heldStill) { if (nav) app.vela.core.location.Stillness.hold(context) else app.vela.core.location.Stillness.release(); heldStill = nav }
                // The same for a fix without a bearing: the direction from the last fix, once it
                // has moved far enough for that to mean something.
                val fixCourse: Double? = when {
                    loc.hasBearing() -> loc.bearing.toDouble()
                    prev != null && movedM >= FIX_COURSE_MIN_M -> prev.bearingTo(raw)
                    else -> null
                }
                lastFix = raw; lastFixMs = nowMs
                driveSpeed += (speedMps - driveSpeed) * (1.0 - exp(-(if (dtFix > 0.0) dtFix else 1.0) / DRIVE_SPEED_TAU_S))
                // Stopped, the heading holds: asking the nearest route segment on every wide fix at
                // a light before a turn swung the arrow between the two streets.
                val courseOk = fixCourse != null && speedMps > STOPPED_MPS && (!wide || speedMps >= app.vela.core.location.Stillness.SLOW_MPS)
                val course = if (courseOk) fixCourse else if (speedMps <= STOPPED_MPS && courseKnown) targetHeading else routeHeading(here)
                estimator.onFix(here.lat, here.lng, speedMps, course, nowMs)
                targetPuck = here
                if (puck == null) {
                    puck = here // first fix: snap into place, don't glide in from null
                    android.util.Log.i("VelaCar", "first fix from ${loc.provider}")
                }
                if (showingWorld) {
                    // Leave the placeholder world view for the street view the map normally opens at.
                    showingWorld = false
                    zoom = 16.5; zoomTarget = 16.5
                    center = here
                }
                // Posted speed limit (offline graph's max_speed) while navigating — null off-graph/online.
                // The graph LocationIndex snap runs OFF the main thread (this collector is on
                // Main.immediate; a synchronous mmap snap every fix would jank the render loop).
                speedLimitKmh = if ((navigating() || driveScreen) && app.vela.ui.SpeedDisplay.on.value) // hidden: no lookup either
                    withContext(Dispatchers.Default) { runCatching { routeEngine?.currentRoadLimit(here.lat, here.lng) }.getOrNull() }
                else null
                // The arrow's heading (the ticker eases toward this, so it never snap-rotates):
                //  1. Moving with a TRUSTWORTHY GPS course → the actual travel direction (what the
                //     phone nav uses; correct even when off-route). A stationary or low-confidence
                //     bearing is noise (bAcc seen at 134° while parked); that spun the map, so it's
                //     rejected via the speed + bearing-accuracy gate.
                //  2. Else the route SEGMENT bearing (stable road direction; a nearest-vertex search
                //     flickered between adjacent points and swung the view even while driving).
                //  3. Else hold the last heading, never chase noise. With no route the arrow
                //     keeps its course through a stop, and a north-up view still turns it.
                val trustCourse = courseOk && (!loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees <= 45f)
                if (trustCourse) { targetHeading = fixCourse!!; courseKnown = true }
                else if (!courseKnown || speedMps > STOPPED_MPS) routeHeading(here)?.let { targetHeading = it; courseKnown = true }
                if ((previewRoute != null || resultPins.isNotEmpty()) && !navigating()) { requestRender(); return@collect } // a preview or the results own the camera
                // Auto-recenter a few seconds after the user pans (Google-style: pan to look around, then snap back).
                if (!following && !overview && android.os.SystemClock.uptimeMillis() - lastPanMs > RECENTER_MS) following = true
                // The free-drive latch rides the follow: released with it, set again on the next
                // fix at driving speed.
                if (!following) driving = false
                else if (!driving && driveSpeed > DRIVE_LATCH_MPS && courseKnown) driving = true
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
                val db = shortestAngleDelta(heading, targetHeading)
                if (abs(db) > 0.2) { heading = normalizeAngle(heading + db * BEARING_EASE); moved = true }
                // The camera turns onto the arrow's heading, or back to north, and once there it
                // follows the heading exactly, so a turn is not eased twice.
                val camTarget = if (headingUp()) normalizeAngle(heading + turnYaw) else 0.0
                val dc = shortestAngleDelta(camBearing, camTarget)
                if (abs(dc) > 0.3) { camBearing = normalizeAngle(camBearing + dc * CAM_BEARING_EASE); moved = true }
                else if (dc != 0.0) { camBearing = camTarget; moved = true }
                // Zoomed out to a far turn the camera lies flatter, as the phone's does.
                val tiltTarget = if (headingUp()) app.vela.ui.map.NavFraming.tiltAt(zoomTarget, CarFraming.NAV_TILT_DEG) else 0.0
                if (abs(tiltTarget - tilt) > 0.05) { tilt += (tiltTarget - tilt) * TILT_EASE; moved = true }
                else if (tilt != tiltTarget) { tilt = tiltTarget; moved = true }
                if (abs(zoomTarget - zoom) > 0.004) { zoom += (zoomTarget - zoom) * ZOOM_EASE; moved = true }
                if (stepFling(dt)) moved = true
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
        if (heldStill) { app.vela.core.location.Stillness.release(); heldStill = false }
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
        // A screen change hands over a new Surface object for the same buffer queue. The old
        // object keeps the queue's one CPU connection until it is released, and until then every
        // lockCanvas on the new one fails and the map stands still.
        val old = surface
        surface = container.surface
        if (old != null && old !== surface) runCatching { old.release() }
        lockFailed = false
        width = container.width
        height = container.height
        // Clear any in-flight-render state: a surface swap (screen transition) cancels the previous
        // snapshot before its callback fires, so `rendering` would otherwise stay stuck true forever
        // and every future requestRender would no-op — the map freezes after a screen change.
        rendering = false; dirty = false
        if (width <= 0 || height <= 0) return
        runCatching { MapLibre.getInstance(context); app.vela.offline.LocalBasemapTiles.installIntoMapLibre() }
        center = center ?: puck ?: runCatching { locationProvider.lastKnown() }.getOrNull()
        // No location yet (permission not granted, or no fix ever): draw the world rather than a
        // black surface; the first fix flies in to street level.
        if (center == null) {
            center = WORLD_CENTER
            zoom = WORLD_ZOOM; zoomTarget = WORLD_ZOOM
            showingWorld = true
        }
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
        val look = app.vela.ui.MapLook.style.value
        val effectiveStyle = app.vela.ui.map.MapFonts.effective(look.uri)
        // The phone's runtime layers (the plaza and parking fills, the traffic raster) go into
        // the JSON, since the snapshotter cannot add a layer once loaded. Until the patched style
        // exists the plain URL loads, without them. The twins belong to the Vela look: on another
        // look nothing would color them, so only the traffic raster goes in.
        val patchedJson = if (effectiveStyle.startsWith("file://")) {
            runCatching { java.io.File(effectiveStyle.removePrefix("file://")).readText() }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { CarStyle.inject(it, app.vela.ui.map.TRAFFIC_TILES, twins = look.painted) }
        } else null
        loadedPlain = patchedJson == null
        // The layer ids of the style being loaded, for the palette's blanket passes: the patched
        // JSON when there is one, else the bundled Liberty asset (the same layer ids as the live
        // style; it is the phone's offline fallback for the same reason).
        styleLayerIds = runCatching {
            val json = patchedJson ?: context.assets.open("styles/liberty-roboto.json").bufferedReader().use { it.readText() }
            val layers = org.json.JSONObject(json).getJSONArray("layers")
            (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        }.getOrDefault(emptyList())
        val opts = MapSnapshotter.Options(width, height)
            .let { if (patchedJson != null) it.withStyleJson(patchedJson) else it.withStyle(look.uri) }
            .withPixelRatio(1.0f)
            .withLogo(false)
        themed = false
        trafficShown = null; fuelOnlyShown = null
        snapshotter = runCatching { QuietSnapshotter(context, opts) }.getOrNull()?.also { s2 ->
            // Vela's own palette on the car map (the same applyMapTheme the phone runs): until
            // 2026-09-21 the car drew stock Liberty under a darkening color filter, which read as
            // "a weird theme that is not ours" (user). This observer is a backstop: for a JSON style
            // it never fires (the style parses before setObserver runs), so the palette actually
            // goes on from the first snapshot callback in requestRender.
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
        runCatching { surface?.release() }
        surface = null
        runCatching { snapshotter?.cancel() }
        snapshotter = null
        snapWidth = 0; snapHeight = 0
        lastSnapshot = null
        rendering = false; dirty = false // the canceled snapshot's callback won't fire — unstick the flag
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); requestRender() }
    override fun onStableAreaChanged(stableArea: Rect) { stable = Rect(stableArea); requestRender() }
    // The host's STABLE area: the part of the surface no template UI ever covers, in any state.
    // The speed badge and the attribution live in it, because the visible area still had the
    // map action strip stacked over the badge on a tall unit (real drive, 2026-09-22).
    @Volatile private var stable: Rect? = null

    private fun safeArea(): Rect = stable?.takeIf { !it.isEmpty && it.width() > 40 && it.height() > 40 }
        ?: visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)

    /** The part of the surface the map is framed in: the host's visible area, else all of it. */
    private fun framingArea(): Rect =
        visible?.takeIf { !it.isEmpty && it.width() > 0 && it.height() > 0 } ?: Rect(0, 0, width, height)

    /** Ground meters per surface pixel at [lat] for the current zoom (MapLibre's 512 px tiles). */
    private fun metersPerPixel(lat: Double): Double = 78271.517 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)

    /** Moves [center] by a screen offset in surface pixels: right is east, down is south. A pan
     *  always draws north-up (following is off), so no heading needs rotating in. */
    private fun shiftCenter(dxPx: Double, dyPx: Double) {
        val here = center ?: return
        val mpp = metersPerPixel(here.lat)
        center = here.destinationPoint(dxPx * mpp, 90.0).destinationPoint(dyPx * mpp, 180.0)
    }

    private fun stopPanning() {
        following = false
        flingVx = 0.0; flingVy = 0.0
        lastPanMs = android.os.SystemClock.uptimeMillis()
    }

    // The scroll used to set the center to the last snapshot's latLngForPixel(surface center +
    // distance). Panning on Gearslip's preview (2026-10-01) sent the map flying: that snapshot's
    // center is the camera target, which requestRender offsets into the visible area, so every
    // scroll event also jumped the map by that offset; and several events landed on one stale
    // snapshot, so the pan never added up. Each scroll is now a plain metric move of the center.
    override fun onScroll(distanceX: Float, distanceY: Float) {
        stopPanning()
        // GestureDetector convention: distance is previous minus current, so a finger moving
        // left (positive distanceX) shows more of the map to the east.
        shiftCenter(distanceX.toDouble(), distanceY.toDouble())
        requestRender()
    }

    // Pinch zoom keeps the point under the fingers where it is, rather than zooming about the
    // center and sliding what the driver was looking at away.
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        stopPanning() // else the next nav frame overwrites the pinch zoom with navZoom()
        val here = center
        val before = here?.let { metersPerPixel(it.lat) }
        zoom = (zoom + ln(scaleFactor.toDouble()) / ln(2.0)).coerceIn(2.0, 20.0)
        // A host with no focal point (a zoom knob or buttons, not fingers) reports a negative
        // focus; shifting toward it slid the map half a screen per zoom step.
        if (here != null && before != null && focusX >= 0f && focusY >= 0f) {
            // While panning, the center is drawn at the middle of the framing area.
            val area = framingArea()
            // The focus point's ground offset from the center shrinks by (before - after) meters
            // per pixel; shiftCenter measures pixels at the new zoom, so divide by that.
            val after = metersPerPixel(here.lat)
            val k = (before - after) / after
            shiftCenter((focusX - area.exactCenterX()) * k, (focusY - area.exactCenterY()) * k)
        }
        requestRender()
    }

    // A fling glides on from the scroll and slows to a stop; the render ticker moves it along.
    override fun onFling(velocityX: Float, velocityY: Float) {
        stopPanning()
        // The finger's velocity: the map follows the finger, so the center moves the other way.
        flingVx = -velocityX.toDouble()
        flingVy = -velocityY.toDouble()
    }

    /** One ticker step of a fling, [dt] seconds long. True while the map is still gliding. */
    private fun stepFling(dt: Double): Boolean {
        if (flingVx == 0.0 && flingVy == 0.0) return false
        shiftCenter(flingVx * dt, flingVy * dt)
        val decay = exp(-dt / FLING_TAU_S)
        flingVx *= decay; flingVy *= decay
        if (hypot(flingVx, flingVy) < FLING_STOP_PX_S) { flingVx = 0.0; flingVy = 0.0 }
        lastPanMs = android.os.SystemClock.uptimeMillis() // the auto-recenter waits for the glide to end
        return true
    }

    /** Zoom that tightens as you slow (more detail at junctions) and widens at speed. */
    private fun navZoom(): Double = CarFraming.navZoom(speedMps * 3.6)

    /**
     * The following zoom: the speed's, or in a drive heading-up with the phone's "Zoom to the next
     * turn" on, the lesser of that and the zoom that puts the next maneuver [TURN_MARGIN_PX] under
     * the top of the visible area, never below the phone's floor (NavFraming, SPEC 4.7 Framing).
     * Sets [turnYaw], the camera's turn toward that maneuver, zero when the speed's zoom governs.
     */
    private fun followZoom(nav: Boolean): Double {
        val speed = navZoom()
        val from = puck
        val st = navSession.state.value
        if (!nav || from == null || !app.vela.ui.ZoomToTurn.on.value || !headingUp()) { turnYaw = 0.0; return speed }
        if (st.route !== turnRoute || st.nav.stepIndex != turnStep) {
            turnRoute = st.route; turnStep = st.nav.stepIndex
            turnPoint = app.vela.ui.map.NavTurnTarget.pointFor(st.route, st.nav.stepIndex)
        }
        val target = turnPoint ?: run { turnYaw = 0.0; return speed }
        val upPx = framingArea().height() * PUCK_DOWN - TURN_MARGIN_PX
        val raw = app.vela.ui.map.NavFraming.turnZoomFlattening(from.distanceTo(target), upPx, height.toDouble(), CarFraming.NAV_TILT_DEG, from.lat)
        turnYaw = app.vela.ui.map.NavFraming.turnYaw(heading, from.bearingTo(target), speed, raw)
        return app.vela.ui.map.NavFraming.withTurn(speed, raw, app.vela.ui.map.NavFraming.turnFloor())
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
            // The Vela look takes the palette; another OpenFreeMap look keeps its own, as on the phone.
            if (app.vela.ui.MapLook.style.value.painted) app.vela.ui.map.applyMapTheme(
                host,
                dark = night,
                amoled = night && app.vela.ui.theme.AppTheme.mode.value == app.vela.ui.theme.ThemeMode.AMOLED,
            ) else app.vela.ui.map.applyLookTheme(host, dark = night)
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
        if (reloadIfPatched()) return // the rebuilt snapshotter renders on its own
        rendering = true
        // The car flipped day/night since the palette went on: re-theme before this frame.
        if (themed && themedNight != isNight()) applyTheme(snap)

        val nav = navigating()
        if (nav && overview) navSession.state.value.route?.let { r -> frameRoute(remainingRoute(r)); zoomTarget = zoom }
        val follow = following // false while the user has panned (until auto-recenter)
        // Zoom by speed whenever the view follows a moving car: a drive, or driving with no route.
        zoomTarget = if (follow && !overview && previewRoute == null && (nav || driving)) followZoom(nav) else { turnYaw = 0.0; zoom }
        if (themed) applyOverlays(snap, nav)
        // FRAME INSIDE THE VISIBLE AREA. The camera target lands at the bitmap's center, but the host
        // covers part of the surface with its cards, so the point the driver should see (the puck,
        // low in the view while heading-up; the center otherwise) is placed at a pixel inside
        // the visible rect and the target is moved by that pixel offset, rotated into the map's
        // heading. Meters per pixel use MapLibre's 512 px tiles (78271.517 at z0), not the 256 px
        // constant that put the puck twice as far down as intended, off the bottom edge. Tilted,
        // the ground nearer the camera draws larger, which CarFraming.targetOffset accounts for.
        val vis = framingArea()
        val mpp = metersPerPixel(here.lat)
        val up = headingUp()
        val wantX = vis.exactCenterX()
        val wantY = if (up) vis.top + vis.height() * PUCK_DOWN.toFloat() else vis.exactCenterY()
        // SIDEWAYS, THE CENTER OF PERSPECTIVE MOVES, NOT THE TARGET. Tilted, the road straight
        // ahead runs straight up the screen only through the camera's center of perspective, and
        // the host's cards push the arrow off the surface's middle: with the target moved across
        // instead, a straight road through the arrow leaned toward the middle (14 degrees on a
        // 1280 px unit with the arrow 234 px right of it) under an arrow that stayed upright.
        // Camera padding puts the center of perspective on the arrow's column, so the target
        // offset is only the one down the screen.
        val (aheadPx, _) = CarFraming.targetOffset((wantY - height / 2f).toDouble(), 0.0, tilt, height.toDouble())
        val bearingUsed = camBearing // eased onto the heading, or back to north, by the ticker
        val anchor = if (follow && (nav || driving) && previewRoute == null) (puck ?: here) else here
        val target = anchor.destinationPoint(aheadPx * mpp, bearingUsed)

        val cam = CameraPosition.Builder()
            .target(MLLatLng(target.lat, target.lng))
            .zoom(zoom)
            .bearing(bearingUsed)
            .tilt(tilt)
            .padding((2f * wantX - width).coerceAtLeast(0f).toDouble(), 0.0, (width - 2f * wantX).coerceAtLeast(0f).toDouble(), 0.0)
            .build()
        // The ground this camera can draw, kept with the snapshot it takes: a tilted camera has
        // ground behind it, and the overlays must not project a point from there.
        val clip = FrontClip(target.lat, target.lng, bearingUsed, CarFraming.behindLimitPx(tilt, height.toDouble()) * mpp)
        val startedMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            snap.setSize(width, height)
            snap.setCameraPosition(cam)
            snap.start({ result ->
                rendering = false
                // Frame cost per snapshot, for a tile or raster that slows the map: `setprop
                // log.tag.VelaCar DEBUG` on the phone turns it on.
                if (android.util.Log.isLoggable("VelaCar", android.util.Log.DEBUG)) {
                    android.util.Log.d(
                        "VelaCar",
                        "snapshot ${android.os.SystemClock.elapsedRealtime() - startedMs} ms zoom=${"%.1f".format(java.util.Locale.US, zoom)} tilt=${tilt.toInt()} yaw=${turnYaw.toInt()} traffic=${trafficShown == true} speed=${(speedMps * 3.6).toInt()}",
                    )
                }
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
                draw(result, bearingUsed, clip)
                if (dirty) { dirty = false; requestRender() }
            }, { rendering = false })
        }.onFailure { rendering = false }
    }

    private var trafficShown: Boolean? = null
    private var fuelOnlyShown: Boolean? = null
    // The style loaded from the plain URL, without the car layers, because the patched file did
    // not exist yet (a first launch, or the font host down). Once it exists the style is built
    // again, so the Traffic switch and the fills do not stay dead until the next connection.
    private var loadedPlain = false

    private fun patchedStyleReady(): Boolean = app.vela.ui.map.MapFonts.effective(app.vela.ui.MapLook.style.value.uri).startsWith("file://")

    // Once per session: a patched file that is listed but cannot be read would otherwise rebuild
    // the snapshotter from inside its own surface callback without end.
    private var reloadedForPatched = false

    /** Rebuilds the snapshotter on the patched style when it has appeared since the plain one loaded. */
    private fun reloadIfPatched(): Boolean {
        if (!loadedPlain || reloadedForPatched || !patchedStyleReady() || surface == null) return false
        reloadedForPatched = true
        runCatching { snapshotter?.cancel() }
        snapshotter = null
        snapWidth = 0; snapHeight = 0
        rendering = false; dirty = false
        surface?.let { onSurfaceAvailable(SurfaceContainer(it, width, height, context.resources.displayMetrics.densityDpi)) }
        return true
    }

    /** The style's switchable layers, set when their state changes: Google's traffic raster with
     *  no route when the phone's Traffic layer is on (and Google is allowed), as the phone shows
     *  it; and the basemap's place icons cut to fuel during a drive, as on the phone. */
    private fun applyOverlays(s: MapSnapshotter, nav: Boolean) {
        val traffic = app.vela.ui.Traffic.on.value && !app.vela.ui.GoogleFree.on.value && !nav
        if (traffic != trafficShown) {
            trafficShown = traffic
            runCatching {
                s.getLayer(CarStyle.TRAFFIC_LAYER)?.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.visibility(
                        if (traffic) org.maplibre.android.style.layers.Property.VISIBLE else org.maplibre.android.style.layers.Property.NONE,
                    ),
                )
            }
        }
        if (nav != fuelOnlyShown) {
            fuelOnlyShown = nav
            runCatching { app.vela.ui.map.applyPoiTierFilters(app.vela.ui.map.SnapshotterHost(s, styleLayerIds), fuelOnly = nav) }
        }
    }

    private var lockFailed = false

    // The ground the snapshot being drawn can show ([FrontClip]); set by [draw] for [project].
    private var frontClip: FrontClip? = null

    /** Draws [snap], rendered with the camera bearing [camUsed], and the overlays on it. [clip]
     *  is the ground that camera can show. */
    private fun draw(snap: MapSnapshot, camUsed: Double, clip: FrontClip) {
        frontClip = clip
        val s = surface ?: return
        if (!s.isValid || width <= 0 || height <= 0) return
        val bmp = runCatching { snap.bitmap }.getOrNull()
        val canvas: Canvas = try { s.lockCanvas(null) } catch (t: Throwable) {
            if (!lockFailed) { lockFailed = true; android.util.Log.w("VelaCar", "car surface would not lock: ${t.javaClass.simpleName}") }
            return
        }
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
            } else if (preview != null && preview.polyline.size >= 2) {
                // Preview screen: the whole route in blue, framed.
                for (other in previewOthers) {
                    if (other.polyline.size >= 2) runCatching { canvas.drawPath(pathOf(snap, other.polyline, other.polyline.indices, sx, sy), otherRoutePaint) }
                }
                runCatching { canvas.drawPath(pathOf(snap, preview.polyline, preview.polyline.indices, sx, sy), trafficPaints[0]!!) }
                runCatching { drawDestination(canvas, snap, preview.polyline.last(), sx, sy) }
            }
            if (!navigating()) resultPins.forEachIndexed { i, at -> runCatching { drawResultPin(canvas, snap, at, i + 1, sx, sy) } }
            runCatching { drawPuck(canvas, snap, sx, sy, camUsed) } // puck always drawn
            // The speed badge on the drive screen and in drive mode, unless Settings > Navigation
            // "Show speed and speed limit" is off (issue #625).
            if (badges && (navigating() || driveScreen) && app.vela.ui.SpeedDisplay.on.value) runCatching { drawSpeed(canvas) }
            runCatching { drawAttribution(canvas) }
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    /** [p] on the surface, or null when it is behind the snapshot's camera: tilted, the
     *  projection answers for such a point with its mirror image (issue 780). */
    private fun project(snap: MapSnapshot, p: LatLng, sx: Float, sy: Float): PointF? {
        if (frontClip?.inFront(p.lat, p.lng) == false) return null
        return projectRaw(snap, p.lat, p.lng, sx, sy)
    }

    private fun projectRaw(snap: MapSnapshot, lat: Double, lng: Double, sx: Float, sy: Float): PointF? {
        val px = runCatching { snap.pixelForLatLng(MLLatLng(lat, lng)) }.getOrNull() ?: return null
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
        // A paused drive is lavender ahead, as on the phone, with no traffic colors over it.
        val paused = navSession.state.value.paused
        if (splitI > 0) canvas.drawPath(pathOf(snap, poly, 0..splitI, sx, sy), drivenPaint)
        if (splitI < poly.lastIndex) canvas.drawPath(pathOf(snap, poly, splitI..poly.lastIndex, sx, sy), if (paused) pausedPaint else trafficPaints[0]!!)
        runCatching { drawDestination(canvas, snap, poly.last(), sx, sy) }
        if (paused) return

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

    private fun drawResultPin(canvas: Canvas, snap: MapSnapshot, at: LatLng, n: Int, sx: Float, sy: Float) {
        val pt = project(snap, at, sx, sy) ?: return
        drawNumberedPin(canvas, pt.x, pt.y, 17f, n)
    }

    /** The destination: a red dot with a white ring, where the route ends. */
    private fun drawDestination(canvas: Canvas, snap: MapSnapshot, at: LatLng, sx: Float, sy: Float) {
        val pt = project(snap, at, sx, sy) ?: return
        canvas.drawCircle(pt.x, pt.y, 13f, pinFill)
        canvas.drawCircle(pt.x, pt.y, 13f, pinRing)
        canvas.drawCircle(pt.x, pt.y, 4.5f, pinDot)
    }

    /** The line through [poly]'s [range], cut where it passes behind the snapshot's camera: a
     *  stretch back there is left out, and the line ends and picks up again at the crossing, so a
     *  long segment with one end behind still draws its visible part. */
    private fun pathOf(snap: MapSnapshot, poly: List<LatLng>, range: IntRange, sx: Float, sy: Float): Path {
        val p = Path()
        val clip = frontClip
        var penDown = false
        var prev: LatLng? = null
        var prevIn = false
        for (i in range) {
            val cur = poly[i]
            val curIn = clip?.inFront(cur.lat, cur.lng) != false
            val before = prev
            if (clip != null && before != null && prevIn != curIn) {
                val t = clip.crossing(before.lat, before.lng, cur.lat, cur.lng)
                projectRaw(snap, before.lat + (cur.lat - before.lat) * t, before.lng + (cur.lng - before.lng) * t, sx, sy)?.let { x ->
                    if (prevIn && penDown) { p.lineTo(x.x, x.y); penDown = false } else if (curIn) { p.moveTo(x.x, x.y); penDown = true }
                }
            }
            if (curIn) {
                projectRaw(snap, cur.lat, cur.lng, sx, sy)?.let { pt ->
                    if (penDown) p.lineTo(pt.x, pt.y) else { p.moveTo(pt.x, pt.y); penDown = true }
                }
            } else {
                penDown = false
            }
            prev = cur; prevIn = curIn
        }
        return p
    }

    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f; setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    /** The ODbL credit, ours and only ours: the library's own overlay ([QuietSnapshotter]) printed
     *  every source's attribution string, which on this basemap is a line of tile-provider names
     *  (user 2026-09-21, "the watermark is wrong and says way more than just OpenStreetMap"). The
     *  phone map shows the same single line. Drawn inside [safeArea]: the host's stable area first,
     *  the visible area only when no usable stable area was reported. */
    private fun drawAttribution(canvas: Canvas) {
        val vis = safeArea()
        attributionPaint.textSize = (minOf(width, height) / 45f).coerceIn(9f, 14f)
        canvas.drawText(ATTRIBUTION, vis.left + 8f, vis.bottom - 8f, attributionPaint)
    }

    private fun drawPuck(canvas: Canvas, snap: MapSnapshot, sx: Float, sy: Float, camUsed: Double) {
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
        // Turned to the heading as the map draws it: a point a little ahead of the arrow along
        // its heading, projected, gives the direction on the screen. Tilted, a turn on the ground
        // is a wider turn in the picture, so the heading against the camera's bearing (the flat
        // answer, kept for when the point cannot be projected) under-turned the arrow whenever
        // the camera was still easing onto a new heading.
        val aim = here.destinationPoint(PUCK_AIM_PX * metersPerPixel(here.lat), heading).let { projectRaw(snap, it.lat, it.lng, sx, sy) }
        val turn = if (aim != null && hypot(aim.x - pt.x, aim.y - pt.y) >= 2f) {
            Math.toDegrees(Math.atan2((aim.x - pt.x).toDouble(), (pt.y - aim.y).toDouble()))
        } else heading - camUsed
        canvas.save()
        canvas.rotate(turn.toFloat(), pt.x, pt.y)
        canvas.drawBitmap(bmp, pt.x - px / 2f, pt.y - px / 2f, dayBitmapPaint)
        canvas.restore()
    }

    /** Bottom-right current-speed badge (km/h or mph per the user's units), plus the posted limit
     *  to its left when it is known: the US "SPEED LIMIT" sign in miles, the round red-ring sign
     *  in kilometers, the same choice the phone makes. The number turns red past the limit. */
    private fun drawSpeed(canvas: Canvas) {
        val imperial = app.vela.ui.Units.imperial.value
        val v = if (imperial) speedMps * 2.236936 else speedMps * 3.6
        val num = v.roundToInt().coerceAtLeast(0)
        val unit = if (imperial) "mph" else "km/h"
        // Inside [safeArea] (the host's STABLE area first, then the visible area), not the
        // surface's corner: the template's map action strip (overview, zoom) sits over the
        // surface's bottom right, and the badge drew under it (user 2026-09-22, stock Pixel 9). Scaled like the puck so a small unit keeps room.
        val vis = safeArea()
        // Sized with the screen, text and the limit sign included (issue #817): on a small unit
        // the badge and the sign at their old fixed sizes took more of the screen than the
        // template's own bars. 46 px radius is the old look, kept on a 720 px screen and up.
        val rad = (minOf(width, height) / 18f).coerceIn(22f, 46f)
        val k = rad / 46f
        badgeNum.textSize = 42f * k; badgeUnit.textSize = 18f * k
        limitNum.textSize = 36f * k; limitWord.textSize = 13f * k
        limitRing.strokeWidth = 7f * k; limitEdge.strokeWidth = 3f * k
        val cx = vis.right - rad - 16f; val cy = vis.bottom - rad - 20f
        // The badge itself is off by default on the car (CarSpeed): the car has a speedometer.
        // Without it the limit sign takes the badge's corner.
        val badge = app.vela.ui.CarSpeed.on.value
        if (badge) {
            canvas.drawRoundRect(RectF(cx - rad, cy - rad, cx + rad, cy + rad), 20f * k, 20f * k, badgeBg)
            canvas.drawText(num.toString(), cx, cy + 6f * k, badgeNum)
            canvas.drawText(unit, cx, cy + 30f * k, badgeUnit)
        }

        speedLimitKmh?.let { kmh ->
            val limit = (if (imperial) kmh / 1.609344 else kmh).roundToInt()
            if (limit <= 0) return
            limitNum.color = if (v > limit + (if (imperial) 3 else 5)) LIMIT_OVER else LIMIT_INK
            val ly = cy
            if (imperial) {
                val hw = 34f * k; val hh = 44f * k
                val lx = if (badge) cx - rad - 14f * k - hw else vis.right - 16f - hw
                val sign = RectF(lx - hw, ly - hh, lx + hw, ly + hh)
                canvas.drawRoundRect(sign, 9f * k, 9f * k, limitDisc)
                canvas.drawRoundRect(sign, 9f * k, 9f * k, limitEdge)
                canvas.drawText("SPEED", lx, ly - 24f * k, limitWord)
                canvas.drawText("LIMIT", lx, ly - 10f * k, limitWord)
                canvas.drawText(limit.toString(), lx, ly + 30f * k, limitNum)
            } else {
                val lr = 42f * k
                val lx = if (badge) cx - rad - 14f * k - lr else vis.right - 16f - lr
                canvas.drawCircle(lx, ly, lr, limitDisc)
                canvas.drawCircle(lx, ly, lr, limitRing)
                canvas.drawText(limit.toString(), lx, ly + 14f * k, limitNum)
            }
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
    // Lights and stop signs are the phone's own glyphs (issue #844: dots the size of a pinhead
    // were lost on a car screen); the rest stay dots.
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val glyphBlit = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lightGlyph by lazy { app.vela.ui.map.trafficLightBitmap() }
    private val stopGlyph by lazy { app.vela.ui.map.stopSignBitmap() }
    private val glyphRect = android.graphics.RectF()
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
        val signPx = controlGlyphPx(minOf(width, height), zoom)
        fun glyph(at: LatLng, bmp: android.graphics.Bitmap, h: Float) {
            val pt = project(snap, at, sx, sy) ?: return
            val w = h * bmp.width / bmp.height
            glyphRect.set(pt.x - w / 2f, pt.y - h / 2f, pt.x + w / 2f, pt.y + h / 2f)
            canvas.drawBitmap(bmp, null, glyphRect, glyphBlit)
        }
        app.vela.car.CarBridge.controls.value.forEach { c ->
            when (c.kind) {
                app.vela.core.data.TrafficControl.Kind.SIGNAL -> glyph(c.loc, lightGlyph, signPx * LIGHT_TALL)
                app.vela.core.data.TrafficControl.Kind.STOP -> glyph(c.loc, stopGlyph, signPx)
                app.vela.core.data.TrafficControl.Kind.RAIL_CROSSING -> dot(c.loc, Color.parseColor("#37474F"))
                app.vela.core.data.TrafficControl.Kind.SPEED_HUMP -> dot(c.loc, Color.parseColor("#E8923D"))
            }
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
        framePoints(route?.polyline ?: return)
    }

    /** Center on [poly]'s bounding box at a zoom that fits it, north up. */
    private fun framePoints(poly: List<LatLng>) {
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
    }

    /** Heading = the bearing of the route SEGMENT the puck is on, not the bearing to the nearest
     *  vertex. A nearest-vertex search flickers between adjacent points on the ~2 m GPS jitter you get
     *  while parked, swinging the whole heading-up view ("moving in all directions in the driveway").
     *  The segment bearing is stable: small jitter keeps you on the same segment, so the heading holds. */
    private fun routeHeading(here: LatLng): Double? {
        val st = navSession.state.value
        val route = st.route ?: return null
        val poly = route.polyline
        if (poly.size < 2) return null
        // On the route, the direction at the engine's own progress along it (NavState.traveledM),
        // the way OsmAnd and Organic Maps point the arrow: anchored to the route, not to the fix,
        // so a wide fix at a corner cannot flip it between the two streets. Off the route, or
        // before the first progress, the segment nearest the fix.
        if (!st.nav.offRoute && st.nav.traveledM > 0.0) {
            val cum = routeCum?.takeIf { it.first === route }?.second
                ?: (route to app.vela.core.nav.RouteProjection.cumulative(poly)).also { routeCum = it }.second
            return app.vela.core.nav.RouteProjection.bearingAt(poly, cum, st.nav.traveledM.coerceAtMost(cum.last()))
        }
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
