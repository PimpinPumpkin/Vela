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
    fun installedFor(center: LatLng?, mounted: File? = null, view: List<LatLng> = emptyList(), keepMounted: Boolean = false): File? {
        val c = center ?: return null
        val index = readIndexPublic()
        // OFFLINE THE MOUNTED ARCHIVE STAYS WHILE ANY OF THE VIEW IS IN IT (issue #552, fourth
        // round, 2026-09-22). The eager unmount below is right online, where the streamed tiles
        // take over the moment the center leaves the data; offline nothing takes over, so on a
        // recorded pan from Scranton across the New York line the whole screen went blank for
        // twelve seconds, the Pennsylvania half included, until the pan back remounted the file.
        // With [keepMounted] the archive in use is kept as long as its roads still reach the
        // center, the ring around it or any corner of the viewport; only a view entirely outside
        // its data lets go of it.
        if (keepMounted && mounted != null && mounted.name != "$WORLD_ID.pmtiles" && mounted.exists()) {
            val (mx, my) = PmtilesReader.tileOf(c.lat, c.lng, COVERAGE_PROBE_Z)
            if (viewTouches(mounted, mx, my, view)) return mounted
        }
        // The WORLD archive is never a normal candidate: it covers every point on earth, so the
        // area sort would rank it last anyway, and the roads probe below would reject it outright
        // (it carries no transportation layer at any zoom). It is the explicit last resort instead.
        val covering = installed().entries
            .filter { (id, _) -> id != WORLD_ID }
            .filter { (id, _) -> index[id]?.let { b -> c.lat in b[0]..b[2] && c.lng in b[1]..b[3] } ?: true }
            .sortedBy { (id, _) -> index[id]?.let { b -> (b[2] - b[0]) * (b[3] - b[1]) } ?: Double.MAX_VALUE }
        if (covering.isEmpty()) return worldArchive()
        val (tx, ty) = PmtilesReader.tileOf(c.lat, c.lng, COVERAGE_PROBE_Z)
        // "Cannot tell" and "definitely no roads here" are NOT the same answer, and collapsing them
        // was the second half of issue #552: past a region's real data but still inside its
        // bounding box, every probe said a definite no and the pick mounted the archive regardless,
        // painting an empty map over streamed tiles that were about to arrive. Crossing the box
        // edge then unmounted it, so panning along a download's border alternated gray and network
        // (HirschBerge). A definite no from everything that could cover the point means NOTHING
        // here is worth mounting; only an archive we could not READ leaves the old rule in charge,
        // because that is the case where asking told us nothing.
        // HYSTERESIS AT THE EDGE (issue #552, third round). A swap reloads the whole style, so
        // it must not happen on every camera idle while the view wanders along a download's
        // border. Unmounting stays eager (the center tile has no roads: stream, never draw gray
        // over tiles the network can supply), but MOUNTING a candidate that is not already the one
        // in use needs the whole VISIBLE VIEW to hold roads: the corners of the viewport ([view])
        // and the ring of z12 tiles around the center. The reporter's video was at a 200 km wide
        // zoom, where a fixed ring is a rounding error; keying on the corners means an archive is
        // mounted only once the border has left the screen, and once the border shows the view
        // keeps streaming instead of flipping the style every couple of seconds.
        // ONLINE THE ARCHIVE IS MOUNTED ONLY WHILE THE WHOLE VIEW IS INSIDE IT (issue #552, fourth
        // round, the online half). The mounted archive used to be kept until the CENTER tile left
        // its data, so with a border on screen the far side drew nothing while the center was
        // still inside, and a pan along the border, with the center wobbling across it, reloaded
        // the style at every crossing (the reporter's video: gray, then network, then gray). Now
        // the archive is in use exactly when the ring and the corners are all inside it: the
        // moment a border comes on screen the view streams, and it keeps streaming, one reload
        // each way, none while the border stays in view. Offline is the [keepMounted] rule above.
        var unreadable = false
        for ((_, f) in covering) {
            when (coverage(f, tx, ty)) {
                true -> if (ringCovered(f, tx, ty) && cornersCovered(f, view)) return f
                null -> unreadable = true
                false -> Unit
            }
        }
        // Nothing here holds the map. Online that means stream it; the caller's shallow rule turns
        // the world archive down while there is a connection, so this only ever draws when there
        // is nothing else at all.
        return if (unreadable) covering.first().value else worldArchive()
    }

    /** The whole planet at low zoom (`WORLD_ID`), the floor under everything else: coastlines,
     *  water, boundaries and place labels, so losing the network away from a downloaded region is
     *  a coarse map rather than an empty screen. Baked by `world-lowzoom.yml`; it holds no roads,
     *  which is why it bypasses the coverage probe, and its shallow max zoom is what keeps it
     *  offline-only without a rule of its own. */
    fun worldArchive(): File? = installed()[WORLD_ID]

    /** Pull the world archive once, if it is not already here. Best effort and quiet: it is a
     *  floor under the map, so failing to get it leaves everything exactly as it was. */
    suspend fun ensureWorld(url: String, onProgress: (Int) -> Unit = {}): Boolean {
        if (worldArchive() != null) return true
        // The bbox is the whole planet on purpose; nothing sorts against it because the pick
        // filters it out of the candidate list by id.
        return download(
            Region(WORLD_ID, "World", url, 0.0, -85.0, -180.0, 85.0, 180.0),
            onProgress,
        )
    }

    private fun probeKey(f: File, x: Int, y: Int) = "${f.name}|$x|$y"

    /** True with roads, false definitely without, null when the file could not answer. */
    /** Every z12 tile around (x, y) holds roads in [f], "cannot tell" counted as yes so an
     *  unreadable neighbor never blocks a mount the center tile earned. */
    private fun ringCovered(f: File, x: Int, y: Int): Boolean {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (coverage(f, x + dx, y + dy) == false) return false
        }
        return true
    }

    /** Roads of [f] reach the center tile, one of the eight around it, or a viewport corner. */
    private fun viewTouches(f: File, x: Int, y: Int, view: List<LatLng>): Boolean {
        if (coverage(f, x, y) == true) return true
        for (dx in -1..1) for (dy in -1..1) if (coverage(f, x + dx, y + dy) == true) return true
        return view.any { p ->
            val (cx, cy) = PmtilesReader.tileOf(p.lat, p.lng, COVERAGE_PROBE_Z)
            coverage(f, cx, cy) == true
        }
    }

    /** Every viewport corner's z12 tile holds roads in [f]; "cannot tell" counts as yes. */
    private fun cornersCovered(f: File, view: List<LatLng>): Boolean = view.none { p ->
        val (x, y) = PmtilesReader.tileOf(p.lat, p.lng, COVERAGE_PROBE_Z)
        coverage(f, x, y) == false
    }

    private fun coverage(f: File, x: Int, y: Int): Boolean? {
        coverageCache.get(probeKey(f, x, y))?.let { return it }
        val answer = PmtilesReader.hasRoads(f, COVERAGE_PROBE_Z, x, y)
        // Only a definite answer is remembered: "cannot tell" must not harden into "no".
        if (answer != null) coverageCache.put(probeKey(f, x, y), answer)
        return answer
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

        /** The id of the global low-zoom archive, kept out of the per-region candidate list. */
        const val WORLD_ID = "world"
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
        /** The region's real boundary where [RegionPolys] has one (the places and basemap catalogs
         *  share the routing catalog's ids), else the box (issue #599). */
        fun covers(lat: Double, lng: Double): Boolean =
            RegionPolys.covers(id, lat, lng) ?: (lat in s..n && lng in w..e)
        fun covers(p: LatLng) = covers(p.lat, p.lng)
        fun area() = (n - s) * (e - w)
        fun boxArea() = area()
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
                    writeDead(region.id, 0)
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
        // A patch appends and leaves the tiles it replaced behind, so without something to reclaim
        // them a file updated this way forever would drift from what a fresh download holds: the
        // same tiles, more bytes. `PmtilesCompact` reclaims them locally after the patch lands, so
        // this only has to catch the case where that has been failing - no free space, say - and
        // fall back to the download of last resort rather than let the file grow without end.
        val dead = deadBytes(region.id)
        if (dead > file.length() / 2) {
            log("${region.id}: ${dead / 1024} KB dead in a ${file.length() / 1024} KB archive and compaction is not keeping up, taking it whole")
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
                        log("${region.id}: patched ${delta.fromRev} -> ${region.rev}, " +
                            "${tmp.length() / 1024} KB down, ${outcome.tiles} tiles, " +
                            "${outcome.deadBytes / 1024} KB dead")
                        var carried = dead + outcome.deadBytes
                        // Past a fifth of the file, rewrite it without the dead bytes. This is
                        // local: every live tile is already here, so it costs a pass over the file
                        // and nothing on the network, and the result is the layout the bake
                        // publishes. A refusal is fine - the archive is correct either way.
                        if (carried > file.length() / DEAD_LIMIT_DIVISOR || compactAlways()) {
                            when (val c = PmtilesCompact.compact(file)) {
                                is PmtilesCompact.Outcome.Done -> {
                                    carried = 0
                                    log("${region.id}: compacted ${c.beforeBytes / 1024} KB to ${c.afterBytes / 1024} KB")
                                }
                                is PmtilesCompact.Outcome.Refused ->
                                    log("${region.id}: not compacted, ${c.why}")
                            }
                        }
                        synchronized(indexLock) {
                            writeRev(region.id, region.rev)
                            writeDead(region.id, carried)
                        }
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
        synchronized(indexLock) { writeIndex(readIndex() - id); writeRev(id, 0); writeDead(id, 0) }
    }

    /** Bytes in the installed archive that patches have superseded: the only way a patched file
     *  differs from a freshly downloaded one. */
    fun deadBytes(id: String): Long = synchronized(indexLock) { readDead().optLong(id, 0L) }

    /** The manifest rev the installed archive came from (0 for archives older than revs). */
    fun installedRev(id: String): Int = synchronized(indexLock) { readRevs().optInt(id, 0) }

    /** Installed archives whose manifest rev is newer than the installed one. */
    fun updatable(manifest: List<Region>): List<Region> {
        val ids = installedIds()
        return manifest.filter { it.id in ids && it.rev > installedRev(it.id) }
    }

    private fun readRevs(): JSONObject =
        runCatching { JSONObject(File(root, "revs.json").readText()) }.getOrDefault(JSONObject())

    private fun readDead(): JSONObject =
        runCatching { JSONObject(File(root, "dead.json").readText()) }.getOrDefault(JSONObject())

    private fun writeDead(id: String, bytes: Long) {
        root.mkdirs()
        File(root, "dead.json").writeText(readDead().put(id, bytes).toString())
    }

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
        /** Dead space a patched archive may carry before it is rewritten without it. */
        const val DEAD_LIMIT_DIVISOR = 5

        /** `adb shell setprop debug.vela.compact true` rewrites after EVERY patch, so the rewrite
         *  can be watched on a device without waiting for a tenth update. The same escape hatch as
         *  debug.vela.fps: it touches nothing the user can see. */
        private fun compactAlways(): Boolean = runCatching {
            @Suppress("PrivateApi")
            val m = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            (m.invoke(null, "debug.vela.compact") as? String).orEmpty()
        }.getOrDefault("") == "true"
        /** How long a fetched catalog is reused. Bakes are daily at most, so an hour is plenty and
         *  still cheap: this runs on camera idle, not per frame. */
        const val MANIFEST_TTL_MS = 60 * 60 * 1000L
    }
}
