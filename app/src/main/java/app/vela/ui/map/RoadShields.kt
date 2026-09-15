package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Route shields the way Google draws them, painted at runtime in place of the OpenFreeMap
 * sprite's outline-only ones (user 2026-09-15: "I-5 is just a white badge with text that kinda
 * fits awkwardly"). The sprite's `us-interstate_N`, `us-highway_N` and `road_N` are white shapes
 * with a thin edge, sized for 10 pt text, and it has NO `us-state_N` at all, so every state
 * route drew as bare text. The three shield layers are repointed at Vela's own images:
 *
 * - `vela-shield-us-interstate_N`: blue shield, red top band, white number (the sign's colors).
 * - `vela-shield-us-highway_N`: white shield with a dark edge, dark number.
 * - `vela-shield-us-state_N` and the international `vela-shield-road_N`: white rounded badge.
 *
 * One image per ref length (1..6, the layers' own filter cap), each sized for 11 pt bold digits
 * with the same margins, so "5" and "580" get the same breathing room. Vela's names, not the
 * sprite's: a same-named `addImage` lost to the sprite when it finished loading (device,
 * 2026-09-15), and `icon-text-fit` on the sprite image shrank the badge to the glyph box. The
 * colors are the sign's, not the theme's, so they stay in dark mode like Google's. Satellite
 * skips shield layers on purpose (white text on a white badge would erase the number).
 */
internal object RoadShields {
    private const val H = 22f
    private const val BAND = 6f // interstate red band, dp
    /** Badge width per ref length, dp: 11 pt bold digits are ~6.5 dp each plus 10 dp of margin. */
    private val WIDTHS = mapOf(1 to 22f, 2 to 27f, 3 to 33f, 4 to 40f, 5 to 47f, 6 to 54f)

    fun install(style: Style, d: Float) {
        for ((n, w) in WIDTHS) {
            style.addImage("vela-shield-us-interstate_$n", shield(w, d, fill = 0xFF1F5AA8.toInt(), band = 0xFFC62828.toInt(), edge = 0xFFFFFFFF.toInt()))
            style.addImage("vela-shield-us-highway_$n", shield(w, d, fill = 0xFFFFFFFF.toInt(), band = null, edge = 0xFF3C4043.toInt()))
            val badge = pill(w, d, fill = 0xFFFFFFFF.toInt(), edge = 0xFF3C4043.toInt())
            style.addImage("vela-shield-us-state_$n", badge)
            style.addImage("vela-shield-road_$n", badge)
        }
        val common = arrayOf(
            PropertyFactory.iconSize(1f),
            PropertyFactory.textSize(11f),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textHaloWidth(0f),
        )
        (style.getLayer("highway-shield-us-interstate") as? SymbolLayer)?.setProperties(
            *common,
            PropertyFactory.iconImage(Expression.concat(Expression.literal("vela-shield-us-interstate_"), Expression.toString(Expression.get("ref_length")))),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textOffset(arrayOf(0f, 0.22f)), // the number sits in the blue, under the band
        )
        (style.getLayer("road_shield_us") as? SymbolLayer)?.setProperties(
            *common,
            PropertyFactory.iconImage(Expression.concat(Expression.literal("vela-shield-"), Expression.get("network"), Expression.literal("_"), Expression.toString(Expression.get("ref_length")))),
            PropertyFactory.textColor("#202124"),
            PropertyFactory.textOffset(arrayOf(0f, 0f)),
        )
        (style.getLayer("highway-shield-non-us") as? SymbolLayer)?.setProperties(
            *common,
            PropertyFactory.iconImage(Expression.concat(Expression.literal("vela-shield-road_"), Expression.toString(Expression.get("ref_length")))),
            PropertyFactory.textColor("#202124"),
            PropertyFactory.textOffset(arrayOf(0f, 0f)),
        )
    }

    /** The interstate / US-route silhouette: flat top, straight sides, rounded point below. */
    private fun shield(wDp: Float, d: Float, fill: Int, band: Int?, edge: Int): Bitmap {
        val w = wDp * d; val h = H * d; val r = 4f * d; val inset = 0.9f * d
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Path().apply {
            moveTo(r, inset)
            lineTo(w - r, inset)
            quadTo(w - inset, inset, w - inset, r)
            lineTo(w - inset, h * 0.6f)
            quadTo(w - inset, h - inset, w / 2f, h - inset)
            quadTo(inset, h - inset, inset, h * 0.6f)
            lineTo(inset, r)
            quadTo(inset, inset, r, inset)
            close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill })
        if (band != null) {
            c.save()
            c.clipPath(p)
            c.drawRect(RectF(0f, 0f, w, BAND * d), Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = band })
            c.restore()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * d; color = edge })
        return bmp
    }

    /** State routes and the international `road_N`: a rounded badge. */
    private fun pill(wDp: Float, d: Float, fill: Int, edge: Int): Bitmap {
        val w = wDp * d; val h = (H - 3f) * d; val r = 4 * d; val inset = 0.9f * d
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(inset, inset, w - inset, h - inset)
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill })
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * d; color = edge })
        return bmp
    }
}
