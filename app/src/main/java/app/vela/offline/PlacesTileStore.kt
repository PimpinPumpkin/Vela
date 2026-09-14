package app.vela.offline

import android.content.Context
import android.os.SystemClock
import app.vela.core.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The open-data places layer (Overture Places baked to PMTiles by `tools/build-places-region.sh`,
 * one archive per region, hosted on the `places-overlays` release with a manifest). Two ways it
 * reaches the map: an archive downloaded into `files/places/` (offline, indexed by bbox like the
 * building overlays), else the manifest's regions covering the view, streamed by HTTP range
 * requests. Business POIs with a prominence baked in, so the map draws them the way it draws
 * Google's ambient dots and Google is asked only when one is tapped.
 */
@Singleton
class PlacesTileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    data class Region(val id: String, val name: String, val url: String, val sizeMb: Double, val s: Double, val w: Double, val n: Double, val e: Double) {
        fun covers(p: LatLng) = p.lat in s..n && p.lng in w..e
        fun area() = (n - s) * (e - w)
    }

    private val root = File(context.filesDir, "places")
    private val indexFile = File(root, "index.json")
    private val indexLock = Any()
    private val downloadMutex = Mutex()

    // Large archives must not die on the shared client's short call timeout (the overlay stores' rule).
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: List<Region>? = null
    @Volatile private var lastMissMs = 0L

    private fun fileFor(id: String) = File(root, "$id.pmtiles")

    /** Installed archives: id -> file. Anything in the folder counts, indexed or not (a dropped-in
     *  test archive still renders); the index only adds the bbox. */
    fun installed(): Map<String, File> =
        root.listFiles { f -> f.extension == "pmtiles" }.orEmpty().associateBy { it.nameWithoutExtension }

    fun installedIds(): Set<String> = installed().keys

    suspend fun manifest(manifestUrl: String): List<Region> {
        cached?.let { return it }
        // A missing or unreachable manifest is remembered for a while: this runs on every camera
        // idle, and without the memo each pan retried the fetch.
        if (SystemClock.elapsedRealtime() - lastMissMs < MISS_MEMO_MS) return emptyList()
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                    .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
                val arr = JSONObject(json).getJSONArray("regions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONArray("bbox") // [S, W, N, E]
                    Region(
                        o.getString("id"), o.optString("name", o.getString("id")), o.getString("url"), o.optDouble("sizeMb", 0.0),
                        b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3),
                    )
                }
            }.getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) cached = fetched else lastMissMs = SystemClock.elapsedRealtime()
        return fetched
    }

    /** Source URIs for [center]: local archives first, then the smallest manifest regions covering
     *  it that are not installed. */
    suspend fun sourcesFor(center: LatLng?, manifestUrl: String): List<String> {
        val local = installed()
        val uris = local.values.map { "pmtiles://file://${it.absolutePath}" }.toMutableList()
        val c = center ?: return uris
        runCatching { manifest(manifestUrl) }.getOrDefault(emptyList())
            .filter { it.covers(c) && it.id !in local.keys }
            .sortedBy { it.area() }
            .take(2)
            .forEach { uris.add("pmtiles://${it.url}") }
        return uris.distinct()
    }

    /** Download [region]'s archive for offline use. True when installed (or already was). */
    suspend fun download(region: Region, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (fileFor(region.id).exists()) { onProgress(100); return@withLock true }
            root.mkdirs()
            val file = fileFor(region.id)
            val tmp = File(root, "${region.id}.pmtiles.tmp")
            runCatching {
                downloadHttp.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val total = resp.body!!.contentLength()
                    var read = 0L
                    var lastPct = -1
                    resp.body!!.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) (100 * read / total).toInt().let { p -> if (p != lastPct) { lastPct = p; onProgress(p) } }
                            }
                        }
                    }
                }
                check(tmp.length() > 127 && tmp.inputStream().use { s -> ByteArray(7).let { s.read(it); String(it) } } == "PMTiles") { "not a PMTiles archive" }
                check(tmp.renameTo(file)) { "rename failed" }
                synchronized(indexLock) { writeIndex(readIndex() + (region.id to doubleArrayOf(region.s, region.w, region.n, region.e))) }
                onProgress(100)
                true
            }.getOrElse { tmp.delete(); false }
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
        synchronized(indexLock) { writeIndex(readIndex() - id) }
    }

    /** Installed archives whose bbox centre falls inside [s],[w],[n],[e]: the ones that belong to
     *  a region being removed. */
    fun idsInside(s: Double, w: Double, n: Double, e: Double): List<String> =
        readIndex().filter { (_, b) -> (b[0] + b[2]) / 2 in s..n && (b[1] + b[3]) / 2 in w..e }.keys.toList()

    private fun readIndex(): Map<String, DoubleArray> = runCatching {
        if (!indexFile.exists()) return emptyMap()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i); val b = o.getJSONArray("bbox")
            o.getString("id") to doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }.getOrDefault(emptyMap())

    private fun writeIndex(index: Map<String, DoubleArray>) {
        root.mkdirs()
        val arr = JSONArray()
        index.forEach { (id, b) -> arr.put(JSONObject().put("id", id).put("bbox", JSONArray(b.toList()))) }
        indexFile.writeText(arr.toString())
    }

    private companion object {
        const val MISS_MEMO_MS = 10 * 60 * 1000L
    }
}
