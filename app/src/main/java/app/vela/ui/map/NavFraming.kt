package app.vela.ui.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the drive camera puts the arrow and how far its speed zoom pulls back, from the part of
 * the map the drive's chrome leaves visible (SPEC 4.7, "Framing").
 *
 * At the default display and font size the frame is the old one: the arrow [DEFAULT_PAD] down
 * (72.5 percent of the map) and the speed's zoom. A large display size or large text grows the
 * turn card down and the bottom chrome up. The arrow then rises until what hangs under it (the
 * road-name pill) clears the bottom chrome, and the zoom pulls back by how much shorter the map
 * between the card and the arrow is than on the same phone at default size.
 */
internal object NavFraming {
    /** Camera top padding as a fraction of the map height at default size: the arrow sits 72.5 percent down. */
    const val DEFAULT_PAD = 0.45

    /** The arrow never rises above this fraction of the map, whatever sits under it. */
    const val MIN_PUCK_FRAC = 0.55

    /** The turn card's bottom edge at default display and font size, in dp from the map's top
     *  (status bar included). The reference the look-ahead is measured against. */
    const val REF_TOP_DP = 230.0

    /** On a screen too short for [REF_TOP_DP] the reference look-ahead is this fraction of the map. */
    const val REF_MIN_AHEAD_FRAC = 0.25

    /** Room below the arrow's point that its glyph takes; the road-name pill hangs this far under it. */
    const val ARROW_BELOW_PX = 62

    /** Half the arrow's width at the default arrow size (the 202 px puck bitmap). */
    const val ARROW_HALF_PX = 101f

    /** Kept between the arrow (or the pill under it) and the bottom chrome. */
    const val MARGIN_DP = 8.0

    /** The road-name pill above the bar sits this far over the bar's top edge. */
    const val BAR_PILL_GAP_DP = 10.0

    /** Look-ahead at or above this share of the default needs no zoom change, so a lane strip or a
     *  "Then" tab at default size leaves the zoom alone. */
    const val DEAD_RATIO = 0.8

    /** At or below this share the zoom makes up the whole difference. Between the two it ramps. */
    const val FULL_RATIO = 0.6

    /** The most the zoom pulls back, in zoom levels. */
    const val MAX_ZOOM_OUT = 1.5

    /** The pull-back never takes the camera's own zoom below this. Lights and stop signs draw from
     *  z15.4 and the minor street callouts from z15.2. */
    const val ZOOM_FLOOR = 15.5

    /** A change in the measured chrome is adopted once it has held this long, so a bar being
     *  dragged or a card animating in does not walk the camera. */
    const val SETTLE_MS = 400L

    class Frame(val pad: Double, val zoomOffset: Double)

    val DEFAULT = Frame(DEFAULT_PAD, 0.0)

    /**
     * The frame for a map [mapHeightPx] tall at [density] px per dp, on a phone whose default
     * density is [defaultDensity]. [topPx] is the bottom edge of the chrome over the map's top (0
     * when none), [bottomPx] the top edge of the chrome under the arrow's column (0 when none),
     * [belowPuckPx] what must fit between the arrow's point and that edge.
     */
    fun frame(
        mapHeightPx: Double,
        density: Double,
        defaultDensity: Double,
        topPx: Double,
        bottomPx: Double,
        belowPuckPx: Double,
        northUp: Boolean = false,
    ): Frame {
        if (mapHeightPx <= 0.0 || density <= 0.0) return DEFAULT
        val h = mapHeightPx
        if (northUp) {
            // North-up, the road ahead can run any way on screen, so the arrow takes the middle of
            // the map the chrome leaves, never lower than its heading-up place.
            val bottom = if (bottomPx > 0.0) bottomPx else h
            val y = ((topPx.coerceIn(0.0, h) + bottom) / 2).coerceIn(h / 2, h * (1 + DEFAULT_PAD) / 2)
            return Frame(2 * y / h - 1, 0.0)
        }
        val lowest = if (bottomPx > 0.0) bottomPx - belowPuckPx - MARGIN_DP * density else h
        val defaultY = h * (1 + DEFAULT_PAD) / 2
        val y = min(defaultY, lowest).coerceAtLeast(h * MIN_PUCK_FRAC)
        val pad = if (y >= defaultY) DEFAULT_PAD else 2 * y / h - 1
        val aheadDp = (y - topPx.coerceIn(0.0, y)) / density
        val defaultDp = h / (if (defaultDensity > 0.0) defaultDensity else density)
        val refAheadDp = max(defaultDp * (1 + DEFAULT_PAD) / 2 - REF_TOP_DP, defaultDp * REF_MIN_AHEAD_FRAC)
        return Frame(pad, zoomOffset(aheadDp / refAheadDp))
    }

