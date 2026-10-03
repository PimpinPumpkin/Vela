package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.vela.core.data.PaintedRoads
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The painted-roads test layers (developer dial `debug.vela.tune.paintedRoads`, user 2026-10-03):
 * median, road surface at the width its lanes need, crosswalks, stop lines, bike lanes, lane lines,
 * the center line and a turn arrow per lane, from [PaintedRoads]. Dial 1 streams the California bake
 * (`painted-roads` release, [BAKED_URL]); dial 2 builds them live for the view (Overpass, or
 * `debug.vela.paintUrl`). Sized in real meters (dp per meter = 2^z / 59960 at 40 degrees, the
 * `widenStreets` rule) so marks stay in their lanes at every zoom. Above the basemap's roads and
 * bridges, below the route line and every label, from z16.5.
 */
internal object PaintedRoadsLayer {
    private const val SRC = "vela-paint-src"
    private const val BAKED_SRC = "vela-paint-baked"
    const val BAKED_URL = "pmtiles://https://github.com/PimpinPumpkin/Vela/releases/download/painted-roads/painted-california.pmtiles?v=3"
    private val IDS = listOf("median", "surface", "crosswalk", "stop", "bike", "lane", "center", "arrow").map { "vela-paint-$it" }
    const val MIN_ZOOM = 16.5f
    private const val M_PER_DP_Z0 = 59960.0
    /** An arrow's length on the road, meters. */
    private const val ARROW_M = 8.0 // set by eye on a 4a: 5.0 drew about 3 m long

    private var lastMarks: List<PaintedRoads.Mark>? = null
    private var lastKey: String? = null

    private fun meters(prop: Expression): Expression = Expression.interpolate(
        Expression.exponential(2f), Expression.zoom(),
        Expression.stop(14, Expression.product(prop, Expression.literal(Math.pow(2.0, 14.0) / M_PER_DP_Z0))),
        Expression.stop(22, Expression.product(prop, Expression.literal(Math.pow(2.0, 22.0) / M_PER_DP_Z0))),
    )
    private fun metersConst(m: Double): Expression = meters(Expression.literal(m))
    private val thin = Expression.interpolate(Expression.linear(), Expression.zoom(),
        Expression.stop(16.5, 0.8f), Expression.stop(18, 1.3f), Expression.stop(20, 2.4f))
    private val fadeIn = Expression.interpolate(Expression.linear(), Expression.zoom(),
        Expression.stop(MIN_ZOOM, 0f), Expression.stop(MIN_ZOOM + 0.5f, 1f))

    fun clear(style: Style) {
        IDS.forEach { runCatching { style.removeLayer(it) } }
        runCatching { style.removeSource(SRC) }
        runCatching { style.removeSource(BAKED_SRC) }
        lastMarks = null; lastKey = null
    }

    /** Dial 1: the California bake, streamed by range request. */
    fun applyBaked(style: Style, dark: Boolean) {
        val key = "baked|$dark"
        if (key == lastKey && style.getSource(BAKED_SRC) != null) return
        clear(style)
        style.addSource(VectorSource(BAKED_SRC, BAKED_URL))
        addLayers(style, BAKED_SRC, "paint", dark)
        lastKey = key
    }

    /** Dial 2: marks built on the phone for the view. */
    fun apply(style: Style, marks: List<PaintedRoads.Mark>, dark: Boolean) {
        val key = "live|$dark"
        if (marks === lastMarks && key == lastKey && style.getSource(SRC) != null) return
        val fc = FeatureCollection.fromFeatures(marks.map { m ->
            val geom = if (m.kind == PaintedRoads.Kind.ARROW) Point.fromLngLat(m.points[0].lng, m.points[0].lat)
                else LineString.fromLngLats(m.points.map { Point.fromLngLat(it.lng, it.lat) })
            Feature.fromGeometry(geom).apply {
                addStringProperty("k", m.kind.name)
                addNumberProperty("off", m.offsetM)
                addNumberProperty("w", m.widthM)
                if (m.icon.isNotEmpty()) { addStringProperty("icon", m.icon); addNumberProperty("rot", m.rotDeg) }
            }
        })
        if (key != lastKey || style.getSource(SRC) == null) {
            clear(style)
            style.addSource(GeoJsonSource(SRC, fc, GeoJsonOptions().withMaxZoom(18)))
            addLayers(style, SRC, null, dark)
        } else style.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(fc)
        lastMarks = marks; lastKey = key
    }

