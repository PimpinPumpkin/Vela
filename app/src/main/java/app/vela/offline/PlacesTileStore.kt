package app.vela.offline

import android.content.Context
import app.vela.core.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The open-data places layer (Overture Places baked to PMTiles by `tools/build-places-region.sh`,
 * one archive per region). Two ways it reaches the map: an archive dropped into `files/places/`
 * (an offline region, the same `pmtiles://file://` path the building overlays use), else the
 * manifest's regions covering the view, streamed by HTTP range requests. Business POIs with a
 * prominence baked in, so the map draws them the way it draws Google's ambient dots and Google
 * is asked only when one is tapped.
 */
@Singleton
class PlacesTileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    data class Region(val id: String, val url: String, val s: Double, val w: Double, val n: Double, val e: Double) {
        fun covers(p: LatLng) = p.lat in s..n && p.lng in w..e
        fun area() = (n - s) * (e - w)
    }

    private val root = File(context.filesDir, "places")

    @Volatile private var cached: List<Region>? = null

    /** Every archive installed locally, whatever region it covers; MapLibre simply finds no tiles
     *  outside its bounds. */
    fun installed(): List<File> = root.listFiles { f -> f.extension == "pmtiles" }.orEmpty().sortedBy { it.name }

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

    /** Source URIs for [center]: local archives first, then the smallest manifest regions covering it. */
    suspend fun sourcesFor(center: LatLng?, manifestUrl: String): List<String> {
        val local = installed().map { "pmtiles://file://${it.absolutePath}" }
        val c = center ?: return local
        val streamed = runCatching { manifest(manifestUrl) }.getOrDefault(emptyList())
            .filter { it.covers(c) }.sortedBy { it.area() }.take(2).map { "pmtiles://${it.url}" }
        return (local + streamed).distinct()
    }
}
