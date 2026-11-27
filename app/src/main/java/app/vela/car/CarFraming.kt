package app.vela.car

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The car map's camera arithmetic, free of Android so it can be tested on the JVM. */
internal object CarFraming {
    /** MapLibre's vertical field of view, radians. The camera sits `0.5 * height / tan(FOV / 2)`
     *  pixels from the point it looks at. */
    const val FOV_RAD = 0.6435011087932844

    /** The drive camera's tilt, the phone's. */
    const val NAV_TILT_DEG = 55.0

    /** The following zoom: [ZOOM_NEAR] at rest to [ZOOM_FAR] at [ZOOM_FAR_KMH] and above, a
     *  straight line between. */
    const val ZOOM_NEAR = 17.2
    const val ZOOM_FAR = 15.0
    const val ZOOM_FAR_KMH = 110.0

    fun navZoom(kmh: Double): Double = ZOOM_NEAR - (kmh / ZOOM_FAR_KMH).coerceIn(0.0, 1.0) * (ZOOM_NEAR - ZOOM_FAR)

    /** The camera's distance to the point it looks at, in pixels of a surface [heightPx] tall. */
    fun cameraDistancePx(heightPx: Double): Double = 0.5 * heightPx / tan(FOV_RAD / 2.0)

    /**
     * Where to put the camera's target so that the anchor (the arrow) lands [downPx] below and
     * [rightPx] to the right of the surface's center, in ground pixels at the center's scale:
     * `ahead` along the camera's heading, `left` across it. Flat, that is [downPx] and [rightPx]
     * themselves. Tilted by [tiltDeg], the ground nearer the camera is drawn larger, so fewer
     * ground pixels reach the same screen row, and a lateral offset on that row covers less ground.
     */
    fun targetOffset(downPx: Double, rightPx: Double, tiltDeg: Double, heightPx: Double): Pair<Double, Double> {
        if (tiltDeg <= 0.0 || heightPx <= 0.0) return downPx to rightPx
        val p = Math.toRadians(tiltDeg)
        val d = cameraDistancePx(heightPx)
        // The camera is d·sin p behind the center and d·cos p above it. A ground point g toward
        // the camera sits p − atan((d·sin p − g) / (d·cos p)) below the view axis, and the screen
        // shows that angle at tan(angle)·d pixels. Solved for g at the wanted row.
        val ahead = d * sin(p) - d * cos(p) * tan(p - atan(downPx / d))
        // Depth along the view axis of that row, against the center's depth d.
        val depth = d - ahead * sin(p)
        val left = rightPx * depth / d
        return ahead to left
    }

    /** The least depth along the view axis, as a share of the center's, a ground point is drawn at. */
    const val FRONT_MARGIN = 0.15

    /**
     * How far behind the camera's target a ground point may be and still be drawn, in ground
     * pixels at the center's scale. A point g toward the camera is `d - g·sin p` deep along the
     * view axis ([targetOffset]); at zero it is level with the camera and past that behind it,
     * where the snapshot's projection answers with the point's mirror image through the screen.
     * The limit keeps [FRONT_MARGIN] of the center's depth, which at the drive tilt lands about
     * six screen heights below the center, far off the bottom edge. Flat, every point projects.
     */
    fun behindLimitPx(tiltDeg: Double, heightPx: Double): Double {
        if (tiltDeg <= 0.0 || heightPx <= 0.0) return Double.POSITIVE_INFINITY
        val s = sin(Math.toRadians(tiltDeg))
        if (s < 1e-3) return Double.POSITIVE_INFINITY
        return (1.0 - FRONT_MARGIN) * cameraDistancePx(heightPx) / s
    }

    /** The screen row, below the center, that a ground point [aheadPx] toward the camera lands
     *  on: the forward projection of [targetOffset], for checking it. */
    fun screenDown(aheadPx: Double, tiltDeg: Double, heightPx: Double): Double {
        if (tiltDeg <= 0.0) return aheadPx
        val p = Math.toRadians(tiltDeg)
        val d = cameraDistancePx(heightPx)
        return d * tan(p - atan((d * sin(p) - aheadPx) / (d * cos(p))))
    }

