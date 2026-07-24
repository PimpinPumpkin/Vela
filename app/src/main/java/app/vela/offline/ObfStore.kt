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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline `.obf` region files - the successor download to [RoutingGraphStore]'s GraphHopper graphs
 * (issue #214; see ObfRouteEngine). One raw `.obf` per region in `filesDir/obf/<id>.obf` plus the
 * same `index.json` bbox registry the graph store keeps, which [app.vela.core.data.ObfRouteEngine]
 * reads to pick a region per trip. No unzip: an obf's blocks are already deflate-compressed, so the
 * asset is served raw and the download IS the install (the manifest's sizeMb and installedMb are
 * the same number, a property the GraphHopper zips never had).
 *
 * The catalog manifest is the same row shape as the routing manifest, so [RoutingGraphStore.manifest]
 * parses it - this store only owns the bytes on disk.
 */
@Singleton
class ObfStore @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
) {
    private val root = File(context.filesDir, "obf")
    private val indexFile = File(root, "index.json")
    private val indexLock = Any()

    // Region files are hundreds of MB - the shared client's 12 s scrape cap would abort the body
    // mid-read, silently (the standing large-download rule).
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun installedIds(): Set<String> =
        readIndex().keys.filter { File(root, "$it.obf").let { f -> f.exists() && f.length() > 0 } }.toSet()

    /** Download [region]'s obf to `obf/<id>.obf` and register it. 0..100 progress. */
    suspend fun download(region: RoutingRegion, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        root.mkdirs()
        val dest = File(root, "${region.id}.obf")
        val tmp = File(root, "${region.id}.obf.tmp")
        runCatching {
            tmp.delete()
            downloadHttp.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body!!.contentLength()
                resp.body!!.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (100 * read / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            }
            // An obf leads with its version varint inside a protobuf frame; the cheap sanity check
            // is size + the reader opening it, which the engine does lazily. Guard the obvious:
            // a truncated/error body must not register as a region.
            check(tmp.length() > 1024) { "downloaded obf is implausibly small" }
            dest.delete()
            check(tmp.renameTo(dest)) { "could not install obf (rename failed)" }
            synchronized(indexLock) { writeIndex(readIndex() + (region.id to doubleArrayOf(region.s, region.w, region.n, region.e))) }
            onProgress(100)
            true
        }.getOrElse { tmp.delete(); false }
    }

    fun delete(id: String) {
        File(root, "$id.obf").delete()
        synchronized(indexLock) { writeIndex(readIndex() - id) }
    }

    private fun readIndex(): Map<String, DoubleArray> = runCatching {
        if (!indexFile.exists()) return emptyMap()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("bbox")
            o.getString("id") to doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }.getOrDefault(emptyMap())

    private fun writeIndex(entries: Map<String, DoubleArray>) {
        root.mkdirs()
        val arr = JSONArray()
        entries.forEach { (id, b) ->
            arr.put(JSONObject().put("id", id).put("bbox", JSONArray().put(b[0]).put(b[1]).put(b[2]).put(b[3])))
        }
        indexFile.writeText(arr.toString())
    }
}