    /** Zoom levels to pull back for a look-ahead [ratio] of the default (1 = the same). */
    fun zoomOffset(ratio: Double): Double {
        if (ratio >= DEAD_RATIO) return 0.0
        val w = ((DEAD_RATIO - ratio) / (DEAD_RATIO - FULL_RATIO)).coerceAtMost(1.0)
        val full = kotlin.math.ln(ratio.coerceAtLeast(1e-3)) / kotlin.math.ln(2.0)
        return (w * full).coerceAtLeast(-MAX_ZOOM_OUT)
    }

    /** The speed's zoom pulled back by [offset], not below [ZOOM_FLOOR] on that account. */
    fun zoom(speedZoom: Double, offset: Double): Double =
        max(speedZoom + offset, min(speedZoom, ZOOM_FLOOR))

    /**
     * The top of the bottom chrome under the arrow's column, in px, 0 when nothing is known: the
     * bar, the road-name pill when it sits above the bar, and the speed box when large text has
     * widened it under the arrow (at default size it stays in the corner).
     */
    fun bottomEdge(
        mapWidthPx: Double,
        density: Double,
        barTopPx: Double,
        pillAboveBar: Boolean,
        pillHeightPx: Double,
        speedTopPx: Double,
        speedRightPx: Double,
        arrowHalfPx: Double,
    ): Double {
        var b = if (barTopPx > 0.0) barTopPx else Double.MAX_VALUE
        if (barTopPx > 0.0 && pillAboveBar && pillHeightPx > 0.0) b -= BAR_PILL_GAP_DP * density + pillHeightPx
        if (speedTopPx > 0.0 && speedRightPx + MARGIN_DP * density > mapWidthPx / 2 - arrowHalfPx) b = min(b, speedTopPx)
        return if (b == Double.MAX_VALUE) 0.0 else b
    }

    /** What hangs under the arrow's point: its own glyph, and the road-name pill when that is pinned under it. */
    fun belowPuck(pillUnderArrow: Boolean, pillHeightPx: Double): Double =
        ARROW_BELOW_PX + if (pillUnderArrow) pillHeightPx else 0.0
}

/**
 * The measured edges of the drive chrome the camera keeps the arrow clear of, written in the layout
 * callbacks of the composables that draw them and read by the drive ticker. Window px, 0 = not
 * measured. Values stay when the element hides (the step list, a pan), so the arrow does not move
 * each time it does. File-level like the other nav edges in MapScreen.kt: that function has no room
 * for another remember.
 */
internal object NavChromeEdges {
    /** The turn card's bottom edge, its "Then" tab and lanes included. */
    @JvmField var bannerBottomPx = 0

    @JvmField var speedTopPx = 0
    @JvmField var speedRightPx = 0

    /** The road-name pill's height at full text size: the tallest measured at the current density
     *  and font scale (the text shrinks to 80 percent for a long name). */
    @JvmField var pillHeightPx = 0
    private var pillKey = Float.NaN

    fun pill(heightPx: Int, densityKey: Float) {
        if (densityKey != pillKey) {
            pillKey = densityKey
            pillHeightPx = heightPx
        } else if (heightPx > pillHeightPx) {
            pillHeightPx = heightPx
        }
    }
}

/**
 * The drive ticker's side of [NavFraming]: holds the frame in use and adopts a new one only when
 * the inputs have moved by a dp or more and then held still for [NavFraming.SETTLE_MS]. The first
 * frame is adopted at once. Called every following frame; no allocation.
 */
