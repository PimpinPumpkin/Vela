package app.vela.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.core.graphics.drawable.IconCompat
import app.vela.core.model.Lane as VelaLane

/**
 * Builds Android Auto lane-guidance from Vela's OSRM lane data: both the [Lane] metadata (which the
 * host uses for accessibility/placement) AND a rendered lanes bitmap (which it displays). Valid lanes
 * (the ones you can take for this maneuver) are drawn bright; the rest dimmed — Google's lane diagram.
 */
object LaneImage {

    private fun shape(ind: String): Int = when {
        "uturn" in ind -> LaneDirection.SHAPE_U_TURN_LEFT
        "sharp left" in ind -> LaneDirection.SHAPE_SHARP_LEFT
        "sharp right" in ind -> LaneDirection.SHAPE_SHARP_RIGHT
        "slight left" in ind -> LaneDirection.SHAPE_SLIGHT_LEFT
        "slight right" in ind -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "left" in ind -> LaneDirection.SHAPE_NORMAL_LEFT
        "right" in ind -> LaneDirection.SHAPE_NORMAL_RIGHT
        "straight" in ind || "through" in ind -> LaneDirection.SHAPE_STRAIGHT
        else -> LaneDirection.SHAPE_UNKNOWN
    }

    /** Turn angle (degrees, 0 = straight up) for the arrow glyph. */
    private fun angle(ind: String): Float = when {
        "uturn" in ind -> 180f
        "sharp left" in ind -> -135f
        "sharp right" in ind -> 135f
        "slight left" in ind -> -40f
        "slight right" in ind -> 40f
        "left" in ind -> -90f
        "right" in ind -> 90f
        else -> 0f
    }

    /** Car lanes + a rendered image, or null when there's no lane data. */
    fun build(lanes: List<VelaLane>): Pair<List<Lane>, CarIcon>? {
        if (lanes.isEmpty() || lanes.size > 8) return null
        val carLanes = lanes.map { vl ->
            val b = Lane.Builder()
            (vl.indications.ifEmpty { listOf("straight") }).forEach {
                b.addDirection(LaneDirection.create(shape(it.lowercase()), vl.valid))
            }
            b.build()
        }
        return carLanes to CarIcon.Builder(IconCompat.createWithBitmap(render(lanes))).build()
    }

    private fun render(lanes: List<VelaLane>): Bitmap {
        val cell = 60; val h = 60
        val bmp = Bitmap.createBitmap((cell * lanes.size).coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lanes.forEachIndexed { i, vl ->
            val cx = i * cell + cell / 2f
            val color = if (vl.valid) Color.WHITE else Color.parseColor("#59616e")
            // The valid indication drives the drawn arrow (for a through/turn lane, the turn wins).
            val ind = (vl.indications.firstOrNull { angle(it.lowercase()) != 0f } ?: vl.indications.firstOrNull() ?: "straight").lowercase()
            arrow(c, cx, h / 2f, angle(ind), color)
        }
        return bmp
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, deg: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        c.save()
        c.rotate(deg, cx, cy)
        val s = 15f
        val head = Path().apply {
            moveTo(cx, cy - s); lineTo(cx - s * 0.75f, cy); lineTo(cx + s * 0.75f, cy); close()
        }
        c.drawPath(head, paint)
        c.drawRect(cx - 3f, cy - 2f, cx + 3f, cy + s + 5f, paint) // shaft
        c.restore()
    }
}
