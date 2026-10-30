package app.vela.ui.map

import android.graphics.PointF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.Scroller
import app.vela.ui.AppTune
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The map's zoom gestures, in place of MapLibre's: the pinch, and the one-finger zoom (tap, touch
 * again and drag: down zooms in, up zooms out). MapLibre keeps the pan, the turn, the tilt, the
 * double tap and the two-finger tap.
 *
 * MapLibre's pinch starts only when the span changes faster than a set speed between two touch
 * events (0.6 dp/ms by default, 0.15 as Vela had it), so a slower pinch pans instead, and both of
 * its zoom gestures stop dead at the lift. Here a pinch starts after [PINCH_START_INCH] of span
 * change at any speed, and a doubled span is one level about the fingers' midpoint. The one-finger
 * zoom moves [QUICK_LEVELS_PER_HEIGHT] levels per map height about the screen center, after
 * [QUICK_DEAD_DP].
 *
 * The fingers add to a pending zoom `a`. Each frame the camera takes `sign(a) min(|a|, 10 a^2)` of
 * it ([DRAIN_GAIN]): a large change goes through at once and a small one eases in. After the lift
 * the map flings: one fling for the pan and the zoom together, on the curve of Android's [Scroller],
 * the deceleration of a list fling on the same phone. MapLibre keeps the drag and its own fling is
 * off. The zoom's release speed is the finger's vertical speed for the one-finger zoom, and for a
 * pinch the span's rate when the first finger lifts, projected 1 ms ahead. [FLING_LEVEL_OF_SCREEN]
 * of the screen height counts as one level, at most [FLING_MAX_LEVELS] in all. The pan's is the
 * fingers' average velocity, uncapped; with several fingers it is taken when the first one lifts if
 * the last follows within [LIFT_SNAPSHOT_MS]. A zoom speed over [FLING_MAX_LEVELS_PER_S] scales both
 * down alike. Each channel travels the distance a Scroller fling of its own speed would, and the
 * fastest one in pixels drives the shared curve, so both end together. A one-finger zoom does not
 * pan, and the pan does not fling during a tilt, or after a pinch or a turn while the camera
 * follows. While the fling runs the pending zoom is applied in full.
 *
 * The camera moves through [MapLibreMap.setZoom], as MapLibre's own gestures move it, so a frame
 * fires no camera idle. When a zoom and its fling end, [Listener.onZoomEnd] runs and then one
 * [MapLibreMap.moveCamera] of [SETTLE_ZOOM] fires the move-started and idle pair the map view
 * waits on. The listener marks that move as the user's.
 */
