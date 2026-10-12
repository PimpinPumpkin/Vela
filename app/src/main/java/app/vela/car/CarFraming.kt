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

    /** The screen row, below the center, that a ground point [aheadPx] toward the camera lands
     *  on: the forward projection of [targetOffset], for checking it. */
    fun screenDown(aheadPx: Double, tiltDeg: Double, heightPx: Double): Double {
        if (tiltDeg <= 0.0) return aheadPx
        val p = Math.toRadians(tiltDeg)
        val d = cameraDistancePx(heightPx)
        return d * tan(p - atan((d * sin(p) - aheadPx) / (d * cos(p))))
    }
}
