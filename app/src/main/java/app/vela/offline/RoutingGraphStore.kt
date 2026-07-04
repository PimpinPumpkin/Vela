package app.vela.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable region routing graph, from the manifest. [s]/[w]/[n]/[e] = covered bbox. */
data class RoutingRegion(
    val id: String,
    val name: String,
    val url: String,
    val sizeMb: Int,
    val s: Double,
    val w: Double,
    val n: Double,
    val e: Double,
)

/**
 * Offline ROUTING graphs (the heavier sibling of [OfflineMaps]' offline *tiles*). Each region is a
 * prebuilt **Contraction-Hierarchies** graph (`tools/graphbuilder`), hosted as a static asset (GitHub
 * release asset, like the APK). **Multiple regions** can be installed — download the areas you travel —
 * each into `filesDir/graphs/<id>/`, with an `index.json` (`[{id, bbox:[S,W,N,E]}]`) that
 * `GraphHopperRouteEngine` reads to pick, per trip, the region covering both endpoints. A GraphHopper
 * graph is monolithic (a trip must fit inside one region), so regions are state/country-sized.
 */
@Singleton
class RoutingGraphStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val graphsRoot = File(context.filesDir, "graphs")
    private val indexFile = File(graphsRoot, "index.json")

    // Region graphs (tens–hundreds of MB) can outrun the shared client's 12s callTimeout (a scrape bound,
    // not a download bound) on a slow link or a big region — the call aborts, runCatching eats it, the
    // graph silently never installs. Derive an unbounded-call client for the body download (same fix as
    // KokoroInstaller / OverlayTileStore). The manifest fetch stays on the short-timeout shared client.
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Region ids with a complete graph on disk. */
    fun installedIds(): Set<String> =
        readIndex().keys.filter { File(File(graphsRoot, it), "properties").exists() }.toSet()

    /** Fetch the catalog of available region graphs from [manifestUrl]. */
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
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Download + unzip [region]'s graph into `graphs/<id>/` and register it in the index. 0..100 progress. */
    suspend fun download(region: RoutingRegion, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        graphsRoot.mkdirs()
        val dir = File(graphsRoot, region.id)
        val tmp = File(graphsRoot, "${region.id}.tmp")
        runCatching {
            tmp.deleteRecursively(); tmp.mkdirs()
            downloadHttp.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body!!.contentLength()
                var lastPct = -1
                val counting = CountingInputStream(resp.body!!.byteStream()) { read ->
                    if (total > 0) (100 * read / total).toInt().let { p -> if (p != lastPct) { lastPct = p; onProgress(p) } }
                }
                ZipInputStream(counting).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val f = File(tmp, e.name)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zis.copyTo(it) } }
                        e = zis.nextEntry
                    }
                }
            }
            check(File(tmp, "properties").exists()) { "downloaded graph is incomplete (no 'properties')" }
            dir.deleteRecursively()
            check(tmp.renameTo(dir)) { "could not install graph (rename failed)" }
            writeIndex(readIndex() + (region.id to doubleArrayOf(region.s, region.w, region.n, region.e)))
            onProgress(100)
            true
        }.getOrElse { tmp.deleteRecursively(); false }
    }

    fun delete(id: String) {
        File(graphsRoot, id).deleteRecursively()
        writeIndex(readIndex() - id)
    }

    private fun readIndex(): Map<String, DoubleArray> = runCatching {
        if (!indexFile.exists()) return emptyMap()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i); val b = o.getJSONArray("bbox")
            o.getString("id") to doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }.getOrDefault(emptyMap())

    private fun writeIndex(map: Map<String, DoubleArray>) {
        graphsRoot.mkdirs()
        val arr = JSONArray()
        map.forEach { (id, b) ->
            arr.put(JSONObject().put("id", id).put("bbox", JSONArray().put(b[0]).put(b[1]).put(b[2]).put(b[3])))
        }
        indexFile.writeText(arr.toString())
    }

    /** Counts bytes pulled from the network so download progress can be reported. */
    private class CountingInputStream(private val wrapped: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var count = 0L
        override fun read(): Int = wrapped.read().also { if (it >= 0) onRead(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
        override fun available(): Int = wrapped.available()
        override fun close() = wrapped.close()
    }
}
