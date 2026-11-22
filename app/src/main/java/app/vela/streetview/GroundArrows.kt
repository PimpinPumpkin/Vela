package app.vela.streetview

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Walk arrows drawn ON THE ROAD, the way Google's Street View draws them: a chevron lying on the
 * ground a few meters out along each neighbor's bearing, projected through the same camera as the
 * panorama ([PanoramaView]), so it turns, slides and foreshortens as you look around and leaves
 * the bottom of the view when you look up. Floating round buttons at a fixed height read as
 * stickers on the glass.
 */
object GroundArrows {
    /** Google's camera rides about this high above the road. */
    const val EYE_M = 2.5
    /** How far out along the street the chevron lies. */
    const val AHEAD_M = 5.5

    /** The chevron in meters on the ground: x along the street, y to its right. Deep along the
     *  street, since the road foreshortens it to about a third. */
    private val SHAPE = arrayOf(
        0.9 to 0.0, 0.0 to 0.9, -0.7 to 0.9, 0.2 to 0.0, -0.7 to -0.9, 0.0 to -0.9,
    )

    /**
     * Screen position of the direction [bearingDeg] (compass) at [elevDeg] above the horizon, for
     * a camera facing [yawDeg] (compass), pitched [pitchDeg] up, with horizontal field of view
     * [hFovDeg] on a [w] by [h] view. Null when the direction is behind the camera.
     */
    fun project(bearingDeg: Double, elevDeg: Double, yawDeg: Double, pitchDeg: Double, hFovDeg: Double, w: Float, h: Float): Offset? {
        val a = Math.toRadians(bearingDeg - yawDeg)
        val e = Math.toRadians(elevDeg)
        val p = Math.toRadians(pitchDeg)
        val x = cos(e) * sin(a)
        val y = sin(e)
        val z = cos(e) * cos(a)
        val fwd = z * cos(p) + y * sin(p)
        val up = y * cos(p) - z * sin(p)
        if (fwd < 0.05) return null
        val s = (w / 2.0) / tan(Math.toRadians(hFovDeg / 2.0))
        return Offset((w / 2.0 + x / fwd * s).toFloat(), (h / 2.0 - up / fwd * s).toFloat())
    }

    /** The chevron for a neighbor at [linkBearingDeg] as screen points, or null when part of it
     *  is behind the camera. */
    fun chevron(linkBearingDeg: Double, yawDeg: Double, pitchDeg: Double, hFovDeg: Double, w: Float, h: Float, scale: Double = 1.0): List<Offset>? {
        val b = Math.toRadians(linkBearingDeg)
        return SHAPE.map { (fx, ry) ->
            val f = AHEAD_M + fx * scale
            val r = ry * scale
            val east = f * sin(b) + r * cos(b)
            val north = f * cos(b) - r * sin(b)
            val bearing = Math.toDegrees(atan2(east, north))
            val elev = -Math.toDegrees(atan2(EYE_M, hypot(east, north)))
            project(bearing, elev, yawDeg, pitchDeg, hFovDeg, w, h) ?: return null
        }
    }

    /** Where a tap lands among [centers] (the chevrons' screen centers): the nearest within
     *  [reachPx], else null. */
    fun pick(tap: Offset, centers: List<Offset?>, reachPx: Float): Int? =
        centers.withIndex().filter { it.value != null && (it.value!! - tap).getDistance() <= reachPx }
            .minByOrNull { (it.value!! - tap).getDistance() }?.index
}
