package app.vela.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import app.vela.core.data.transit.Transitous
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The bike-share stations on the map (SPEC 8, Bike-share stations): a round teal badge with a
 * bike for each station and its free bikes beside it, gray when it has none to rent. One GeoJSON
 * source and one symbol layer, from [MIN_ZOOM], under the transit stops. Colors are fixed when
 * the layer is made, from the theme; a new theme or icon size makes it again, a new list only
 * replaces the data. An empty list takes the layer off the map.
 */
internal object BikeShareMapLayer {
    private const val SRC = "vela-bikeshare-src"
    private const val LAYER = "vela-bikeshare"
    /** The station key on each feature, for the tap handler. */
    const val KEY_PROP = "vela_bikeshare_key"
    private const val BIKES_PROP = "bikes"
    private const val EMPTY_PROP = "empty"
    private const val MIN_ZOOM = 15f
    private const val BADGE_PX = 44

    private var last: List<Transitous.BikeStation>? = null
    private var lastKey: String? = null
    private var glyphs: Typeface? = null

    fun clear(style: Style) {
        runCatching { style.removeLayer(LAYER) }
        runCatching { style.removeSource(SRC) }
        last = null
        lastKey = null
    }

    fun apply(
        context: Context,
        style: Style,
        stations: List<Transitous.BikeStation>,
        dark: Boolean,
        amoled: Boolean,
        iconScale: Float,
        labelScale: Float,
        below: String?,
    ) {
        if (stations.isEmpty()) {
            if (style.getSource(SRC) != null || style.getLayer(LAYER) != null) clear(style)
            last = stations
            return
        }
        val key = "$dark|$amoled|$iconScale|$labelScale"
        if (stations == last && key == lastKey && style.getSource(SRC) != null) return
        val fc = FeatureCollection.fromFeatures(
            stations.map { st ->
                Feature.fromGeometry(Point.fromLngLat(st.lon, st.lat)).apply {
                    addStringProperty(KEY_PROP, st.key)
                    // A station out of service shows no count: it has nothing to rent.
                    addStringProperty(BIKES_PROP, if (st.renting) st.bikes.toString() else "")
                    addBooleanProperty(EMPTY_PROP, !st.renting || st.bikes == 0)
                }
            },
        )
        if (key != lastKey || style.getSource(SRC) == null) {
            clear(style)
            val theme = if (dark || amoled) "d" else "l"
            val full = "vela-bikeshare-$theme"
            val empty = "vela-bikeshare-empty-$theme"
            if (style.getImage(full) == null) style.addImage(full, badge(context, if (dark || amoled) FILL_DARK else FILL_LIGHT))
            if (style.getImage(empty) == null) style.addImage(empty, badge(context, if (dark || amoled) EMPTY_DARK else EMPTY_LIGHT))
            style.addSource(GeoJsonSource(SRC, fc, GeoJsonOptions().withMaxZoom(18)))
            val isEmpty = Expression.eq(Expression.get(EMPTY_PROP), Expression.literal(true))
            val layer = SymbolLayer(LAYER, SRC).apply {
                setMinZoom(MIN_ZOOM)
                setProperties(
                    PropertyFactory.iconImage(Expression.switchCase(isEmpty, Expression.literal(empty), Expression.literal(full))),
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(15f, 0.9f * iconScale), Expression.stop(17f, 1.15f * iconScale), Expression.stop(19f, 1.4f * iconScale),
                        ),
                    ),
                    // Always drawn, and it claims its space so labels place around it (the transit
                    // stops' rule).
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(false),
                    PropertyFactory.iconPadding(2f),
                    PropertyFactory.textField(Expression.get(BIKES_PROP)),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11.5f * labelScale),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                    PropertyFactory.textOffset(arrayOf(0.95f, 0f)),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.textColor(
                        Expression.switchCase(
                            isEmpty, Expression.literal(if (dark || amoled) TEXT_EMPTY_DARK else TEXT_EMPTY_LIGHT),
                            Expression.literal(if (dark || amoled) TEXT_DARK else TEXT_LIGHT),
                        ),
                    ),
                    PropertyFactory.textHaloColor(if (amoled) "#000000" else if (dark) "#11161C" else "#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.3f),
                )
            }
            if (below != null && style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        } else {
            style.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(fc)
        }
        last = stations
        lastKey = key
    }

    /** The station badge: a white rim, a filled disc and Material Icons' bike in white. */
    private fun badge(context: Context, fill: Int): Bitmap {
        val s = BADGE_PX
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        // MapLibre scales an image by its bitmap density: pin it to the screen's.
        bmp.density = android.content.res.Resources.getSystem().displayMetrics.densityDpi
        val c = Canvas(bmp)
        val cx = s / 2f
        c.drawCircle(cx, cx, cx - 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        c.drawCircle(cx, cx, cx - 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        val tf = glyphs ?: runCatching { Typeface.createFromAsset(context.assets, "fonts/MaterialIcons-Regular.ttf") }.getOrNull()
            ?.also { glyphs = it }
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = s * 0.56f
            typeface = tf ?: Typeface.DEFAULT_BOLD
        }
        val text = if (tf != null) String(Character.toChars(BIKE_GLYPH)) else "B"
        val fm = glyph.fontMetrics
        c.drawText(text, cx, cx - (fm.ascent + fm.descent) / 2f, glyph)
        return bmp
    }

    /** Material Icons `directions_bike`, the glyph the shortcuts use for bike. */
    private const val BIKE_GLYPH = 0xe52f
    // The bike accent of the map's cycleways (`vela-bikeroutes`), light and dark.
    private const val FILL_LIGHT = 0xFF007B8B.toInt()
    private const val FILL_DARK = 0xFF1F8F9C.toInt()
    private const val EMPTY_LIGHT = 0xFF9AA0A6.toInt()
    private const val EMPTY_DARK = 0xFF5F6368.toInt()
    private const val TEXT_LIGHT = "#00616E"
    private const val TEXT_DARK = "#7FD0DA"
    private const val TEXT_EMPTY_LIGHT = "#80868B"
    private const val TEXT_EMPTY_DARK = "#9AA0A6"
}
