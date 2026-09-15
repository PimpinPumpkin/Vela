package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
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
 * - `vela-shield-blue_N` / `vela-shield-green_N`: non-US families by sign color, see [FAMILY].
 * - `vela-exit_N`: the exit-number badge of [EXIT_LAYER], a layer this object also adds.
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
    /** Exit-number badge width per ref length, dp (9.5 pt bold). */
    private val EXIT_WIDTHS = mapOf(1 to 18f, 2 to 23f, 3 to 29f, 4 to 35f)
    const val EXIT_LAYER = "vela-exit-shield" // "shield" in the id: satellite's white-text pass skips it

    /**
     * Which badge a non-US ref gets. The tile's `network` is only set for a few sign systems
     * (gb-*, ie-*, e-road, ca-*; everywhere else it is "road" or absent), so: UK and Irish
     * motorways blue, UK trunk/primary and Irish national routes green (their sign colors), E-roads
     * and the Trans-Canada green, then any other `class=motorway` blue (the Vienna-convention
     * default for most of Europe; Japan's green and Switzerland's exceptions are not knowable
     * from the tile), and the rest a white badge.
     */
    private const val FAMILY = """["match", ["get", "network"],
        ["gb-motorway", "ie-motorway"], "blue",
        ["gb-trunk", "gb-primary", "ie-national", "e-road", "ca-transcanada"], "green",
        ["match", ["get", "class"], ["motorway"], "blue", "road"]]"""

    fun install(style: Style, d: Float, basemapSource: String?) {
        for ((n, w) in WIDTHS) {
            style.addImage("vela-shield-us-interstate_$n", shield(w, d, fill = 0xFF1F5AA8.toInt(), band = 0xFFC62828.toInt(), edge = 0xFFFFFFFF.toInt()))
            style.addImage("vela-shield-us-highway_$n", shield(w, d, fill = 0xFFFFFFFF.toInt(), band = null, edge = 0xFF3C4043.toInt()))
            val badge = pill(w, d, H - 3f, fill = 0xFFFFFFFF.toInt(), edge = 0xFF3C4043.toInt())
            style.addImage("vela-shield-us-state_$n", badge)
            style.addImage("vela-shield-road_$n", badge)
            style.addImage("vela-shield-blue_$n", pill(w, d, H - 3f, fill = 0xFF1F5AA8.toInt(), edge = 0xFFFFFFFF.toInt()))
            style.addImage("vela-shield-green_$n", pill(w, d, H - 3f, fill = 0xFF1E7B45.toInt(), edge = 0xFFFFFFFF.toInt()))
        }
        for ((n, w) in EXIT_WIDTHS) {
            style.addImage("vela-exit_$n", pill(w, d, 16f, fill = 0xFF1E7B45.toInt(), edge = 0xFFFFFFFF.toInt()))
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
            PropertyFactory.iconImage(Expression.raw("""["concat", "vela-shield-", $FAMILY, "_", ["to-string", ["get", "ref_length"]]]""")),
            PropertyFactory.textColor(Expression.raw("""["match", $FAMILY, ["blue", "green"], "#FFFFFF", "#202124"]""")),
            PropertyFactory.textOffset(arrayOf(0f, 0f)),
        )
        // Exit numbers (user 2026-09-15, "google has that these days"): OpenMapTiles carries
        // motorway junctions as `subclass=junction` points with the exit number in `ref`
        // (junction NAMES, the European and Japanese kind, have no ref and are skipped). A small
        // green badge, the US exit-sign color, from z12.5 so the freeway reads as a list of
        // exits the way Google draws it; stays up in nav (an exit number is nav information).
        if (basemapSource != null && style.getLayer(EXIT_LAYER) == null) {
            val layer = SymbolLayer(EXIT_LAYER, basemapSource).withSourceLayer("transportation_name")
                .withFilter(
                    Expression.all(
                        Expression.eq(Expression.get("subclass"), Expression.literal("junction")),
                        Expression.has("ref"),
                        Expression.lte(Expression.get("ref_length"), Expression.literal(4)),
                    ),
                )
                .withProperties(
                    PropertyFactory.iconImage(Expression.concat(Expression.literal("vela-exit_"), Expression.toString(Expression.get("ref_length")))),
                    PropertyFactory.iconSize(1f),
                    PropertyFactory.textField(Expression.toString(Expression.get("ref"))),
                    PropertyFactory.textSize(9.5f),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(0f),
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_POINT),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                    PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.iconPadding(4f),
                ).apply { minZoom = 12.5f }
            if (style.getLayer("road_shield_us") != null) style.addLayerAbove(layer, "road_shield_us") else style.addLayer(layer)
        }
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

    /** State routes, international refs, the colored European families and exit numbers: a rounded badge. */
    private fun pill(wDp: Float, d: Float, hDp: Float, fill: Int, edge: Int): Bitmap {
        val w = wDp * d; val h = hDp * d; val r = 4 * d; val inset = 0.9f * d
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(inset, inset, w - inset, h - inset)
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill })
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * d; color = edge })
        return bmp
    }
}