internal class NavFramer {
    var pad = NavFraming.DEFAULT_PAD
        private set
    var zoomOffset = 0.0
        private set

    // [map height dp, density x 100, default density x 100, top dp, bottom dp, below-puck dp, north-up x 100]
    private val cur = FloatArray(7)
    private val seen = FloatArray(7) { Float.NaN }
    private val adopted = FloatArray(7) { Float.NaN }
    private var seenAtMs = 0L
    private var adoptedOnce = false

    /** Returns true when the frame changed. */
    fun update(
        nowMs: Long,
        mapHeightPx: Int,
        density: Float,
        defaultDensity: Float,
        topPx: Float,
        bottomPx: Float,
        belowPuckPx: Float,
        northUp: Boolean = false,
    ): Boolean {
        if (density <= 0f) return false
        cur[0] = mapHeightPx / density
        cur[1] = density * 100f
        cur[2] = defaultDensity * 100f
        cur[3] = topPx / density
        cur[4] = bottomPx / density
        cur[5] = belowPuckPx / density
        cur[6] = if (northUp) 100f else 0f
        if (differs(cur, seen)) {
            cur.copyInto(seen)
            seenAtMs = nowMs
        }
        if (!differs(seen, adopted)) return false
        if (adoptedOnce && nowMs - seenAtMs < NavFraming.SETTLE_MS) return false
        val f = NavFraming.frame(
            mapHeightPx.toDouble(), density.toDouble(), defaultDensity.toDouble(),
            topPx.toDouble(), bottomPx.toDouble(), belowPuckPx.toDouble(), northUp,
        )
        seen.copyInto(adopted)
        adoptedOnce = true
        val changed = abs(f.pad - pad) > 1e-6 || abs(f.zoomOffset - zoomOffset) > 1e-6
        pad = f.pad
        zoomOffset = f.zoomOffset
        return changed
    }

    /** Reads the measured chrome and calls [update]. In landscape (the chrome is a left column) and
     *  in picture-in-picture nothing covers the arrow's column. */
    fun track(nowMs: Long, context: android.content.Context, mapWidthPx: Int, mapHeightPx: Int, barTopPx: Float, northUp: Boolean = false): Boolean {
        val dm = context.resources.displayMetrics
        val d = dm.density
        val defaultD = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
        val clear = mapWidthPx > mapHeightPx || app.vela.ui.PipMode.active.value
        val mode = app.vela.ui.RoadLabel.mode.value
        val pillH = NavChromeEdges.pillHeightPx.toDouble()
        val bottom = if (clear) 0.0 else NavFraming.bottomEdge(
            mapWidthPx.toDouble(), d.toDouble(), barTopPx.toDouble(),
            pillAboveBar = mode == app.vela.ui.RoadLabel.BAR,
            pillHeightPx = pillH,
            speedTopPx = if (app.vela.ui.SpeedDisplay.on.value) NavChromeEdges.speedTopPx.toDouble() else 0.0,
            speedRightPx = NavChromeEdges.speedRightPx.toDouble(),
            arrowHalfPx = (NavFraming.ARROW_HALF_PX * app.vela.ui.PuckStyle.scale()).toDouble(),
        )
        val below = NavFraming.belowPuck(mode == app.vela.ui.RoadLabel.PUCK, pillH)
        val top = if (clear) 0 else NavChromeEdges.bannerBottomPx
        // North-up the arrow centers between the card and the bar itself: the pill and the speed
        // box sit under a low arrow, and the middle is clear of both.
        val edge = if (northUp && !clear && barTopPx > 0f) barTopPx.toDouble() else bottom
        return update(nowMs, mapHeightPx, d, if (defaultD > 0f) defaultD else d, top.toFloat(), edge.toFloat(), below.toFloat(), northUp)
    }

    private fun differs(a: FloatArray, b: FloatArray): Boolean {
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.isNaN() != y.isNaN()) return true
            if (!x.isNaN() && abs(x - y) >= 1f) return true
        }
        return false
    }
}
