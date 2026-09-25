package app.vela.offline

import android.content.Context
import org.json.JSONObject

/**
 * The catalog regions' REAL boundaries, so a region is picked for a point by the polygon its
 * extract was cut with and not by its bounding box (issue #599).
 *
 * A box is not coverage. Vietnam's Geofabrik extract carries the island claims, so its box reaches
 * 114.6 E and swallows Hong Kong; while China was absent from the routing catalog nothing smaller
 * competed, and "download the area you're viewing" from Hong Kong announced "Downloading Vietnam".
 * Kansas's box crossing the Missouri River is the same fact in another place, handled there by
 * streaming three candidates. Geofabrik publishes the polygon beside every extract, so
 * `scripts/region-polys.py` bakes all of them, simplified to a few kilometers, into
 * `assets/region_polys.json` (about 340 KB for 458 regions).
 *
 * [covers] answers null for a region it has no polygon for (a catalog whose ids are not the
 * routing catalog's, such as the building overlays, or a row added since the last bake), and every
 * caller falls back to the box in that case, so a missing polygon behaves exactly as before.
 * Loaded once, off the main thread, at app start; until then every answer is null.
 */
object RegionPolys {

    private const val ASSET = "region_polys.json"

    /** Outer rings and holes as flat [lat, lng, lat, lng, ...] arrays. */
    class Entry(val outers: List<DoubleArray>, val holes: List<DoubleArray>)

    @Volatile private var table: Map<String, Entry>? = null

    fun ensureLoaded(context: Context) {
        if (table != null) return
        table = runCatching {
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyMap())
    }

    /** Whether the region [id]'s polygon contains the point; null when there is no polygon to ask. */
    fun covers(id: String, lat: Double, lng: Double): Boolean? {
        val e = table?.get(id) ?: return null
        if (e.outers.none { inside(lat, lng, it) }) return false
        return e.holes.none { inside(lat, lng, it) }
    }

    val loaded: Boolean get() = table != null

    /**
     * The box fallback for a region with no polygon. A box that spans the whole globe in
     * longitude is an extract that crosses the antimeridian (Alaska's Aleutians reach past 180),
     * and its true footprint is unknowable from the box: taken literally it "covered" every point
     * in its latitude band, so the Alaska address overlay claimed Germany and the Netherlands and
     * hid the basemap house numbers there (issue #257, 2026-09-22). Such a box never covers by
     * itself; only a polygon can answer for it. A box that also spans (nearly) all latitudes is
     * a real whole-world row (the low-zoom world basemap), not a crossing, and still covers.
     */
    fun boxCovers(s: Double, w: Double, n: Double, e: Double, lat: Double, lng: Double): Boolean =
        (e - w < WORLD_SPAN || n - s >= WORLD_BAND) && lat in s..n && lng in w..e

    /** A longitude span this wide is the antimeridian, not a region... */
    const val WORLD_SPAN = 350.0
    /** ...unless the box is this tall too, which is the whole world on purpose. */
    const val WORLD_BAND = 120.0

    internal fun parse(json: String): Map<String, Entry> {
        val root = JSONObject(json)
        val out = HashMap<String, Entry>(root.length() * 2)
        for (id in root.keys()) {
            val o = root.getJSONObject(id)
            fun rings(key: String): List<DoubleArray> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { i ->
                    val r = arr.getJSONArray(i)
                    DoubleArray(r.length()) { j -> r.getDouble(j) }
                }
            }
            out[id] = Entry(rings("o"), rings("h"))
        }
        return out
    }

    /** Ray cast against a flat [lat, lng, ...] ring. Planar on purpose: the rings were simplified to
     *  kilometers and the question is which country, so the meridian at 180 is the only edge case,
     *  and no catalog polygon crosses it. */
    internal fun inside(lat: Double, lng: Double, ring: DoubleArray): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        var hit = false
        var j = n - 1
        for (i in 0 until n) {
            val yi = ring[2 * i]; val xi = ring[2 * i + 1]
            val yj = ring[2 * j]; val xj = ring[2 * j + 1]
            if ((yi > lat) != (yj > lat)) {
                val x = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lng < x) hit = !hit
            }
            j = i
        }
        return hit
    }
}
