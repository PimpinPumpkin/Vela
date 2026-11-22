package app.vela.ui.map

import android.graphics.RectF
import app.vela.core.location.RoadSnap
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import java.lang.ref.WeakReference
import org.maplibre.android.geometry.LatLng as MLLatLng

/**
 * The drawn roads near a point, for the no-route dot snap ([RoadSnap], issue #803). Reads the
 * road lines the map has already laid out (`queryRenderedFeatures` in a small box around the fix)
 * instead of fetching anything, so it works on the online and the downloaded basemap alike.
 */
internal object RoadSnapQuery {
    private var idsStyle = WeakReference<Style>(null)
    private var ids: Array<String> = emptyArray()
    private val SKIP = listOf("casing", "rail", "hatching", "path", "pedestrian")
    /** The last call's native query and conversion times, for the dotLog line. */
    var lastQueryMs = 0.0
        private set
    var lastConvertMs = 0.0
        private set

    // The last answer, reused while the fix stays inside it: the native query waits for the
    // render thread (about a frame, 13 ms median on a Pixel 4a), so it runs every few seconds
    // over the road ahead, not on every fix.
    private var cacheStyle = WeakReference<Style>(null)
    private var cache: List<RoadSnap.Road>? = null
    private var cacheLat = Double.NaN
    private var cacheLng = Double.NaN
    private var cacheR = 0.0
    private var cacheAtMs = 0L

    fun clear() { cache = null; cacheStyle = WeakReference(null) }

    /** Road lines within at least [RoadSnap.MAX_M] of the point, or null when the map cannot say
     *  (no road layers, the point off screen). [speedMps] sizes a fresh query to cover about
     *  [AHEAD_S] of driving, and an answer that still covers the fix is reused. */
    fun roadsNear(map: MapLibreMap, style: Style, lat: Double, lng: Double, speedMps: Double, nowMs: Long): List<RoadSnap.Road>? {
        val held = cache
        if (held != null && cacheStyle.get() === style && nowMs - cacheAtMs <= REUSE_MS &&
            meters(lat, lng, cacheLat, cacheLng) + RoadSnap.MAX_M <= cacheR) return held
        val r = RoadSnap.MAX_M + (speedMps * AHEAD_S).coerceIn(MIN_AHEAD_M, MAX_AHEAD_M)
        val fresh = query(map, style, lat, lng, r) ?: return null
        cache = fresh; cacheStyle = WeakReference(style)
        cacheLat = lat; cacheLng = lng; cacheR = r; cacheAtMs = nowMs
        return fresh
    }

    private fun query(map: MapLibreMap, style: Style, lat: Double, lng: Double, radiusM: Double): List<RoadSnap.Road>? {
        if (idsStyle.get() !== style) {
            // Ids only: a Layer object must not outlive the call that fetched it.
            // One layer per kind of road: the casings repeat every line under it, and rail and
            // paths never count, so querying them only costs time (58 layers to 21 in Liberty).
            ids = runCatching {
                style.layers.filter { l ->
                    l is LineLayer && l.sourceLayer == "transportation" && SKIP.none { l.id.contains(it) }
                }.map { it.id }.toTypedArray()
            }.getOrDefault(emptyArray())
            idsStyle = WeakReference(style)
        }
        if (ids.isEmpty()) return null
        val proj = map.projection
        val p = proj.toScreenLocation(MLLatLng(lat, lng))
        if (p.x < 0 || p.y < 0 || p.x > map.width || p.y > map.height) return null
        // The box's half-size in pixels from the ground scale at the fix itself (the view may be
        // tilted, so the scale at the screen's center is not the one here). Parts of it off the
        // screen return nothing; the road ahead is on it.
        val dLat = radiusM / 111_320.0
        val dLng = radiusM / (111_320.0 * Math.cos(Math.toRadians(lat))).coerceAtLeast(1.0)
        val n = proj.toScreenLocation(MLLatLng(lat + dLat, lng))
        val e = proj.toScreenLocation(MLLatLng(lat, lng + dLng))
        val half = maxOf(Math.hypot((n.x - p.x).toDouble(), (n.y - p.y).toDouble()), Math.hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble())).toFloat()
        if (!(half > 0f) || half > 4_000f) return null
        val t0 = System.nanoTime()
        val feats = runCatching { map.queryRenderedFeatures(RectF(p.x - half, p.y - half, p.x + half, p.y + half), *ids) }.getOrNull() ?: return null
        val t1 = System.nanoTime()
        val out = ArrayList<RoadSnap.Road>()
        for (f in feats) runCatching { add(f, out) }
        lastQueryMs = (t1 - t0) / 1e6; lastConvertMs = (System.nanoTime() - t1) / 1e6
        queries++
        return out
    }

    /** Queries run since start, for the dotLog line. */
    var queries = 0
        private set

    private const val AHEAD_S = 4.0
    private const val MIN_AHEAD_M = 30.0
    private const val MAX_AHEAD_M = 120.0
    private const val REUSE_MS = 6_000L

    private fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val kx = 111_320.0 * Math.cos(Math.toRadians(aLat))
        return Math.hypot((bLat - aLat) * 111_320.0, (bLng - aLng) * kx)
    }

    private fun add(f: Feature, out: MutableList<RoadSnap.Road>) {
        val cls = if (f.hasProperty("class")) f.getStringProperty("class") else null
        val service = if (f.hasProperty("service")) f.getStringProperty("service") else null
        val penalty = RoadSnap.penaltyFor(cls, service) ?: return
        val oneway = if (f.hasProperty("oneway")) f.getNumberProperty("oneway")?.toInt() ?: 0 else 0
        val lines = when (val g = f.geometry()) {
            is LineString -> listOf(g.coordinates())
            is MultiLineString -> g.coordinates()
            else -> return
        }
        for (pts in lines) {
            if (pts.size < 2) continue
            out.add(RoadSnap.Road(DoubleArray(pts.size) { pts[it].latitude() }, DoubleArray(pts.size) { pts[it].longitude() }, oneway, penalty))
        }
    }
}
