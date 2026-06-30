package app.vela.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable region routing graph, from the manifest. */
data class RoutingRegion(val id: String, val name: String, val url: String, val sizeMb: Int)

/**
 * Offline ROUTING graphs (the heavier sibling of [OfflineMaps]' offline *tiles*). Routing graphs are
 * prebuilt **per fixed metro region** off-device (`tools/graphbuilder`, with Contraction Hierarchies)
 * — not arbitrary bounding boxes like tiles — and hosted as static assets (GitHub release assets, like
 * the APK). This downloads + unzips the chosen region's CH graph into **internal** storage
 * (`filesDir/routing-graph`), exactly where `GraphHopperRouteEngine` loads it, so `directions()` can
 * route fully on-device when offline. One region at a time for now (a metro CH graph ≈ 53 MB).
 */
@Singleton
class RoutingGraphStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val graphDir = File(context.filesDir, "routing-graph")
    private val marker = File(context.filesDir, "routing-graph.region")

    /** The region id currently installed, or null if no (complete) graph is present. */
    fun installedRegionId(): String? =
        if (File(graphDir, "properties").exists() && marker.exists())
            marker.readText().trim().ifEmpty { null }
        else null

    /** Fetch the list of available region graphs from [manifestUrl] (`{"regions":[{id,name,url,sizeMb}]}`). */
    suspend fun manifest(manifestUrl: String): List<RoutingRegion> = withContext(Dispatchers.IO) {
        runCatching {
            val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
            val arr = JSONObject(json).getJSONArray("regions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RoutingRegion(o.getString("id"), o.getString("name"), o.getString("url"), o.optInt("sizeMb"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Download + unzip [region]'s graph into internal storage, replacing any existing graph.
     * [onProgress] gets 0..100 (download %). Returns true on success. Never throws.
     */
    suspend fun download(region: RoutingRegion, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "routing-graph.tmp")
        runCatching {
            tmp.deleteRecursively(); tmp.mkdirs()
            http.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
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
            graphDir.deleteRecursively()
            check(tmp.renameTo(graphDir)) { "could not install graph (rename failed)" }
            marker.writeText(region.id)
            onProgress(100)
            true
        }.getOrElse { tmp.deleteRecursively(); false }
    }

    fun delete() {
        graphDir.deleteRecursively()
        marker.delete()
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
