package app.vela.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import app.vela.core.model.RoundaboutGeometry
import kotlin.math.cos
import kotlin.math.sin

/**
 * A roundabout turn glyph DRAWN from the maneuver's own geometry (issue #259).
 *
 * The old glyph was Material's `RoundaboutLeft`, one fixed picture used for every roundabout: a
 * counter-clockwise circulation exiting at 270 degrees. That is wrong about the direction of travel
 * for every left-hand-traffic driver, and wrong about the exit for nearly every roundabout anywhere
 * - which matters because the glyph is what you take in at a glance while the text is what you read
 * only if you have time. The reporter's read was that a wrong icon is worse than none.
 *
 * So it is built per maneuver instead:
 *  - the ring is drawn as the arc you actually TRAVEL (heavier) plus the part you do not (lighter),
 *    going round the correct way, so circulation direction is visible rather than implied;
 *  - the exit stub leaves at the real angle, measured off the road you approached on;
 *  - with no geometry (a fallback router that gives no bearings) it draws its NEUTRAL form - ring
 *    plus entry stub, no exit arrow - because an arrow pointing somewhere we did not measure is
 *    exactly the thing being fixed here.
 *
 * Angles are compass-style on the glyph: 0 is straight up (the way you entered), positive turns
 * clockwise on screen, matching [RoundaboutGeometry.exitAngleDeg].
 */

private const val VP = 24f // viewport, matching the Material icon set so it drops into the same slots
private const val CX = 12f
private const val CY = 12f
private const val R = 6.0f // ring radius
private const val STUB = 4.2f // how far the entry/exit roads stick out past the ring
private const val TRAVELLED_W = 2.1f
private const val UNTRAVELLED_W = 1.1f

/** Ring point at compass angle [deg] (0 = up, positive clockwise on screen). */
private fun ring(deg: Double, radius: Float = R): Pair<Float, Float> {
    val r = Math.toRadians(deg)
    return (CX + radius * sin(r)).toFloat() to (CY - radius * cos(r)).toFloat()
}

/**
 * The roundabout glyph for [geom], or the neutral ring when it is null.
 *
 * Cached per distinct geometry: the banner recomposes on every frame of the distance countdown and
 * building an ImageVector allocates a whole node tree, so an uncached build would run 60x a second
 * for the entire approach to the roundabout.
 */
@Composable
fun rememberRoundaboutGlyph(geom: RoundaboutGeometry?): ImageVector =
    remember(geom?.exitAngleDeg, geom?.clockwise) { roundaboutGlyph(geom) }

internal fun roundaboutGlyph(geom: RoundaboutGeometry?): ImageVector {
    val b = ImageVector.Builder(
        name = "vela_roundabout",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = VP, viewportHeight = VP,
    )
    val ink = SolidColor(Color.Black) // Icon()'s tint colours the whole vector; this is just a placeholder

    // You always enter from the bottom of the glyph heading up, whatever the real compass heading -
    // the whole picture is drawn relative to your approach, which is how a driver reads it.
    val (entryX, entryY) = ring(180.0)
    b.path(
        stroke = ink, strokeLineWidth = TRAVELLED_W,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(CX, CY + R + STUB)
        lineTo(entryX, entryY)
    }

    if (geom == null) {
        // Neutral: a plain ring. It says "roundabout" without claiming an exit we never measured.
        b.path(stroke = ink, strokeLineWidth = UNTRAVELLED_W) { circleAt(R) }
        return b.build()
    }

    // Sweep from the entry point round to where the exit road meets the ring. Right-hand traffic
    // circulates counter-clockwise on screen, so the ring angle DECREASES from 180 toward the exit;
    // left-hand traffic increases it. (Sanity check, right-hand: exit angle +90 - a first exit,
    // a right turn out - gives a quarter of the ring. Exit angle -90 - a third exit - gives three
    // quarters, the long way round, which is exactly what you drive.)
    val exitDeg = geom.exitAngleDeg
    val sweep = if (geom.clockwise) mod360(exitDeg - 180.0) else mod360(180.0 - exitDeg)
    val (exitX, exitY) = ring(exitDeg)

    // The travelled arc. isPositiveArc is the SVG sweep flag: true draws clockwise on screen, which
    // is left-hand-traffic circulation.
    if (sweep > 1.0) {
        b.path(
            stroke = ink, strokeLineWidth = TRAVELLED_W,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(entryX, entryY)
            arcTo(R, R, 0f, sweep > 180.0, geom.clockwise, exitX, exitY)
        }
    }
    // ...and the rest of the ring, thinner, so the roundabout still reads as a full circle.
    val rest = 360.0 - sweep
    if (rest > 1.0) {
        b.path(stroke = ink, strokeLineWidth = UNTRAVELLED_W) {
            moveTo(exitX, exitY)
            arcTo(R, R, 0f, rest > 180.0, geom.clockwise, entryX, entryY)
        }
    }

    // The exit road, leaving radially at the measured angle, with a solid head so the direction out
    // is unmistakable. Drawn as a triangle rather than two strokes: a stroked chevron at this size
    // renders as a smudge on a low-density screen.
    val (outX, outY) = ring(exitDeg, R + STUB * 0.55f)
    b.path(
        stroke = ink, strokeLineWidth = TRAVELLED_W,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(exitX, exitY)
        lineTo(outX, outY)
    }
    val tip = ring(exitDeg, R + STUB + 1.4f)
    val baseR = R + STUB * 0.55f
    val perp = Math.toRadians(exitDeg + 90.0)
    val hx = (2.0 * sin(perp)).toFloat()
    val hy = (-2.0 * cos(perp)).toFloat()
    val base = ring(exitDeg, baseR)
    b.path(fill = ink) {
        moveTo(tip.first, tip.second)
        lineTo(base.first + hx, base.second + hy)
        lineTo(base.first - hx, base.second - hy)
        close()
    }
    return b.build()
}

/** A full circle of [radius] about the glyph centre, as four arc quadrants (a path has no circle
 *  primitive, and one 360-degree arcTo is degenerate - start and end coincide). */
private fun androidx.compose.ui.graphics.vector.PathBuilder.circleAt(radius: Float) {
    moveTo(CX, CY - radius)
    arcTo(radius, radius, 0f, false, true, CX + radius, CY)
    arcTo(radius, radius, 0f, false, true, CX, CY + radius)
    arcTo(radius, radius, 0f, false, true, CX - radius, CY)
    arcTo(radius, radius, 0f, false, true, CX, CY - radius)
}

/** [deg] wrapped into [0, 360). */
private fun mod360(deg: Double): Double {
    val d = deg % 360.0
    return if (d < 0) d + 360.0 else d
}

/** True when [type] should render the drawn roundabout glyph rather than a Material icon. */
internal fun isRoundabout(type: app.vela.core.model.ManeuverType): Boolean =
    type == app.vela.core.model.ManeuverType.ROUNDABOUT ||
        type == app.vela.core.model.ManeuverType.EXIT_ROUNDABOUT
