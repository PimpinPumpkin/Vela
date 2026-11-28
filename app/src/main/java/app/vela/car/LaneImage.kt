package app.vela.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.drawable.IconCompat
import app.vela.core.model.ManeuverType
import app.vela.ui.nav.LANE_FOOT
import app.vela.ui.nav.LANE_GLYPH
import app.vela.ui.nav.LANE_PITCH
import app.vela.ui.nav.LANE_STROKE
import app.vela.ui.nav.laneArrows
import app.vela.ui.nav.laneGlyphs
import app.vela.ui.nav.laneHeadOutline
import app.vela.core.model.Lane as VelaLane

/**
 * Builds Android Auto lane guidance from Vela's OSRM lane data: the [Lane] list (which the host
 * uses for accessibility and placement) and a lanes bitmap (which it displays). The bitmap draws
 * the phone card's arrows (`laneGlyphs`, Google's): every arrow a lane allows, the one for this
 * turn lit in a valid lane, and a short dash between lanes. A lane direction is recommended when
 * the phone would light it.
 */
object LaneImage {

    // Pixels to a glyph unit (a glyph is 97 tall): the host scales the bitmap to its lane strip.
    private const val UNIT = 1.25f
    // Room above the tallest tip, and past the end lanes for glyphs that reach out to the side.
    private const val TOP = 4f
    private const val SIDE = 21f

    private fun shape(kind: String): Int = when (kind) {
        "uturn" -> LaneDirection.SHAPE_U_TURN_LEFT
        "sharp left" -> LaneDirection.SHAPE_SHARP_LEFT
        "sharp right" -> LaneDirection.SHAPE_SHARP_RIGHT
        "slight left" -> LaneDirection.SHAPE_SLIGHT_LEFT
        "slight right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "left" -> LaneDirection.SHAPE_NORMAL_LEFT
        "right" -> LaneDirection.SHAPE_NORMAL_RIGHT
        else -> LaneDirection.SHAPE_STRAIGHT
    }

    /** Car lanes + a rendered image, or null when there's no lane data. */
    fun build(lanes: List<VelaLane>, maneuver: ManeuverType): Pair<List<Lane>, CarIcon>? {
        if (lanes.isEmpty() || lanes.size > 8) return null
        val carLanes = laneArrows(lanes, maneuver).map { arrows ->
            Lane.Builder().apply {
                arrows.kinds.forEach { addDirection(LaneDirection.create(shape(it), it in arrows.lit)) }
            }.build()
        }
        return carLanes to CarIcon.Builder(IconCompat.createWithBitmap(render(lanes, maneuver))).build()
    }

    internal fun render(lanes: List<VelaLane>, maneuver: ManeuverType): Bitmap {
        val u = UNIT
        val w = ((lanes.size * LANE_PITCH + 2 * SIDE) * u).toInt().coerceAtLeast(1)
        val h = ((TOP + LANE_GLYPH + LANE_FOOT) * u).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = LANE_STROKE * u
            strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val dim = Color.parseColor("#80868B")
        val foot = (TOP + LANE_GLYPH) * u
        val laneSets = laneArrows(lanes, maneuver)
        lanes.forEachIndexed { i, _ ->
            val arrows = laneSets[i]
            val cx = (SIDE + LANE_PITCH * (i + 0.5f)) * u
            // Dim arrows first, so the lit one lies on top where they share the shaft.
            laneGlyphs(arrows.kinds, cx, foot, u).sortedBy { if (it.indication in arrows.lit) 1 else 0 }.forEach { g ->
                val color = if (g.indication in arrows.lit) Color.WHITE else dim
                body.color = color; fill.color = color
                c.drawPath(path(g.body), body)
                c.drawPath(path(laneHeadOutline(g, u)).apply { close() }, fill)
            }
            if (i < lanes.lastIndex) {
                fill.color = dim
                val x = (SIDE + LANE_PITCH * (i + 1)) * u
                c.drawRect(x - 2.5f * u, foot + 2f * u, x + 2.5f * u, foot + LANE_FOOT * u, fill)
            }
        }
        return bmp
    }

    private fun path(points: List<Offset>) = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
}
