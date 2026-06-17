package app.vela.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.maplibre.android.maps.Style

/**
 * Google-style POI markers: a category-coloured circle with a white Material
 * Icons glyph in the middle, generated at runtime and registered on the style as
 * `vela-poi-<group>` images. The bundled OpenFreeMap style (liberty-roboto.json)
 * references them from its POI layers via an `icon-image` match on `class`.
 * Keyless — the Material Icons font is bundled in assets.
 *
 * The group keys + colours here MUST stay in sync with the match expression baked
 * into the style asset (see the python transform that generates it).
 */
object PoiIcons {

    // group -> (Material Icons codepoint, circle colour)
    private val GROUPS = listOf(
        Triple("food", 0xe56c, "#E8710A"),
        Triple("shop", 0xe8cc, "#4285F4"),
        Triple("lodging", 0xe53a, "#C2185B"),
        Triple("fuel", 0xe546, "#1967D2"),
        Triple("parking", 0xe54f, "#1A73E8"),
        Triple("park", 0xea63, "#188038"),
        Triple("health", 0xe548, "#D93025"),
        Triple("edu", 0xe80c, "#00897B"),
        Triple("civic", 0xe84f, "#5F6368"),
        Triple("culture", 0xea36, "#9334E6"),
        Triple("sport", 0xeb43, "#E37400"),
        Triple("transit", 0xe530, "#1A73E8"),
        Triple("default", 0xe55f, "#5F6368"),
    )

    fun addTo(context: Context, style: Style) {
        val tf = runCatching {
            Typeface.createFromAsset(context.assets, "fonts/MaterialIcons-Regular.ttf")
        }.getOrNull() ?: return
        GROUPS.forEach { (key, codepoint, color) ->
            if (style.getImage("vela-poi-$key") == null) {
                style.addImage("vela-poi-$key", marker(tf, codepoint, color))
            }
        }
    }

    private fun marker(tf: Typeface, codepoint: Int, colorHex: String): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val r = size / 2f - 5f
        canvas.drawCircle(cx, cx, r + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(colorHex) })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = Color.WHITE
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val glyph = String(Character.toChars(codepoint))
        val fm = text.fontMetrics
        canvas.drawText(glyph, cx, cx - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }
}
