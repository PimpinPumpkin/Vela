package app.vela.ui.map

import app.vela.core.data.PaintedRoads
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The painted-roads test layers (developer dial `debug.vela.tune.paintedRoads 1`, user 2026-10-03):
 * road surface at the width its lanes need, crosswalks, bike lanes, lane lines and the center line,
 * from [PaintedRoads]. Everything is sized in real meters (dp per meter = 2^z / 59960 at 40 degrees,
 * the same rule as `widenStreets`), so marks stay in their lanes at every zoom. Drawn above the
 * basemap's roads and bridges, below the route line and every label, from z16.5.
 */
internal object PaintedRoadsLayer {
    private const val SRC = "vela-paint-src"
    private const val SURFACE = "vela-paint-surface"
    private const val CROSSWALK = "vela-paint-crosswalk"
    private const val BIKE = "vela-paint-bike"
    private const val LANE = "vela-paint-lane"
    private const val CENTER = "vela-paint-center"
    private val LAYERS = listOf(SURFACE, CROSSWALK, BIKE, LANE, CENTER)
    const val MIN_ZOOM = 16.5f
    private const val M_PER_DP_Z0 = 59960.0

    private var lastMarks: List<PaintedRoads.Mark>? = null
    private var lastDark: Boolean? = null

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
        LAYERS.forEach { runCatching { style.removeLayer(it) } }
        runCatching { style.removeSource(SRC) }
        lastMarks = null; lastDark = null
    }

    fun apply(style: Style, marks: List<PaintedRoads.Mark>, dark: Boolean) {
        if (marks === lastMarks && dark == lastDark && style.getSource(SRC) != null) return
        val fc = FeatureCollection.fromFeatures(marks.map { m ->
            Feature.fromGeometry(LineString.fromLngLats(m.points.map { Point.fromLngLat(it.lng, it.lat) })).apply {
                addStringProperty("k", m.kind.name)
                addNumberProperty("off", m.offsetM)
                addNumberProperty("w", m.widthM)
            }
        })
        val src = style.getSourceAs<GeoJsonSource>(SRC)
        if (src == null) style.addSource(GeoJsonSource(SRC, fc, GeoJsonOptions().withMaxZoom(18))) else src.setGeoJson(fc)
        lastMarks = marks
        if (style.getLayer(SURFACE) != null && dark == lastDark) return
        LAYERS.forEach { runCatching { style.removeLayer(it) } }
        lastDark = dark
        val roadColor = (style.getLayer("road_trunk_primary") as? LineLayer)?.lineColor?.value as? String
            ?: if (dark) "#476789" else "#AAB9C9"
        val offset = PropertyFactory.lineOffset(meters(Expression.get("off")))
        fun kind(k: PaintedRoads.Kind) = Expression.eq(Expression.get("k"), Expression.literal(k.name))
        val layers = listOf(
            LineLayer(SURFACE, SRC).withProperties(
                PropertyFactory.lineColor(roadColor), PropertyFactory.lineWidth(meters(Expression.get("w"))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(kind(PaintedRoads.Kind.SURFACE)),
            LineLayer(CROSSWALK, SRC).withProperties(
                PropertyFactory.lineColor(if (dark) "#C9D2DC" else "#FFFFFF"), PropertyFactory.lineWidth(metersConst(3.0)),
                PropertyFactory.lineDasharray(arrayOf(0.4f, 0.4f)), PropertyFactory.lineOpacity(fadeIn),
            ).withFilter(kind(PaintedRoads.Kind.CROSSWALK)),
            LineLayer(BIKE, SRC).withProperties(
                PropertyFactory.lineColor(if (dark) "#2E6B47" else "#86CFA0"), PropertyFactory.lineWidth(metersConst(1.2)),
                offset, PropertyFactory.lineOpacity(Expression.product(fadeIn, Expression.literal(0.9f))),
            ).withFilter(kind(PaintedRoads.Kind.BIKE)),
            LineLayer(LANE, SRC).withProperties(
                PropertyFactory.lineColor(if (dark) "#C9D2DC" else "#FFFFFF"), PropertyFactory.lineWidth(thin),
                PropertyFactory.lineDasharray(arrayOf(5f, 7f)), offset, PropertyFactory.lineOpacity(fadeIn),
            ).withFilter(kind(PaintedRoads.Kind.LANE)),
            LineLayer(CENTER, SRC).withProperties(
                PropertyFactory.lineColor(if (dark) "#C9A227" else "#F2C200"), PropertyFactory.lineWidth(thin),
                PropertyFactory.lineGapWidth(thin), offset, PropertyFactory.lineOpacity(fadeIn),
            ).withFilter(kind(PaintedRoads.Kind.CENTER)),
        )
        var anchor = style.layers.lastOrNull { it.id.startsWith("bridge_") }?.id
        for (l in layers) {
            l.minZoom = MIN_ZOOM
            if (anchor != null) style.addLayerAbove(l, anchor) else style.addLayer(l)
            anchor = l.id
        }
    }
}
