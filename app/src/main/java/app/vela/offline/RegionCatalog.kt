package app.vela.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable region asset, from a manifest. [s]/[w]/[n]/[e] = covered bbox. Shared by the obf,
 *  place-pack, overlay and basemap catalogs (same row shape); the trailing fields are pack-only
 *  (update revision + row counts + optional row-level delta) and stay at their defaults elsewhere. */
data class RoutingRegion(
    val id: String,
    val name: String,
    val url: String,
    val sizeMb: Int,
    val s: Double,
    val w: Double,
    val n: Double,
    val e: Double,
    val installedMb: Int = 0,               // unpacked on-disk size; 0 in old manifests (estimate from the zip)
    val rev: Int = 0,                       // pack revision (bumped every rebuild)
    val counts: Map<String, Long> = emptyMap(), // expected per-table row counts, verifies a delta apply
    val deltaUrl: String? = null,           // row-level delta from [deltaFromRev] to [rev], if published
    val deltaFromRev: Int = 0,
    val deltaSizeMb: Int = 0,
) {
    /** Whether this region holds the point: its real boundary where [RegionPolys] has one, else
     *  the box. Every "which region is this point in" decision goes through here (issue #599). */
    fun covers(lat: Double, lng: Double): Boolean =
        RegionPolys.covers(id, lat, lng) ?: RegionPolys.boxCovers(s, w, n, e, lat, lng)

    /** The box's area in square degrees, the tie-break among covering regions: the smallest one is
     *  the specific region for a point where boxes overlap at a border. */
    fun boxArea(): Double = (n - s) * (e - w)
}

/**
 * The region catalog fetch: reads a `{regions:[...]}` manifest into [RoutingRegion] rows. The obf
 * routing catalog is the one caller today; the store that used to live here (the GraphHopper
 * graph download, its index and the road-name sidecar) was retired on 2026-09-15 once the obf
 * carried the speed limit and the romanized names itself.
 */
@Singleton
class RegionCatalog @Inject constructor(
    private val http: OkHttpClient,
) {
    suspend fun manifest(manifestUrl: String): List<RoutingRegion> = withContext(Dispatchers.IO) {
        runCatching {
            val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
            val arr = JSONObject(json).getJSONArray("regions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("bbox") // [S, W, N, E]
                RoutingRegion(
                    o.getString("id"), o.getString("name"), o.getString("url"), o.optInt("sizeMb"),
                    b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3),
                    installedMb = o.optInt("installedMb"),
                    rev = o.optInt("rev"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