    /**
     * The zoom that fits a north-up box into a [visW] by [visH] pixel area with [FIT_MARGIN] to
     * spare, on MapLibre's 512 px tiles. The zoom used to come from the box's span alone, the same
     * on every screen: an 800 by 480 unit's preview put most of the route under the card, and a
     * wide one showed it small.
     */
    fun fitZoom(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double, visW: Double, visH: Double): Double {
        fun mercY(lat: Double): Double {
            val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + r / 2)) / (2 * Math.PI)
        }
        val dx = ((maxLng - minLng) / 360.0).coerceAtLeast(MIN_FRACTION)
        val dy = kotlin.math.abs(mercY(maxLat) - mercY(minLat)).coerceAtLeast(MIN_FRACTION)
        val zx = kotlin.math.log2(visW * FIT_MARGIN / (512.0 * dx))
        val zy = kotlin.math.log2(visH * FIT_MARGIN / (512.0 * dy))
        return minOf(zx, zy).coerceIn(3.0, 16.0)
    }

    /** The share of the visible area the fitted box may take. */
    const val FIT_MARGIN = 0.8
    /** A box narrower than this share of the world (about 30 m) fits as if it were this wide. */
    private const val MIN_FRACTION = 7.5e-7

    /**
     * Vertical top and bottom bounds `(topY, botY)` for the road-ahead bar on the car surface, or
     * null when there is not enough room.
     *
     * When [leftColumn] is true (full-screen Android Auto, where the top-left turn card and
     * bottom-left ETA card push `safeArea().left` right of the bar's column), `visTop` and
     * `visBottom` describe the open map to the right of those cards rather than the gap between
     * them. The turn card has dynamic height (multi-line street names, lane arrows, next-step
     * strip) while the ETA card at the bottom has fixed height, so the bar reserves top and bottom
     * clearances and anchors to the bottom of the gap so the top span label stays clear of the
     * turn card.
     */
    fun routeBarBounds(
        visTop: Float,
        visBottom: Float,
        surfaceHeight: Int,
        k: Float,
        leftColumn: Boolean,
    ): Pair<Float, Float>? {
        val topAvail = if (leftColumn) maxOf(visTop + 14f * k, surfaceHeight * 0.38f) else visTop + 14f * k
        val botAvail = if (leftColumn) minOf(visBottom - 28f * k, surfaceHeight * 0.78f) else visBottom - 28f * k
        val availH = botAvail - topAvail
        val minH = (if (leftColumn) 80f else 100f) * k
        if (availH < minH) return null
        return if (leftColumn) {
            val barH = (availH * 0.90f).coerceIn(minH, 260f * k)
            (botAvail - barH) to botAvail
        } else {
            val barH = (availH * 0.78f).coerceIn(minH, 320f * k)
            val topY = topAvail + (availH - barH) * 0.5f
            topY to (topY + barH)
        }
    }
}

/**
 * Which ground points a snapshot taken from a camera at ([lat], [lng]) facing [bearingDeg] can
 * draw: those no more than [limitM] meters behind its target ([CarFraming.behindLimitPx] times
 * the meters in a pixel). The route's driven part runs behind a tilted camera, and drawn from
 * its mirror image it was a gray line swinging about the map (issue 780).
 */
internal class FrontClip(private val lat: Double, private val lng: Double, bearingDeg: Double, private val limitM: Double) {
    private val cosB = cos(Math.toRadians(bearingDeg))
    private val sinB = sin(Math.toRadians(bearingDeg))
    private val cosLat = cos(Math.toRadians(lat))

    /** Meters behind the camera's target along its bearing; negative ahead of it. */
    fun behindM(pLat: Double, pLng: Double): Double {
        var dLng = pLng - lng
        if (dLng > 180.0) dLng -= 360.0 else if (dLng < -180.0) dLng += 360.0
        val north = (pLat - lat) * M_PER_DEG
        val east = dLng * M_PER_DEG * cosLat
        return -(north * cosB + east * sinB)
    }

    fun inFront(pLat: Double, pLng: Double): Boolean = behindM(pLat, pLng) <= limitM

    /** How far along the segment from a to b, 0 to 1, it crosses the limit. For a segment with
     *  one end in front and one behind. */
    fun crossing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val a = behindM(aLat, aLng) - limitM
        val b = behindM(bLat, bLng) - limitM
        return if (a == b) 0.0 else (a / (a - b)).coerceIn(0.0, 1.0)
    }


    private companion object { const val M_PER_DEG = 111_320.0 }
}
