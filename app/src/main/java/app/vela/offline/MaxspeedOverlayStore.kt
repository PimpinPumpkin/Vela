package app.vela.offline

import android.content.Context
import app.vela.core.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Speed B" source: the hosted **posted-speed-limit PMTiles overlays** (OSM `maxspeed` ways, built by
 * `scripts/build-maxspeed-region.sh`), consumed STREAMING - unlike the routing/building/address stores this
 * never downloads a file, it just hands the map view a `pmtiles://https://…` URI so MapLibre range-fetches
 * only the visible tiles. The map adds it as an invisible line layer and `queryRenderedFeatures` under the
 * puck reads the `maxspeed` tag, so a posted-limit sign shows anywhere online without the offline graph.
 *
 * Manifest is the same `{regions:[{id,name,url,sizeMb,bbox:[S,W,N,E]}]}` shape as the other overlays; fetched
 * once and cached in memory for the process (speed limits don't move). Best-effort - a failure just means no
 * online limit (the offline graph or a blank sign stands in).
 */
@Singleton
class MaxspeedOverlayStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    /** One catalog row: a region's streamed PMTiles [url] and its [s]/[w]/[n]/[e] bbox. */
    data class Region(val id: String, val url: String, val s: Double, val w: Double, val n: Double, val e: Double) {
        fun covers(p: LatLng) = p.lat in s..n && p.lng in w..e
        fun area() = (n - s) * (e - w)
    }

    @Volatile private var cached: List<Region>? = null

    /** Fetch + cache the overlay catalog (once per process). Empty on any failure. */
    suspend fun manifest(manifestUrl: String): List<Region> {
        cached?.let { return it }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                    .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
                val arr = JSONObject(json).getJSONArray("regions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONArray("bbox") // [S, W, N, E]
                    Region(o.getString("id"), o.getString("url"), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                }
            }.getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) cached = fetched
        return fetched
    }

    /** The streamed `pmtiles://https://…` source URIs for the overlays covering [center] (smallest region
     *  first, so a state overlay wins over a whole-country one). Empty when no overlay covers the point. */
    suspend fun sourcesFor(center: LatLng, manifestUrl: String): List<String> =
        manifest(manifestUrl).filter { it.covers(center) }.sortedBy { it.area() }.map { "pmtiles://${it.url}" }
}
