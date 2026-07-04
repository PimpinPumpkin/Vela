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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open BUILDING-FOOTPRINT overlays (Microsoft US Building Footprints, ODbL) as **PMTiles** — the
 * gap-fill sibling of [RoutingGraphStore]. Each region is ONE `.pmtiles` archive (no unzip, unlike the
 * routing graph's folder) downloaded into `filesDir/overlays/<id>.pmtiles`, with an `index.json`
 * (`[{id, bbox:[S,W,N,E]}]`) the map view reads to add a `pmtiles://` source + a fill layer BENEATH the
 * OSM building layer, so footprints only fill where OSM is thin (a suburb the Microsoft→OSM import missed).
 *
 * Reuses [RoutingRegion] as the manifest row (same `{id,name,url,sizeMb,bbox}` shape) — the overlay manifest
 * (`OVERLAY_MANIFEST_URL`) is built by the same CI pattern as the routing one.
 */
@Singleton
class OverlayTileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val overlaysRoot = File(context.filesDir, "overlays")
    private val indexFile = File(overlaysRoot, "index.json")

    /** id → the `.pmtiles` file, for the regions actually present on disk. */
    fun installed(): Map<String, File> =
        readIndex().keys.mapNotNull { id -> fileFor(id).takeIf { it.exists() }?.let { id to it } }.toMap()

    fun installedIds(): Set<String> = installed().keys

    private fun fileFor(id: String) = File(overlaysRoot, "$id.pmtiles")

    /** Installed regions + bbox (`[S,W,N,E]`), so the map view can add only the overlays that exist. */
    fun installedRegions(): List<Pair<String, DoubleArray>> =
        readIndex().filterKeys { fileFor(it).exists() }.map { it.key to it.value }

    /** Fetch the catalog of available overlays from [manifestUrl] (same shape as the routing manifest). */
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

    /** Download [region]'s `.pmtiles` into `overlays/<id>.pmtiles` (atomic via a .tmp) + register it. 0..100. */
    suspend fun download(region: RoutingRegion, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        overlaysRoot.mkdirs()
        val file = fileFor(region.id)
        val tmp = File(overlaysRoot, "${region.id}.pmtiles.tmp")
        runCatching {
            http.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body!!.contentLength()
                var lastPct = -1
                val counting = CountingInputStream(resp.body!!.byteStream()) { read ->
                    if (total > 0) (100 * read / total).toInt().let { p -> if (p != lastPct) { lastPct = p; onProgress(p) } }
                }
                tmp.outputStream().use { counting.copyTo(it) }
            }
            // PMTiles v3 archives start with the 7-byte magic "PMTiles"; guard against an HTML error page
            // slipping through as a "download" (which would blank the map source, not just fail quietly).
            check(tmp.length() > 127 && tmp.inputStream().use { s -> ByteArray(7).let { s.read(it); String(it) } } == "PMTiles")
                { "downloaded overlay isn't a PMTiles archive" }
            file.delete()
            check(tmp.renameTo(file)) { "could not install overlay (rename failed)" }
            writeIndex(readIndex() + (region.id to doubleArrayOf(region.s, region.w, region.n, region.e)))
            onProgress(100)
            true
        }.getOrElse { tmp.delete(); false }
    }

    fun delete(id: String) {
        fileFor(id).delete()
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
        overlaysRoot.mkdirs()
        val arr = JSONArray()
        map.forEach { (id, b) ->
            arr.put(JSONObject().put("id", id).put("bbox", JSONArray().put(b[0]).put(b[1]).put(b[2]).put(b[3])))
        }
        indexFile.writeText(arr.toString())
    }

    /** Counts bytes pulled so download progress can be reported (same helper shape as RoutingGraphStore). */
    private class CountingInputStream(private val wrapped: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var count = 0L
        override fun read(): Int = wrapped.read().also { if (it >= 0) onRead(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
        override fun available(): Int = wrapped.available()
        override fun close() = wrapped.close()
    }
}
