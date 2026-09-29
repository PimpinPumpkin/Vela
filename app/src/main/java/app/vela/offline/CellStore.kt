package app.vela.offline

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grid cells (SPEC 7.6, app side since 2026-09-28): half-degree pieces of a region, each a zip of
 * up to three parts, `<cell>.obf` (routing), `<cell>.db` (the place pack) and `places-<cell>.pmtiles`
 * (the places slice), so the area picker can pull directions and places for the framed area alone
 * instead of a whole state. The parts install into the SAME stores a region download fills
 * ([ObfStore], [PoiPackStore], [PlacesTileStore]) under the cell's id, so routing, offline search and
 * the places layer read them with no new code path; this store only knows which cells are on the
 * phone (`cells/index.json`) so they can be listed and deleted together. The cell manifest is
 * `cells-manifest.json` on the `grid-cells` release, one row per region with its cells.
 */
@Singleton
class CellStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val obfStore: ObfStore,
    private val poiPackStore: PoiPackStore,
    private val placesStore: PlacesTileStore,
) {
    data class Cell(
        val id: String, val regionId: String, val regionName: String, val url: String,
        val sizeMb: Double, val installedMb: Double, val rev: Int,
        val s: Double, val w: Double, val n: Double, val e: Double, val parts: List<String>,
    ) {
        fun intersects(bs: Double, bw: Double, bn: Double, be: Double) = s < bn && n > bs && w < be && e > bw
    }

    data class Installed(val id: String, val regionId: String, val regionName: String, val s: Double, val w: Double, val n: Double, val e: Double, val rev: Int, val mb: Double)

    private val root: File get() = File(StorageLocation.root(context), FOLDER)
    private val indexFile: File get() = File(root, "index.json")
    private val indexLock = Any()
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: List<Cell>? = null
    @Volatile private var cachedAtMs = 0L
    @Volatile private var lastMissMs = 0L

    /** Every cell the manifest lists, memoized like the archive catalogs (the picker asks on each idle). */
    suspend fun manifest(manifestUrl: String): List<Cell> {
        cached?.let { if (SystemClock.elapsedRealtime() - cachedAtMs < MANIFEST_TTL_MS) return it }
        if (SystemClock.elapsedRealtime() - lastMissMs < MISS_MEMO_MS) return emptyList()
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                    .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
                parseManifest(json)
            }.getOrDefault(emptyList())
        }
        if (fetched.isEmpty()) { lastMissMs = SystemClock.elapsedRealtime(); return emptyList() }
        cached = fetched; cachedAtMs = SystemClock.elapsedRealtime()
        return fetched
    }

    /** The cells whose box touches [s],[w],[n],[e], the framed area of the picker. */
    fun cellsFor(cells: List<Cell>, s: Double, w: Double, n: Double, e: Double): List<Cell> = cells.filter { it.intersects(s, w, n, e) }

    fun installed(): List<Installed> = synchronized(indexLock) { readIndex() }
    fun installedIds(): Set<String> = installed().map { it.id }.toSet()

    /**
     * Download one cell's zip and install each part into its store under the cell's id. 0..100
     * progress over the zip's bytes; [active] false mid-stream aborts. True when at least one
     * part installed; the cell is then listed even if another part failed, so it can be deleted.
     */
    suspend fun download(cell: Cell, active: () -> Boolean = { true }, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        root.mkdirs()
        val staging = File(root, "${cell.id}.tmp").apply { deleteRecursively(); mkdirs() }
        val box = doubleArrayOf(cell.s, cell.w, cell.n, cell.e)
        var partsInstalled = 0
        val ok = runCatching {
            downloadHttp.newCall(Request.Builder().url(cell.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body!!.contentLength()
                var lastPct = -1
                val counting = CountingInputStream(resp.body!!.byteStream()) { read ->
                    if (!active()) error("canceled")
                    if (total > 0) (100 * read / total).toInt().let { p -> if (p != lastPct) { lastPct = p; onProgress(p) } }
                }
                ZipInputStream(counting).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val name = e.name.substringAfterLast('/')
                            val tmp = File(staging, name)
                            tmp.outputStream().use { zis.copyTo(it) }
                            val installed = when {
                                name == "${cell.id}.obf" -> obfStore.installFile(cell.id, tmp, box, cell.rev)
                                name == "${cell.id}.db" -> poiPackStore.installFile(cell.id, tmp, cell.rev)
                                name == "places-${cell.id}.pmtiles" -> placesStore.installFile(cell.id, tmp, box, cell.rev)
                                else -> false
                            }
                            if (installed) partsInstalled++ else tmp.delete()
                        }
                        e = zis.nextEntry
                    }
                }
            }
            true
        }.getOrDefault(false)
        staging.deleteRecursively()
        if (partsInstalled > 0) {
            synchronized(indexLock) {
                writeIndex(readIndex().filter { it.id != cell.id } + Installed(cell.id, cell.regionId, cell.regionName, cell.s, cell.w, cell.n, cell.e, cell.rev, cell.installedMb))
            }
            onProgress(100)
        }
        ok && partsInstalled > 0
    }

    /** Remove one cell's parts from every store and forget it. */
    fun delete(id: String) {
        obfStore.delete(id)
        poiPackStore.delete(id)
        placesStore.delete(id)
        synchronized(indexLock) { writeIndex(readIndex().filter { it.id != id }) }
    }

    /** Remove every installed cell of [regionId]. */
    fun deleteRegion(regionId: String) = installed().filter { it.regionId == regionId }.forEach { delete(it.id) }

    /** Forget every cell: the delete-all sweep has already removed the parts. */
    fun clearIndex() = synchronized(indexLock) { indexFile.delete() }

    private fun readIndex(): List<Installed> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val b = o.getJSONArray("bbox")
            Installed(o.getString("id"), o.optString("region", o.getString("id").substringBefore('.')), o.optString("name", ""),
                b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3), o.optInt("rev"), o.optDouble("mb", 0.0))
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(cells: List<Installed>) {
        root.mkdirs()
        val arr = JSONArray()
        cells.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("region", c.regionId).put("name", c.regionName)
                .put("bbox", JSONArray().put(c.s).put(c.w).put(c.n).put(c.e)).put("rev", c.rev).put("mb", c.mb))
        }
        app.vela.core.util.AtomicFiles.writeText(indexFile, arr.toString())
    }

    companion object {
        const val FOLDER = "cells"
        private const val MANIFEST_TTL_MS = 10 * 60_000L
        private const val MISS_MEMO_MS = 10 * 60_000L

        /** `{regions:[{id,name,rev,cells:[{id,bbox,url,sizeMb,installedMb,rev,parts?}]}]}` flattened to cells. */
        internal fun parseManifest(json: String): List<Cell> {
            val regions = JSONObject(json).optJSONArray("regions") ?: return emptyList()
            val out = ArrayList<Cell>()
            for (i in 0 until regions.length()) {
                val r = regions.getJSONObject(i)
                val cells = r.optJSONArray("cells") ?: continue
                for (k in 0 until cells.length()) {
                    val c = cells.getJSONObject(k)
                    val b = c.getJSONArray("bbox")
                    val parts = c.optJSONArray("parts")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: listOf("obf", "pack", "places")
                    out += Cell(
                        c.getString("id"), r.getString("id"), r.optString("name", r.getString("id")), c.getString("url"),
                        c.optDouble("sizeMb", 0.0), c.optDouble("installedMb", c.optDouble("sizeMb", 0.0)), c.optInt("rev", r.optInt("rev")),
                        b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3), parts,
                    )
                }
            }
            return out
        }
    }
}