internal class MapZoomGestures(
    private val map: MapLibreMap,
    private val view: View,
    private val listener: Listener,
) {
    interface Listener {
        fun onZoomBegin()
        fun onZoom()
        fun onZoomEnd()
        /** The camera follows a position (a drive, or the free-drive follow). */
        fun following(): Boolean
        /** The camera move that closes a gesture or its fling is on its way, and is the user's. */
        fun onSettle()
    }

    /** SETTLE: the fingers are up and the pending zoom or the fling is still running. */
    private enum class Mode { IDLE, PINCH, QUICK, SETTLE }

    private var mode = Mode.IDLE
    private val gestures = map.gesturesManager
    private val density = view.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val doubleTapSlop = ViewConfiguration.get(view.context).scaledDoubleTapSlop.toFloat()

    // The one-finger zoom: the last tap's lift, and the finger that is down now.
    private var tapUpMs = Long.MIN_VALUE / 2
    private var tapX = 0f
    private var tapY = 0f
    private var downMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var tapping = false // one finger down that has not moved past the touch slop
    private var quickArmed = false
    private var lastY = 0f
    // MapLibre was handed a cancel for this touch: every event up to the last lift is ours.
    private var owned = false

    private var startSpan = 0f
    private var startFocalY = 0f
    private var lastSpan = 0f
    private val focal = PointF()

    // Dials, read when a zoom begins.
    private var pinchStartPx = 0f
    private var quickPxPerLevel = 1f
    private var quickDeadPx = 0f

    private var target = 0.0 // the camera zoom plus the pending zoom
    private var framePosted = false
    private val frame = Choreographer.FrameCallback { step() }

    private val velocity = VelocityTracker.obtain()
    private val driver = Scroller(view.context, null, false) // no flywheel: a new fling never adds to the last
    private val flingPxPerLevel = FLING_LEVEL_OF_SCREEN * view.resources.displayMetrics.heightPixels
    private var flinging = false
    private var lastFraction = 0f
    private var flingPanX = 0f // the fling's whole travel, in screen px and zoom levels
    private var flingPanY = 0f
    private var flingZoom = 0.0
    private var zoomActive = false // onZoomBegin was told and onZoomEnd is owed
    private var pinchFling = 0.0 // levels a second, taken when the pinch's first finger lifts

    // What this touch did, for the pan fling at the last lift.
    private var panned = false
    private var pinched = false
    private var turned = false
    private var tilted = false
    private var liftMs = 0L
    private var snapVx = 0f
    private var snapVy = 0f
    private var hasSnap = false

    init {
        gestures.standardScaleGestureDetector.isEnabled = false
        map.uiSettings.isFlingVelocityAnimationEnabled = false
    }

    /** Puts MapLibre's own zoom gestures back. */
    fun release() {
        stopFrames()
        velocity.recycle()
        mode = Mode.IDLE
        gestures.standardScaleGestureDetector.isEnabled = true
        map.uiSettings.isFlingVelocityAnimationEnabled = true
        gestures.rotateGestureDetector.isEnabled = true
        gestures.shoveGestureDetector.isEnabled = true
        gestures.multiFingerTapGestureDetector.isEnabled = true
    }

    /** True when the event is taken and MapLibre must not see it. */
    fun onTouch(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { velocity.clear(); pinchFling = 0.0 }
        velocity.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.SETTLE) end() // a touch stops the fling
                // A zoom set these aside until its fingers lifted.
                gestures.shoveGestureDetector.isEnabled = true
                gestures.multiFingerTapGestureDetector.isEnabled = true
                owned = false
                panned = false; pinched = false; turned = false; tilted = false
                liftMs = 0L; hasSnap = false
                downMs = ev.eventTime; downX = ev.x; downY = ev.y
                tapping = true
                quickArmed = zoomAllowed() &&
                    ev.eventTime - tapUpMs <= ViewConfiguration.getDoubleTapTimeout() &&
                    hypot(ev.x - tapX, ev.y - tapY) <= doubleTapSlop
                if (quickArmed) quickDeadPx = (AppTune.value("quickZoomDeadDp", QUICK_DEAD_DP.toDouble()).toFloat() * density).roundToInt().toFloat()
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapping = false
                quickArmed = false
                if (owned) {
                    if (mode == Mode.QUICK) lift()
                    return true
                }
                liftMs = 0L
                if (mode == Mode.SETTLE) end()
                if (mode == Mode.PINCH) lastSpan = span(ev, -1) // the span jumps with a new finger
                else rebase(span(ev, -1))
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapping && hypot(ev.x - downX, ev.y - downY) > touchSlop) tapping = false
                if (!owned) {
                    if (gestures.moveGestureDetector.isInProgress) panned = true
                    if (gestures.rotateGestureDetector.isInProgress) turned = true
                    if (gestures.shoveGestureDetector.isInProgress) tilted = true
                }
                when {
                    owned -> if (mode == Mode.QUICK) quickMove(ev)
                    ev.pointerCount >= 2 -> pinchMove(ev)
                    quickArmed && abs(ev.y - downY).roundToInt() > maxOf(touchSlop, quickDeadPx) -> startQuick(ev)
                }
                return owned
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (owned) return true
                liftMs = ev.eventTime
                averageVelocity(ev).let { (vx, vy) -> snapVx = vx; snapVy = vy; hasSnap = true } // the lifting finger counts
                if (ev.pointerCount - 1 >= 2) {
                    if (mode == Mode.PINCH) lastSpan = span(ev, ev.actionIndex) else rebase(span(ev, ev.actionIndex))
                } else if (mode == Mode.PINCH) {
                    // Taken at the first lift, so the finger left on the screen adds nothing. The
                    // fling waits for that finger to lift.
                    pinchFling = pinchSpeed(ev)
                    lift()
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                val took = owned
                owned = false
                if (!took && gestures.moveGestureDetector.isInProgress) panned = true // a flick of a few events
                var zoomSpeed = pinchFling // about the last pinch focal
                if (mode == Mode.QUICK) {
                    velocity.computeCurrentVelocity(1000)
                    zoomSpeed = quickLevelsPerHeight() * velocity.getYVelocity(ev.getPointerId(0)) / view.height
                    focal.set(view.width / 2f, view.height / 2f)
                }
                val (panVx, panVy) = if (!took && panned && !tilted && map.uiSettings.isScrollGesturesEnabled &&
                    !((pinched || turned) && listener.following())
                ) {
                    if (hasSnap && ev.eventTime <= liftMs + LIFT_SNAPSHOT_MS) snapVx to snapVy else averageVelocity(ev)
                } else 0f to 0f
                if (mode == Mode.QUICK || mode == Mode.PINCH) lift()
                fling(panVx, panVy, zoomSpeed)
                pinchFling = 0.0
                // A single tap arms the one-finger zoom for the next touch. The second tap of a
                // double tap does not, or a third tap would arm it again.
                tapUpMs = if (tapping && !quickArmed && !took &&
                    ev.eventTime - downMs < ViewConfiguration.getLongPressTimeout()
                ) ev.eventTime else Long.MIN_VALUE / 2
                tapX = ev.x; tapY = ev.y
                tapping = false
                quickArmed = false
                return took
            }
            MotionEvent.ACTION_CANCEL -> {
                val took = owned
                owned = false
                if (mode == Mode.QUICK || mode == Mode.PINCH) lift()
                pinchFling = 0.0
                tapUpMs = Long.MIN_VALUE / 2
                tapping = false
                quickArmed = false
                return took
            }
        }
        return owned
    }

    private fun zoomAllowed() = map.uiSettings.isZoomGesturesEnabled

    private fun quickLevelsPerHeight() = AppTune.value("quickZoomPerHeight", QUICK_LEVELS_PER_HEIGHT)

    private fun pinchMove(ev: MotionEvent) {
        if (mode == Mode.QUICK) return
        val s = span(ev, -1)
        if (mode != Mode.PINCH) {
            if (startSpan <= 0f || abs(s - startSpan) < pinchStartPx()) return
            // A two-finger drag up or down is a tilt, and its span wobbles a few dp. A pinch
            // changes the span at least half as much as the fingers' center moves up or down.
            if (map.uiSettings.isTiltGesturesEnabled && abs(s - startSpan) < TILT_GUARD * abs(focal.y - startFocalY)) return
            if (!zoomAllowed() || gestures.shoveGestureDetector.isInProgress) return
            begin(Mode.PINCH)
            pinched = true
            lastSpan = startSpan
        }
        if (s <= 0f || lastSpan <= 0f) return
        val r = s / lastSpan
        if (r > PINCH_SKIP_LOW && r < PINCH_SKIP_HIGH) return // too small: wait, measured from the last span used
        zoomBy(ln(r.toDouble()) / LN2)
        lastSpan = s
    }

    /** A pinch starts after [PINCH_START_INCH] of span change, twice that while the map is turning. */
    private fun pinchStartPx(): Float {
        val dm = view.resources.displayMetrics
        val inch = AppTune.value("pinchStartInch", PINCH_START_INCH.toDouble()).toFloat()
        return inch * (dm.xdpi + dm.ydpi) / 2f * if (gestures.rotateGestureDetector.isInProgress) 2f else 1f
    }

    /** Measures the pinch that has not started from [s] and the [focal] it was taken at. */
    private fun rebase(s: Float) {
        startSpan = s
        lastSpan = s
        startFocalY = focal.y
    }

    private fun startQuick(ev: MotionEvent) {
        owned = true
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        view.onTouchEvent(cancel)
        cancel.recycle()
        focal.set(view.width / 2f, view.height / 2f)
        lastY = ev.y // the zoom counts from where the dead zone was crossed
        begin(Mode.QUICK)
    }

    private fun quickMove(ev: MotionEvent) {
        zoomBy(((ev.y - lastY) / quickPxPerLevel).toDouble())
        lastY = ev.y
    }

    private fun begin(m: Mode) {
        mode = m
        quickPxPerLevel = view.height / quickLevelsPerHeight().toFloat()
        // MapLibre makes a pinch and a tilt shut each other out, and keeps a short pinch from
        // reading as a two-finger tap. Its own pinch is off, so this does both.
        gestures.shoveGestureDetector.isEnabled = false
        gestures.multiFingerTapGestureDetector.isEnabled = false
        // MapLibre's isDisableRotateWhenScaling, which its pinch applied: a pinch that came first
        // holds the turn off until it ends.
        if (m == Mode.PINCH && map.uiSettings.isDisableRotateWhenScaling &&
            !gestures.rotateGestureDetector.isInProgress
        ) gestures.rotateGestureDetector.isEnabled = false
        if (!gestures.moveGestureDetector.isInProgress) map.cancelTransitions()
        target = map.zoom
        zoomActive = true
        listener.onZoomBegin()
    }

    private fun zoomBy(levels: Double) {
        if (levels == 0.0 || levels.isNaN()) return
        target = (target + levels).coerceIn(map.minZoomLevel, map.maxZoomLevel)
        postFrame()
    }

    /** The fingers are up: finish the pending zoom. */
    private fun lift() {
        mode = Mode.SETTLE
        postFrame()
    }

    /** The fingers' average velocity in px a second, uncapped. */
    private fun averageVelocity(ev: MotionEvent): Pair<Float, Float> {
        velocity.computeCurrentVelocity(1000)
        var vx = 0f
        var vy = 0f
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            vx += velocity.getXVelocity(id); vy += velocity.getYVelocity(id)
        }
        return vx / ev.pointerCount to vy / ev.pointerCount
    }

    /** The pinch's zoom speed when its first finger lifts: the span projected 1 ms ahead. */
    private fun pinchSpeed(ev: MotionEvent): Double {
        if (ev.pointerCount < 2) return 0.0
        velocity.computeCurrentVelocity(1) // px per ms
        val a = ev.getPointerId(0)
        val b = ev.getPointerId(1)
        val s0 = hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))
        val s1 = hypot(
            (ev.getX(1) + velocity.getXVelocity(b)) - (ev.getX(0) + velocity.getXVelocity(a)),
            (ev.getY(1) + velocity.getYVelocity(b)) - (ev.getY(0) + velocity.getYVelocity(a)),
        )
        return if (s0 > 0f && s1 > 0f) ln((s1 / s0).toDouble()) / LN2 * 1000 else 0.0
    }

    /** Where a Scroller fling at [v] px a second, held to [min]..[max], comes to rest. */
    private fun flingDistance(v: Int, min: Int, max: Int): Int =
        Scroller(view.context, null, false).run { fling(0, 0, v, 0, min, max, 0, 0); finalX }

    /** One fling for the pan ([panVx], [panVy], the fingers' px a second) and the zoom ([zoomSpeed] levels a second). */
    private fun fling(panVx: Float, panVy: Float, zoomSpeed: Double) {
        var vx = panVx
        var vy = panVy
        var vz = zoomSpeed
        if (abs(vz) > FLING_MAX_LEVELS_PER_S) {
            val k = FLING_MAX_LEVELS_PER_S / abs(vz)
            vx *= k.toFloat(); vy *= k.toFloat(); vz *= k
        }
        val panSpeed = hypot(vx, vy).roundToInt()
        val zoomPx = (vz * flingPxPerLevel).roundToInt()
        if (panSpeed == 0 && zoomPx == 0) return
        val zoomMax = (FLING_MAX_LEVELS * flingPxPerLevel).toInt()
        val panTravel = if (panSpeed == 0) 0 else flingDistance(panSpeed, Int.MIN_VALUE, Int.MAX_VALUE)
        flingPanX = if (panSpeed == 0) 0f else panTravel * vx / panSpeed
        flingPanY = if (panSpeed == 0) 0f else panTravel * vy / panSpeed
        flingZoom = if (zoomPx == 0) 0.0 else flingDistance(zoomPx, -zoomMax, zoomMax) / flingPxPerLevel.toDouble()
        if (mode == Mode.IDLE) target = map.zoom
        if (flingZoom != 0.0 && !zoomActive) { // a pinch whose pending zoom finished before the last lift
            zoomActive = true
            listener.onZoomBegin()
        }
        mode = Mode.SETTLE
        driver.forceFinished(true)
        if (panSpeed >= abs(zoomPx)) driver.fling(0, 0, panSpeed, 0, Int.MIN_VALUE, Int.MAX_VALUE, 0, 0)
        else driver.fling(0, 0, abs(zoomPx), 0, 0, zoomMax, 0, 0)
        lastFraction = 0f
        flinging = true
        postFrame()
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopFrames() {
        if (flinging) { driver.forceFinished(true); flinging = false }
        Choreographer.getInstance().removeFrameCallback(frame)
        framePosted = false
    }

    /** One frame: advance the fling, then hand the camera its share of the pending zoom. */
    private fun step() {
        framePosted = false
        if (mode == Mode.IDLE) return
        if (flinging) {
            if (driver.computeScrollOffset()) {
                val fraction = if (driver.finalX == 0) 1f else driver.currX.toFloat() / driver.finalX
                val d = fraction - lastFraction
                lastFraction = fraction
                if (flingPanX != 0f || flingPanY != 0f) map.scrollBy(flingPanX * d, flingPanY * d)
                if (flingZoom != 0.0) target = (target + flingZoom * d).coerceIn(map.minZoomLevel, map.maxZoomLevel)
            } else flinging = false
        }
        val z = map.zoom
        val a = target - z
        if (abs(a) > SETTLED_LEVELS) {
            val move = when {
                flinging || abs(a) <= SNAP_LEVELS -> a
                a < 0 -> maxOf(a, -DRAIN_GAIN * a * a)
                else -> minOf(a, DRAIN_GAIN * a * a)
            }
            map.setZoom(z + move, focal, 0)
            listener.onZoom()
        }
        when {
            flinging || abs(a) > SETTLED_LEVELS -> postFrame()
            mode == Mode.SETTLE -> end()
            // else the fingers are holding still: the next move posts a frame
        }
    }

    private fun end() {
        stopFrames()
        mode = Mode.IDLE
        gestures.rotateGestureDetector.isEnabled = true
        if (zoomActive) { zoomActive = false; listener.onZoomEnd() }
        listener.onSettle()
        runCatching { map.moveCamera(CameraUpdateFactory.zoomBy(SETTLE_ZOOM)) }
    }

    /** Twice the fingers' mean distance from their center, leaving out pointer [skip]; sets [focal]. */
    private fun span(ev: MotionEvent, skip: Int): Float {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) if (i != skip) { sx += ev.getX(i); sy += ev.getY(i); n++ }
        if (n < 2) return 0f
        val fx = sx / n
        val fy = sy / n
        var d = 0f
        for (i in 0 until ev.pointerCount) if (i != skip) d += hypot(ev.getX(i) - fx, ev.getY(i) - fy)
        focal.set(fx, fy)
        return 2f * d / n
    }

    private companion object {
        val LN2 = ln(2.0)
        /** Span change before a pinch zooms, in inches of the screen. Dial `pinchStartInch`. */
        const val PINCH_START_INCH = 0.1f
        /** A pinch step whose span ratio lies between these is skipped, so a still pinch does not jitter. */
        const val PINCH_SKIP_LOW = 0.999f
        const val PINCH_SKIP_HIGH = 1.001001f
        /** One-finger zoom levels per map height of drag. Google Maps measures the same on a 1,080 x
         *  2,340 px phone at 480 dpi (0.168 to 0.170 levels per 100 px while the finger moves, Davis
         *  at z15), at any drag speed from 0.25 to 2.5 s per 800 px and from z11 to z15. Dial
         *  `quickZoomPerHeight`. */
        const val QUICK_LEVELS_PER_HEIGHT = 4.0
        /** Drag before the one-finger zoom starts. Google Maps on the same phone ignores the first
         *  65 px. Dial `quickZoomDeadDp`. */
        const val QUICK_DEAD_DP = 20f
        /** The share of the pending zoom `a` a frame takes: `min(|a|, DRAIN_GAIN a^2)`. */
        const val DRAIN_GAIN = 10.0
        /** A pending zoom this small is taken whole; at 10 a^2 a frame its tail would crawl. */
        const val SNAP_LEVELS = 0.002
        const val SETTLED_LEVELS = 0.0005
        /** The fling counts this share of the screen height as one zoom level. */
        const val FLING_LEVEL_OF_SCREEN = 0.125f
        const val FLING_MAX_LEVELS_PER_S = 15.0
        const val FLING_MAX_LEVELS = 4
        /** A several-finger release takes the velocity from the first lift when the last follows within this. */
        const val LIFT_SNAPSHOT_MS = 100L
        /** Span change over the fingers' vertical travel below which a two-finger move is a tilt. */
        const val TILT_GUARD = 0.5f
        /** Small enough not to show, large enough that MapLibre does not drop it as no change. */
        const val SETTLE_ZOOM = 1e-6
    }
}
