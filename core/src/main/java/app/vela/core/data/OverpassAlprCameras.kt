package app.vela.core.data

import app.vela.core.model.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** An automated licence-plate reader (ALPR / "Flock") camera at [loc]. [operator] is the agency/company
 *  that runs it when tagged (e.g. "Flock Safety"), [direction] the way it points (OSM `direction`, degrees
 *  or a compass string) when known - both may be blank. */
data class AlprCamera(val loc: LatLng, val operator: String = "", val direction: String = "")

/**
 * Fetches ALPR surveillance-camera locations from **Overpass** (OpenStreetMap's keyless query API). These are
 * the cameras the community DeFlock project maps as `man_made=surveillance` + `surveillance:type=ALPR`
 * (Flock Safety, Motorola, police plate readers). Sibling of [OverpassTrafficSignals]; same best-effort,
 * area-cached, viewport-fetched contract. Coverage is OSM's/DeFlock's - strong in the US, growing elsewhere.
 */
object OverpassAlprCameras {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ALPR camera nodes inside a bounding box, for DRAWING on the map. Matches the canonical DeFlock tag
     * `surveillance:type=ALPR` AND the older `man_made=surveillance` + `camera:type` plate-reader form some
     * imports used. Returns **null on FAILURE** (network/timeout/non-2xx) and a (possibly empty) list on a
     * SUCCESSFUL parse - the caller area-caches success only, so a failure retries instead of stamping a
     * false "no cameras here". Queried per padded viewport (area-cached; cameras are static), NOT per fix.
     */
    fun fetchInBox(
        http: OkHttpClient,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 4000,
    ): List<AlprCamera>? {
        return try {
            val box = "($south,$west,$north,$east)"
            // `out body` (NOT `out tags`): for a node, `out tags` prints only id + tags and OMITS
            // lat/lon, so the parser below dropped every camera (no coordinates) and the layer drew
            // nothing. `out body` is the default full print - id, lat, lon, AND tags - which is what
            // we need to both place the marker and read operator/direction. (device-caught 2026-07-12)
            val query = "[out:json][timeout:25];" +
                "node[\"surveillance:type\"=\"ALPR\"]$box;out body $limit;"
            val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "VelaMaps/0.1 (+https://github.com/PimpinPumpkin/Vela)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null // a failure, NOT a genuine empty area - don't cache it
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["elements"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lng = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val tags = o["tags"]?.jsonObject
                    // Real DeFlock nodes tag the vendor as `manufacturer` ("Flock Safety"), not
                    // `operator` (which is usually the agency and often absent) - fall back to it so
                    // the camera actually shows who runs it.
                    val op = ((tags?.get("operator") ?: tags?.get("manufacturer")) as? JsonPrimitive)
                        ?.content.orEmpty()
                    val dir = (tags?.get("direction") as? JsonPrimitive)?.content.orEmpty()
                    AlprCamera(LatLng(lat, lng), op, dir)
                }.orEmpty()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** ALPR cameras within [nearMeters] of the route [polyline] - for the "this route passes N
     *  cameras" alert on the route picker. Queries the route in LOCAL TILES ([routeTiles]) rather than
     *  one bbox: a cross-city/state trip's single bbox is enormous, so one `[timeout:25]` Overpass
     *  query over it times out and silently returns nothing - a route full of cameras then badges "0",
     *  indistinguishable from a genuinely clean one. Small tiles almost never time out, and a single
     *  failed tile just drops that stretch instead of zeroing the whole count. Cameras are then kept
     *  only if they're within [nearMeters] of an actual line SEGMENT (not just a vertex - decoded
     *  polylines are sparse on freeway straights, so a gantry sitting on the line but far from both
     *  bracketing shape points used to be missed). Empty when every tile fails or nothing is near. */
    fun fetchAlong(
        http: OkHttpClient,
        polyline: List<LatLng>,
        nearMeters: Double = 120.0,
        limit: Int = 4000,
    ): List<AlprCamera> {
        if (polyline.size < 2) return emptyList()
        val seen = HashSet<String>()
        val all = ArrayList<AlprCamera>()
        for (t in routeTiles(polyline, padDeg = 0.003, maxSpanDeg = 0.25, maxTiles = 40)) {
            val cams = fetchInBox(http, t[0], t[1], t[2], t[3], limit) ?: continue // failed tile: skip, keep the rest
            for (cam in cams) {
                // Dedupe cameras that fall in the overlap between adjacent (padded) tiles.
                val key = "${(cam.loc.lat * 1e5).toLong()},${(cam.loc.lng * 1e5).toLong()}"
                if (seen.add(key)) all.add(cam)
            }
        }
        return all.filter { nearPolyline(it.loc, polyline, nearMeters) }
    }

    /** Split a route's coverage into local tiles (each at most [maxSpanDeg] on a side, padded by
     *  [padDeg]) so no single Overpass query spans a huge box. Only tiles the line actually enters are
     *  returned (a diagonal route touches a diagonal band of the grid, not the whole rectangle), capped
     *  at [maxTiles]. Each entry is [south, west, north, east]. Pure/side-effect-free (unit-tested). */
    internal fun routeTiles(
        polyline: List<LatLng>,
        padDeg: Double,
        maxSpanDeg: Double,
        maxTiles: Int,
    ): List<DoubleArray> {
        if (polyline.isEmpty()) return emptyList()
        val minLat = polyline.minOf { it.lat }; val maxLat = polyline.maxOf { it.lat }
        val minLng = polyline.minOf { it.lng }; val maxLng = polyline.maxOf { it.lng }
        val latSpan = maxLat - minLat; val lngSpan = maxLng - minLng
        if (latSpan <= maxSpanDeg && lngSpan <= maxSpanDeg) {
            return listOf(doubleArrayOf(minLat - padDeg, minLng - padDeg, maxLat + padDeg, maxLng + padDeg))
        }
        var rows = Math.ceil(latSpan / maxSpanDeg).toInt().coerceAtLeast(1)
        var cols = Math.ceil(lngSpan / maxSpanDeg).toInt().coerceAtLeast(1)
        // Guard a pathological grid (a very long route): grow the cell until the grid fits maxTiles.
        while (rows.toLong() * cols.toLong() > maxTiles) { rows = (rows + 1) / 2; cols = (cols + 1) / 2 }
        val cellH = latSpan / rows; val cellW = lngSpan / cols
        val touched = LinkedHashSet<Long>()
        for (p in polyline) {
            val r = if (cellH <= 0) 0 else ((p.lat - minLat) / cellH).toInt().coerceIn(0, rows - 1)
            val c = if (cellW <= 0) 0 else ((p.lng - minLng) / cellW).toInt().coerceIn(0, cols - 1)
            touched.add(r * 1000L + c)
        }
        return touched.map { key ->
            val r = (key / 1000L).toInt(); val c = (key % 1000L).toInt()
            doubleArrayOf(
                minLat + r * cellH - padDeg,
                minLng + c * cellW - padDeg,
                minLat + (r + 1) * cellH + padDeg,
                minLng + (c + 1) * cellW + padDeg,
            )
        }
    }

    /** True if [p] is within [meters] of any SEGMENT of [polyline] (point-to-segment, not just to a
     *  vertex). Pure/unit-tested. */
    internal fun nearPolyline(p: LatLng, polyline: List<LatLng>, meters: Double): Boolean {
        for (i in 0 until polyline.size - 1) {
            if (segDistMeters(p, polyline[i], polyline[i + 1]) <= meters) return true
        }
        return polyline.size == 1 && segDistMeters(p, polyline[0], polyline[0]) <= meters
    }

    /** Distance in metres from point [p] to the segment [a]-[b], via a local equirectangular
     *  projection (exact enough at the ~100 m scale this gate uses). */
    internal fun segDistMeters(p: LatLng, a: LatLng, b: LatLng): Double {
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0))
        val bx = (b.lng - a.lng) * mPerLng; val by = (b.lat - a.lat) * mPerLat
        val px = (p.lng - a.lng) * mPerLng; val py = (p.lat - a.lat) * mPerLat
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val ex = px - t * bx; val ey = py - t * by
        return Math.sqrt(ex * ex + ey * ey)
    }
}