    private fun addLayers(style: Style, src: String, sourceLayer: String?, dark: Boolean) {
        ensureArrowImages(style, dark)
        val roadColor = (style.getLayer("road_trunk_primary") as? LineLayer)?.lineColor?.value as? String
            ?: if (dark) "#476789" else "#AAB9C9"
        val paintWhite = if (dark) "#C9D2DC" else "#FFFFFF"
        val offset = PropertyFactory.lineOffset(meters(Expression.coalesce(Expression.get("off"), Expression.literal(0))))
        val width = meters(Expression.coalesce(Expression.get("w"), Expression.literal(0)))
        fun kind(k: PaintedRoads.Kind) = Expression.eq(Expression.get("k"), Expression.literal(k.name))
        fun line(id: String, k: PaintedRoads.Kind, vararg props: org.maplibre.android.style.layers.PropertyValue<*>) =
            LineLayer("vela-paint-$id", src).apply {
                sourceLayer?.let { setSourceLayer(it) }
                setFilter(kind(k))
                setProperties(*props)
            }
        val layers: List<Layer> = listOf(
            line("median", PaintedRoads.Kind.MEDIAN,
                PropertyFactory.lineColor(if (dark) "#22303F" else "#E4E8EE"), PropertyFactory.lineWidth(width),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)),
            line("surface", PaintedRoads.Kind.SURFACE,
                PropertyFactory.lineColor(roadColor), PropertyFactory.lineWidth(width),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)),
            // Dash 0.4 of the width: at 0.22 MapLibre's dash texture drew nothing.
            line("crosswalk", PaintedRoads.Kind.CROSSWALK,
                PropertyFactory.lineColor(paintWhite), PropertyFactory.lineWidth(metersConst(3.0)),
                PropertyFactory.lineDasharray(arrayOf(0.4f, 0.4f)), PropertyFactory.lineOpacity(fadeIn)),
            line("stop", PaintedRoads.Kind.STOP,
                PropertyFactory.lineColor(paintWhite), PropertyFactory.lineWidth(metersConst(0.6)),
                PropertyFactory.lineOpacity(fadeIn)),
            line("bike", PaintedRoads.Kind.BIKE,
                PropertyFactory.lineColor(if (dark) "#2E6B47" else "#86CFA0"), PropertyFactory.lineWidth(metersConst(1.2)),
                offset, PropertyFactory.lineOpacity(Expression.product(fadeIn, Expression.literal(0.9f)))),
            line("lane", PaintedRoads.Kind.LANE,
                PropertyFactory.lineColor(paintWhite), PropertyFactory.lineWidth(thin),
                PropertyFactory.lineDasharray(arrayOf(5f, 7f)), offset, PropertyFactory.lineOpacity(fadeIn)),
            line("center", PaintedRoads.Kind.CENTER,
                PropertyFactory.lineColor(if (dark) "#C9A227" else "#F2C200"), PropertyFactory.lineWidth(thin),
                PropertyFactory.lineGapWidth(thin), offset, PropertyFactory.lineOpacity(fadeIn)),
            SymbolLayer("vela-paint-arrow", src).apply {
                sourceLayer?.let { setSourceLayer(it) }
                setFilter(kind(PaintedRoads.Kind.ARROW))
                setProperties(
                    PropertyFactory.iconImage(Expression.concat(Expression.get("icon"), Expression.literal(if (dark) "-d" else "-l"))),
                    PropertyFactory.iconRotate(Expression.coalesce(Expression.get("rot"), Expression.literal(0))),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(arrowSize()),
                    PropertyFactory.iconOpacity(fadeIn),
                )
            },
        )
        var anchor = style.layers.lastOrNull { it.id.startsWith("bridge_") }?.id
        for (l in layers) {
            l.minZoom = MIN_ZOOM
            if (anchor != null) style.addLayerAbove(l, anchor) else style.addLayer(l)
            anchor = l.id
        }
    }

    // --- Arrow images: white glyphs drawn here, one per combination of left / through / right / U-turn.

    private const val ARROW_PX_H = 96
    private const val ARROW_PX_W = 72

    /** iconSize so the image's height is [ARROW_M] on the road at every zoom. */
    private fun arrowSize(): Expression {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val imageDp = ARROW_PX_H / density
        fun at(z: Double) = (ARROW_M * Math.pow(2.0, z) / M_PER_DP_Z0 / imageDp).toFloat()
        return Expression.interpolate(Expression.exponential(2f), Expression.zoom(),
            Expression.stop(14, at(14.0)), Expression.stop(22, at(22.0)))
    }

    private fun ensureArrowImages(style: Style, dark: Boolean) {
        val parts = listOf("uturn", "left", "through", "right")
        val color = if (dark) 0xFFC9D2DC.toInt() else 0xFFFFFFFF.toInt()
        for (mask in 1 until 16) {
            val set = parts.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val id = "arrow-" + set.joinToString("-") + if (dark) "-d" else "-l"
            if (style.getImage(id) == null) style.addImage(id, arrowBitmap(set.toSet(), color))
        }
    }

    /** The arrow(s) for one lane in the paint color, pointing UP (the way the lane drives). A plain
     *  bitmap per theme: as an SDF icon the strokes thinned to stems and the heads disappeared. */
    private fun arrowBitmap(parts: Set<String>, color: Int): Bitmap {
        val w = ARROW_PX_W.toFloat(); val h = ARROW_PX_H.toFloat()
        val bmp = Bitmap.createBitmap(ARROW_PX_W, ARROW_PX_H, Bitmap.Config.ARGB_8888)
        // MapLibre scales an image by its bitmap density; pin it to the screen's so arrowSize's math holds.
        bmp.density = android.content.res.Resources.getSystem().displayMetrics.densityDpi
        val c = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = w * 0.16f; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val cx = w / 2; val base = h * 0.97f; val fork = h * 0.55f
        val head = w * 0.24f
        fun headAt(x: Double, y: Double, angleDeg: Double) {
            val a = Math.toRadians(angleDeg)
            val tip = android.graphics.PointF((x + Math.cos(a) * head).toFloat(), (y + Math.sin(a) * head).toFloat())
            val l = android.graphics.PointF((x + Math.cos(a + 2.4) * head).toFloat(), (y + Math.sin(a + 2.4) * head).toFloat())
            val r = android.graphics.PointF((x + Math.cos(a - 2.4) * head).toFloat(), (y + Math.sin(a - 2.4) * head).toFloat())
            c.drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close() }, fill)
        }
        // The stem every arrow shares.
        val stemTop = if ("through" in parts) h * 0.16f else fork
        c.drawLine(cx, base, cx, stemTop, stroke)
        if ("through" in parts) headAt(cx.toDouble(), (h * 0.12f).toDouble(), -90.0)
        if ("left" in parts) {
            val ex = w * 0.16f; val ey = fork - h * 0.16f
            c.drawPath(Path().apply { moveTo(cx, fork + h * 0.06f); quadTo(cx, ey, ex + head * 0.6f, ey) }, stroke)
            headAt((ex + head * 0.2f).toDouble(), ey.toDouble(), 180.0)
        }
        if ("right" in parts) {
            val ex = w * 0.84f; val ey = fork - h * 0.16f
            c.drawPath(Path().apply { moveTo(cx, fork + h * 0.06f); quadTo(cx, ey, ex - head * 0.6f, ey) }, stroke)
            headAt((ex - head * 0.2f).toDouble(), ey.toDouble(), 0.0)
        }
        if ("uturn" in parts) {
            val lx = w * 0.2f
            c.drawPath(Path().apply { moveTo(cx, fork); cubicTo(cx, h * 0.15f, lx, h * 0.15f, lx, fork) ; lineTo(lx, fork + h * 0.12f) }, stroke)
            headAt(lx.toDouble(), (fork + h * 0.16f).toDouble(), 90.0)
        }
        return bmp
    }
}
