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
    @ApplicationContext context: Context,
    http: OkHttpClient,
) : PmtilesRegionStore(context, http, "places")

/**
 * The offline basemap: the same OpenMapTiles-schema vector tiles OpenFreeMap serves online, baked
 * per region by planetiler from the Geofabrik extract (`.github/workflows/basemap-tiles.yml`) and
 * hosted on the `basemap-tiles` release. Never streamed (online, OpenFreeMap is the same data,
 * fresher); an installed archive covering the view replaces the style's tile source, so a region
 * download shows the map itself with no signal, not just routes and places.
 */
@Singleton
class BasemapTileStore @Inject constructor(
    @ApplicationContext context: Context,
    http: OkHttpClient,
) : PmtilesRegionStore(context, http, "basemap") {
    /** The archive to draw [center] from: the smallest installed one whose box covers the point AND
     *  that actually holds a tile there, else the smallest covering one, else null.
     *
     *  The coverage test is what keeps the map from going blank (issue #552). A bounding box is a
     *  rectangle and a region is not, so a neighbor's box routinely covers a point its tiles do not
     *  reach - a small state next door can even have the SMALLER box and win the old pick outright.
     *  The result was no vector basemap at all over that strip, with the traffic raster and the
     *  place pins still drawing on top of bare land. [PmtilesReader.hasRoads] asks the file instead:
     *  not "is there a tile here" (a bake emits tiles across its whole box from global base data,
     *  so that answers yes over the neighbor and out to sea) but "does the tile here carry the road
     *  network", which only the OSM-derived part of the bake does.
     *  A probe that cannot answer (an unreadable file, a format this reader does not know, a zoom
     *  outside the archive) leaves the old rule in charge, so this can only ever improve the pick. */
    fun installedFor(center: LatLng?): File? {
        val c = center ?: return null
        val index = readIndexPublic()
        val covering = installed().entries
            .filter { (id, _) -> index[id]?.let { b -> c.lat in b[0]..b[2] && c.lng in b[1]..b[3] } ?: true }
            .sortedBy { (id, _) -> index[id]?.let { b -> (b[2] - b[0]) * (b[3] - b[1]) } ?: Double.MAX_VALUE }
        if (covering.isEmpty()) return null
        val (tx, ty) = PmtilesReader.tileOf(c.lat, c.lng, COVERAGE_PROBE_Z)
        covering.firstOrNull { (_, f) -> coverageCache.get(probeKey(f, tx, ty)) ?: probe(f, tx, ty) }
            ?.let { return it.value }
        return covering.first().value
    }

    private fun probeKey(f: File, x: Int, y: Int) = "${f.name}|$x|$y"

    private fun probe(f: File, x: Int, y: Int): Boolean {
        val answer = PmtilesReader.hasRoads(f, COVERAGE_PROBE_Z, x, y)
        // Only a definite answer is remembered: "cannot tell" must not harden into "no".
        if (answer != null) coverageCache.put(probeKey(f, x, y), answer)
        return answer == true
    }

    /** Probes are memoized per archive and tile: this runs on every camera idle, and the answer for
     *  a tile cannot change while the file is installed. */
    private val coverageCache = android.util.LruCache<String, Boolean>(256)

    /** The archive's own max zoom, read from the PMTiles v3 header (byte 101). A region baked
     *  shallower than [FULL_MAP_ZOOM] - the workflow drops a level when a bake would pass GitHub's
     *  2 GiB asset limit - draws as a blurred, detail-less version of the same map at street zoom,
     *  which is worse than the tiles we can stream (issue #552). Null when it cannot be read. */
    fun maxZoomOf(file: File): Int? = runCatching {
        file.inputStream().use { s ->
            val head = ByteArray(102)
            if (s.read(head) < 102) return@runCatching null
            if (String(head, 0, 7) != "PMTiles") return@runCatching null
            head[101].toInt() and 0xFF
        }
    }.getOrNull()

    companion object {
        /** What the online tiles carry; an archive at least this deep is as good as streaming. */
        const val FULL_MAP_ZOOM = 14

        /** The zoom the coverage probe asks about. A tile here is about ten kilometers across:
         *  fine enough to tell a neighboring state's archive from the right one, coarse enough
         *  that a lake or a stretch of farmland inside the right region still has a tile. */
        const val COVERAGE_PROBE_Z = 12
    }
}

