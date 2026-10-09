package app.vela.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.vela.core.model.ManeuverType
import app.vela.ui.nav.ManeuverGlyph
import app.vela.ui.nav.maneuverGlyph

/**
 * Canvas-drawn maneuver arrows for the nav notification's large icon (a white glyph on the
 * Vela-teal rounded square, Google-Maps style). The in-app banner glyphs are Compose
 * ImageVectors, which can't be rasterized outside a composition, so the notification draws
 * its own small set here. Which picture a type gets comes from the same [maneuverGlyph] table the
 * app's banner uses. Geometry is on a 0..96 grid scaled to the requested pixel size.
 */
object NavGlyphs {

    const val TEAL = 0xFF14857A.toInt() // ui.theme.VelaTeal; also the notification accent

    fun bitmap(type: ManeuverType, sizePx: Int, background: Int = TEAL): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val r = sizePx * 0.22f
        c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, bg)
        val s = sizePx / 96f // glyph coordinate space
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

        // Left-hand variants are the right-hand paths mirrored around the vertical axis.
        val glyph = maneuverGlyph(type)
        val mirrored = glyph == ManeuverGlyph.LEFT || glyph == ManeuverGlyph.SLIGHT_LEFT || glyph == ManeuverGlyph.SHARP_LEFT
        if (mirrored) {
            c.save()
            c.scale(-1f, 1f, sizePx / 2f, sizePx / 2f)
        }

        when (glyph) {
            ManeuverGlyph.LEFT, ManeuverGlyph.RIGHT -> {
                val p = Path().apply {
                    moveTo(38f * s, 76f * s)
                    lineTo(38f * s, 46f * s)
                    quadTo(38f * s, 34f * s, 50f * s, 34f * s)
                    lineTo(64f * s, 34f * s)
                }
                c.drawPath(p, stroke)
                c.drawPath(arrowHead(64f, 34f, 90f, s), fill)
            }
            // Ramps, forks and keeps too: only the branch the driver takes is drawn.
            ManeuverGlyph.SLIGHT_LEFT, ManeuverGlyph.SLIGHT_RIGHT -> {
                val p = Path().apply {
                    moveTo(44f * s, 78f * s)
                    lineTo(44f * s, 58f * s)
                    lineTo(58f * s, 34f * s)
                }
                c.drawPath(p, stroke)
                c.drawPath(arrowHead(58f, 34f, 30f, s), fill)
            }
            ManeuverGlyph.SHARP_LEFT, ManeuverGlyph.SHARP_RIGHT -> {
                val p = Path().apply {
                    moveTo(40f * s, 78f * s)
                    lineTo(40f * s, 42f * s)
                    lineTo(62f * s, 60f * s)
                }
                c.drawPath(p, stroke)
                c.drawPath(arrowHead(62f, 60f, 140f, s), fill)
            }
            ManeuverGlyph.UTURN -> {
                val p = Path().apply {
                    moveTo(60f * s, 76f * s)
                    lineTo(60f * s, 42f * s)
                    quadTo(60f * s, 26f * s, 47f * s, 26f * s)
                    quadTo(34f * s, 26f * s, 34f * s, 42f * s)
                    lineTo(34f * s, 58f * s)
                }
                c.drawPath(p, stroke)
                c.drawPath(arrowHead(34f, 58f, 180f, s), fill)
            }
            ManeuverGlyph.MERGE -> {
                val left = Path().apply {
                    moveTo(32f * s, 78f * s)
                    quadTo(48f * s, 60f * s, 48f * s, 44f * s)
                    lineTo(48f * s, 26f * s)
                }
                val right = Path().apply {
                    moveTo(64f * s, 78f * s)
                    quadTo(48f * s, 60f * s, 48f * s, 44f * s)
                }
                c.drawPath(left, stroke)
                c.drawPath(right, stroke)
                c.drawPath(arrowHead(48f, 26f, 0f, s), fill)
            }
            ManeuverGlyph.ROUNDABOUT -> {
                c.drawCircle(48f * s, 52f * s, 15f * s, stroke)
                c.drawLine(48f * s, 37f * s, 48f * s, 22f * s, stroke)
                c.drawPath(arrowHead(48f, 22f, 0f, s), fill)
            }
            ManeuverGlyph.FLAG -> {
                // Destination flag: pole + pennant.
                c.drawLine(38f * s, 76f * s, 38f * s, 22f * s, stroke)
                val pennant = Path().apply {
                    moveTo(38f * s, 24f * s)
                    lineTo(66f * s, 32f * s)
                    lineTo(38f * s, 42f * s)
                    close()
                }
                c.drawPath(pennant, fill)
            }
            ManeuverGlyph.ORIGIN, ManeuverGlyph.STRAIGHT, ManeuverGlyph.UNKNOWN -> { // straight-ahead arrow
                c.drawLine(48f * s, 78f * s, 48f * s, 30f * s, stroke)
                c.drawPath(arrowHead(48f, 30f, 0f, s), fill)
            }
        }
        if (mirrored) c.restore()
        return bmp
    }

    /** A filled triangular arrowhead at ([x],[y]) pointing along [angleDeg] (0 = up, 90 = right). */
    private fun arrowHead(x: Float, y: Float, angleDeg: Float, s: Float): Path {
        val len = 14f
        val half = 9f
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = Math.sin(rad).toFloat()
        val dy = -Math.cos(rad).toFloat()
        val px = -dy // perpendicular
        val py = dx
        val tipX = (x + dx * len * 0.6f) * s
        val tipY = (y + dy * len * 0.6f) * s
        val baseX = x - dx * len * 0.4f
        val baseY = y - dy * len * 0.4f
        return Path().apply {
            moveTo(tipX, tipY)
            lineTo((baseX + px * half) * s, (baseY + py * half) * s)
            lineTo((baseX - px * half) * s, (baseY - py * half) * s)
            close()
        }
    }
}