/** One folder of per-region PMTiles archives under `files/<folder>/` with an `index.json` of bboxes,
 *  a hosted manifest of regions, downloads, deletes and the covering-archive lookups. */
abstract class PmtilesRegionStore(
    private val context: Context,
    private val http: OkHttpClient,
    folder: String,
) {
    /** A delta the bake published against an earlier revision: applicable only to an archive
     *  installed at exactly [fromRev]. Absent until the bake publishes one. */
    data class Delta(val fromRev: Int, val url: String, val sizeMb: Double)

    data class Region(val id: String, val name: String, val url: String, val sizeMb: Double, val s: Double, val w: Double, val n: Double, val e: Double, val rev: Int = 0, val delta: Delta? = null) {
        fun covers(p: LatLng) = p.lat in s..n && p.lng in w..e
        fun area() = (n - s) * (e - w)
    }

    private val root = File(context.filesDir, folder)
    private val indexFile = File(root, "index.json")
    private val indexLock = Any()
    private val downloadMutex = Mutex()

    // Large archives must not die on the shared client's short call timeout (the overlay stores' rule).
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: List<Region>? = null
    @Volatile private var cachedAtMs = 0L
    @Volatile private var lastMissMs = 0L

    private fun fileFor(id: String) = File(root, "$id.pmtiles")

    /** Installed archives: id -> file. Anything in the folder counts, indexed or not (a dropped-in
     *  test archive still renders); the index only adds the bbox. */
    fun installed(): Map<String, File> =
        root.listFiles { f -> f.extension == "pmtiles" }.orEmpty().associateBy { it.nameWithoutExtension }

    fun installedIds(): Set<String> = installed().keys

    suspend fun manifest(manifestUrl: String): List<Region> {
        // The memo EXPIRES. A bake publishes a new revision while the app is running, and a process
        // that lives for days would otherwise never see it: no Update offered, no delta taken.
        cached?.let { if (SystemClock.elapsedRealtime() - cachedAtMs < MANIFEST_TTL_MS) return it }
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
                    val d = o.optJSONObject("delta")
                    Region(
                        o.getString("id"), o.optString("name", o.getString("id")), o.getString("url"), o.optDouble("sizeMb", 0.0),
                        b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3), o.optInt("rev"),
                        d?.let { Delta(it.optInt("fromRev"), it.getString("url"), it.optDouble("sizeMb", 0.0)) },
                    )
                }
            }.getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) {
            cached = fetched
            cachedAtMs = SystemClock.elapsedRealtime()
        } else lastMissMs = SystemClock.elapsedRealtime()
        return fetched
    }

    /** The ONE source URI for [center]: the smallest installed archive covering it, else the smallest
     *  manifest region covering it. One, not every match: regions nest (a city test box inside its
     *  state), and two archives on the style drew every business in the overlap twice. An installed
     *  archive with no index entry (a dropped-in test file) counts as covering everything. */
    suspend fun sourcesFor(center: LatLng?, manifestUrl: String): List<String> {
        val local = installed()
        val c = center ?: return local.values.take(1).map { "pmtiles://file://${it.absolutePath}" }
        val index = readIndex()
        val localPick = local.entries
            .filter { (id, _) -> index[id]?.let { b -> c.lat in b[0]..b[2] && c.lng in b[1]..b[3] } ?: true }
            .minByOrNull { (id, _) -> index[id]?.let { b -> (b[2] - b[0]) * (b[3] - b[1]) } ?: Double.MAX_VALUE }
        if (localPick != null) return listOf("pmtiles://file://${localPick.value.absolutePath}")
        val streamed = runCatching { manifest(manifestUrl) }.getOrDefault(emptyList())
            .filter { it.covers(c) }
            .minByOrNull { it.area() } ?: return emptyList()
        return listOf("pmtiles://${streamed.url}")
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
                synchronized(indexLock) {
                    writeIndex(readIndex() + (region.id to doubleArrayOf(region.s, region.w, region.n, region.e)))
                    writeRev(region.id, region.rev)
                }
                onProgress(100)
                true
            }.getOrElse { tmp.delete(); false }
        }
    }

    /**
     * Update an installed archive with the manifest's delta instead of downloading it whole.
     *
     * Only when the patch is FOR the installed revision, and only when the archive is actually
     * installed. Everything else (no delta published, a revision gap, a patch that does not apply,
     * a fingerprint that does not match) answers false and the caller downloads the region, which
     * is what it would have done anyway. The patch is applied in place, so this needs the patch's
     * own size in free space rather than a second copy of the region.
     *
     * [log] gets one line per attempt, for the diagnostics ring: a region that quietly falls back
     * to a full download every week is the failure mode worth being able to see.
     */
    suspend fun updateWithDelta(
        region: Region,
        onProgress: (Int) -> Unit,
        log: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val delta = region.delta ?: run { log("${region.id}: no delta published for rev ${region.rev}"); return@withContext false }
        val file = fileFor(region.id)
        if (!file.exists()) { log("${region.id}: not installed"); return@withContext false }
        val have = installedRev(region.id)
        if (have != delta.fromRev) {
            log("${region.id}: installed rev $have, patch is from ${delta.fromRev}")
            return@withContext false
        }
        downloadMutex.withLock {
            val tmp = File(root, "${region.id}.vpatch.tmp")
            val ok = runCatching {
                downloadHttp.newCall(Request.Builder().url(delta.url).build()).execute().use { resp ->
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
                when (val outcome = PmtilesPatch.apply(file, tmp)) {
                    is PmtilesPatch.Outcome.Applied -> {
                        synchronized(indexLock) { writeRev(region.id, region.rev) }
                        log("${region.id}: patched ${delta.fromRev} -> ${region.rev}, " +
                            "${tmp.length() / 1024} KB down, ${outcome.tiles} tiles, " +
                            "${outcome.deadBytes / 1024} KB dead")
                        true
                    }
                    is PmtilesPatch.Outcome.Refused -> {
                        log("${region.id}: patch refused, ${outcome.why}")
                        false
                    }
                }
            }.getOrElse { log("${region.id}: patch download failed, ${it.javaClass.simpleName}"); false }
            tmp.delete()
            onProgress(100)
            ok
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
        synchronized(indexLock) { writeIndex(readIndex() - id); writeRev(id, 0) }
    }

    /** The manifest rev the installed archive came from (0 for archives older than revs). */
    fun installedRev(id: String): Int = synchronized(indexLock) { readRevs().optInt(id, 0) }

    /** Installed archives whose manifest rev is newer than the installed one. */
    fun updatable(manifest: List<Region>): List<Region> {
        val ids = installedIds()
        return manifest.filter { it.id in ids && it.rev > installedRev(it.id) }
    }

    private fun readRevs(): JSONObject =
        runCatching { JSONObject(File(root, "revs.json").readText()) }.getOrDefault(JSONObject())

    private fun writeRev(id: String, rev: Int) {
        root.mkdirs()
        File(root, "revs.json").writeText(readRevs().put(id, rev).toString())
    }

    /** Installed archives whose bbox center falls inside [s],[w],[n],[e]: the ones that belong to
     *  a region being removed. */
    fun idsInside(s: Double, w: Double, n: Double, e: Double): List<String> =
        readIndex().filter { (_, b) -> (b[0] + b[2]) / 2 in s..n && (b[1] + b[3]) / 2 in w..e }.keys.toList()

    protected fun readIndexPublic(): Map<String, DoubleArray> = readIndex()

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
        /** How long a fetched catalog is reused. Bakes are daily at most, so an hour is plenty and
         *  still cheap: this runs on camera idle, not per frame. */
        const val MANIFEST_TTL_MS = 60 * 60 * 1000L
    }
}
