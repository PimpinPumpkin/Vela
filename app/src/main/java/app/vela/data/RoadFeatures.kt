package app.vela.data

import android.content.Context
import app.vela.core.data.SpeedCamera
import app.vela.core.data.TrafficControl
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Road features queried ON-DEVICE (issue #304, 2026-09-13): traffic lights, stop signs, level
 * crossings, speed humps and fixed speed cameras, one small gzipped-TSV file per catalog region
 * (`lat<TAB>lon<TAB>kind`, kind = S/T/R/H/C), baked on CI by `scripts/build-road-features.sh`
 * from the same Geofabrik extracts the place packs use and hosted on the `road-features` release.
 *
 * Until this existed every one of these was a live Overpass query from every phone: a padded box
 * per area at street zoom, a corridor per driven route, the same again for the opt-in camera
 * layer. The Nominatim maintainer asked, rightly, that a public OSM server not be an app's
 * backend. Now the app pulls the file for the region it is in ONCE (a US state is a few hundred
 * KB), keeps up to [MAX_LOADED] regions in memory with a 0.1 degree grid index, and answers the
 * box and corridor questions locally. `OverpassTrafficSignals` / `OverpassSpeedCameras` remain
 * only for an area the manifest has no region for.
 *
 * Contract for callers: [ensureBox] / [ensureAlong] return true when every covering region is
 * loaded (downloading first if needed, which suspends), false when the manifest has NO region for
 * the spot (the caller may fall back to Overpass) or the download failed (caller shows nothing and
 * retries on the next viewport; a failure is never cached). The query fns then read memory only.
 */
object RoadFeatures {
    private const val CELL = 0.1
    private const val MAX_LOADED = 4
    private const val MANIFEST_TTL_MS = 6L * 60 * 60 * 1000

    class Region(val id: String, val name: String, val url: String, val s: Double, val w: Double, val n: Double, val e: Double, val updatedAt: String)

    private class Loaded(val lat: DoubleArray, val lng: DoubleArray, val kind: ByteArray) {
        val grid = HashMap<Long, IntArray>()
        init {
            // Two passes over primitive arrays (count, then fill) instead of a map of boxed lists.
            val keys = LongArray(lat.size) { key(rowOf(lat[it]), rowOf(lng[it])) }
            val counts = HashMap<Long, Int>()
            for (k in keys) counts[k] = (counts[k] ?: 0) + 1
            for ((k, c) in counts) grid[k] = IntArray(c)
            val fill = HashMap<Long, Int>()
            for (i in keys.indices) {
                val k = keys[i]; val at = fill[k] ?: 0
                grid[k]!![at] = i; fill[k] = at + 1
            }
        }
    }

    private val mutex = Mutex()
    @Volatile private var manifest: List<Region> = emptyList()
    @Volatile private var manifestAt = 0L
    @Volatile private var manifestFailedAt = 0L
    // Insertion-ordered so the oldest region is evicted first.
    private val loaded = LinkedHashMap<String, Loaded>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun key(row: Long, col: Long): Long = (row shl 32) xor (col and 0xffffffffL)
    private fun rowOf(v: Double): Long = Math.floor(v / CELL).toLong()
    private fun dir(context: Context) = File(context.filesDir, "roadfeatures").apply { mkdirs() }
    private fun fileOf(context: Context, id: String) = File(dir(context), "$id.bin")
    private fun stampOf(context: Context, id: String) = File(dir(context), "$id.updated")

    /** The manifest, fetched at most every [MANIFEST_TTL_MS]; a failure keeps the last copy and
     *  is retried after a minute, and a cached file on disk is usable without any manifest. */
    private suspend fun regions(context: Context, manifestUrl: String): List<Region> {
        val now = System.currentTimeMillis()
        if (manifest.isNotEmpty() && now - manifestAt < MANIFEST_TTL_MS) return manifest
        if (now - manifestFailedAt < 60_000L) return manifest
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val body = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                    .use { if (!it.isSuccessful) error("HTTP ${it.code}"); it.body?.string().orEmpty() }
                val arr = org.json.JSONObject(body).getJSONArray("regions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i); val b = o.getJSONArray("bbox")
                    Region(o.getString("id"), o.getString("name"), o.getString("url"), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3), o.optString("updatedAt", ""))
                }
            }.getOrNull()
        }
        if (fetched != null) { manifest = fetched; manifestAt = now } else manifestFailedAt = now
        return manifest
    }

    /** The smallest catalog region covering a point, or null when the manifest has none. */
    private fun regionFor(regions: List<Region>, lat: Double, lng: Double): Region? =
        regions.filter { lat in it.s..it.n && lng in it.w..it.e }.minByOrNull { (it.n - it.s) * (it.e - it.w) }

    /** Load [region] into memory, downloading first when the file is missing or the manifest says
     *  it was rebuilt since. True when loaded. */
    private suspend fun ensureRegion(context: Context, region: Region): Boolean = mutex.withLock {
        val f = fileOf(context, region.id)
        val stale = region.updatedAt.isNotBlank() && stampOf(context, region.id).takeIf { it.exists() }?.readText()?.trim() != region.updatedAt
        if (loaded.containsKey(region.id) && !stale) {
            // Touch for LRU order.
            val l = loaded.remove(region.id)!!; loaded[region.id] = l
            return@withLock true
        }
        if (!f.exists() || stale) {
            android.util.Log.i("VelaControls", "road-features ${region.id}: ${if (stale) "stale (stamp != ${region.updatedAt})" else "missing"}, downloading")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(dir(context), "${region.id}.bin.tmp")
                    http.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching false
                        val bytes = resp.body?.bytes() ?: return@runCatching false
                        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return@runCatching false
                        tmp.writeBytes(bytes)
                    }
                    if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                    stampOf(context, region.id).writeText(region.updatedAt)
                    true
                }.getOrDefault(false)
            }
            if (!ok && !f.exists()) return@withLock false
        }
        val tp = System.nanoTime()
        val parsed = withContext(Dispatchers.IO) { runCatching { f.inputStream().use(::parse) }.getOrNull() } ?: return@withLock false
        android.util.Log.i("VelaControls", "road-features ${region.id}: parsed ${parsed.lat.size} in ${(System.nanoTime() - tp) / 1_000_000} ms")
        loaded.remove(region.id)
        loaded[region.id] = parsed
        while (loaded.size > MAX_LOADED) loaded.remove(loaded.keys.first())
        true
    }

    /** Hand-rolled: the file is `lat<TAB>lon<TAB>kind` lines of plain decimals. The line/
     *  substring/toDouble/boxed-list version took 14 s for Northern California's 176k features
     *  on a Pixel 4a (2026-09-13); this reads the inflated bytes once and parses the decimals as
     *  integers, a few hundred ms for the same file. */
    private fun parse(raw: InputStream): Loaded {
        val bytes = GZIPInputStream(raw, 1 shl 16).readBytes()
        var las = DoubleArray(1 shl 16); var los = DoubleArray(1 shl 16); var ks = ByteArray(1 shl 16); var n = 0
        var i = 0; val end = bytes.size
        while (i < end) {
            // lat
            var neg = false; var ip = 0L; var fp = 0L; var fd = 0; var ok = false; var seenDot = false
            if (bytes[i] == '-'.code.toByte()) { neg = true; i++ }
            while (i < end) {
                val c = bytes[i].toInt()
                if (c in 48..57) { if (seenDot) { if (fd < 9) { fp = fp * 10 + (c - 48); fd++ } } else ip = ip * 10 + (c - 48); ok = true; i++ }
                else if (c == '.'.code && !seenDot) { seenDot = true; i++ } else break
            }
            val la = if (ok) (ip + fp / POW10[fd]).let { if (neg) -it else it } else Double.NaN
            if (i < end && bytes[i] == '\t'.code.toByte()) i++ else { i = skipLine(bytes, i); continue }
            // lon
            neg = false; ip = 0L; fp = 0L; fd = 0; ok = false; seenDot = false
            if (bytes[i] == '-'.code.toByte()) { neg = true; i++ }
            while (i < end) {
                val c = bytes[i].toInt()
                if (c in 48..57) { if (seenDot) { if (fd < 9) { fp = fp * 10 + (c - 48); fd++ } } else ip = ip * 10 + (c - 48); ok = true; i++ }
                else if (c == '.'.code && !seenDot) { seenDot = true; i++ } else break
            }
            val lo = if (ok) (ip + fp / POW10[fd]).let { if (neg) -it else it } else Double.NaN
            if (i < end && bytes[i] == '\t'.code.toByte()) i++ else { i = skipLine(bytes, i); continue }
            if (i >= end || la.isNaN() || lo.isNaN()) { i = skipLine(bytes, i); continue }
            val k = bytes[i]
            if (n == las.size) { las = las.copyOf(n * 2); los = los.copyOf(n * 2); ks = ks.copyOf(n * 2) }
            las[n] = la; los[n] = lo; ks[n] = k; n++
            i = skipLine(bytes, i)
        }
        return Loaded(las.copyOf(n), los.copyOf(n), ks.copyOf(n))
    }
    private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9)
    private fun skipLine(b: ByteArray, from: Int): Int {
        var i = from
        while (i < b.size && b[i] != '\n'.code.toByte()) i++
        return i + 1
    }

    /** Does the catalog have a region for this spot at all (so a false from ensure* means a failed
     *  download rather than no coverage)? */
    suspend fun hasRegion(context: Context, manifestUrl: String, lat: Double, lng: Double): Boolean =
        regionFor(regions(context, manifestUrl), lat, lng) != null

    /** True when the region covering the box centre is loaded; false = no coverage or failed. */
    suspend fun ensureBox(context: Context, manifestUrl: String, south: Double, west: Double, north: Double, east: Double): Boolean {
        val r = regionFor(regions(context, manifestUrl), (south + north) / 2, (west + east) / 2) ?: return false
        return ensureRegion(context, r)
    }

    /** True when every region a route passes through is loaded (sampled every ~40 km). */
    suspend fun ensureAlong(context: Context, manifestUrl: String, polyline: List<LatLng>): Boolean {
        if (polyline.isEmpty()) return false
        val regs = regions(context, manifestUrl)
        val ids = LinkedHashMap<String, Region>()
        var acc = Double.MAX_VALUE // distance since the last sampled point; the first point always samples
        var last: LatLng? = null
        for (p in polyline) {
            acc += last?.distanceTo(p) ?: 0.0
            if (acc >= 40_000.0) {
                regionFor(regs, p.lat, p.lng)?.let { ids[it.id] = it } ?: return false
                acc = 0.0
            }
            last = p
        }
        polyline.last().let { p -> regionFor(regs, p.lat, p.lng)?.let { ids[it.id] = it } ?: return false }
        var all = true
        for (r in ids.values) if (!ensureRegion(context, r)) all = false
        return all
    }

    private inline fun scan(south: Double, west: Double, north: Double, east: Double, visit: (Double, Double, Char) -> Unit) {
        val sets = synchronized(loaded) { loaded.values.toList() }
        val r0 = rowOf(south); val r1 = rowOf(north); val c0 = rowOf(west); val c1 = rowOf(east)
        for (l in sets) {
            var r = r0
            while (r <= r1) {
                var c = c0
                while (c <= c1) {
                    l.grid[key(r, c)]?.let { bucket ->
                        for (i in bucket) {
                            val la = l.lat[i]; val lo = l.lng[i]
                            if (la in south..north && lo in west..east) visit(la, lo, l.kind[i].toInt().toChar())
                        }
                    }
                    c++
                }
                r++
            }
        }
    }

    private fun kindOf(c: Char): TrafficControl.Kind? = when (c) {
        'S' -> TrafficControl.Kind.SIGNAL
        'T' -> TrafficControl.Kind.STOP
        'R' -> TrafficControl.Kind.RAIL_CROSSING
        'H' -> TrafficControl.Kind.SPEED_HUMP
        else -> null
    }

    fun controlsInBox(south: Double, west: Double, north: Double, east: Double): List<TrafficControl> {
        val out = ArrayList<TrafficControl>()
        scan(south, west, north, east) { la, lo, k -> kindOf(k)?.let { out.add(TrafficControl(LatLng(la, lo), it)) } }
        return out
    }

    fun camerasInBox(south: Double, west: Double, north: Double, east: Double): List<SpeedCamera> {
        val out = ArrayList<SpeedCamera>()
        scan(south, west, north, east) { la, lo, k -> if (k == 'C') out.add(SpeedCamera(LatLng(la, lo))) }
        return out
    }

    private fun corridorBox(polyline: List<LatLng>, meters: Double): DoubleArray {
        val pad = meters / 111_320.0 * 1.5 + 0.001
        return doubleArrayOf(polyline.minOf { it.lat } - pad, polyline.minOf { it.lng } - pad, polyline.maxOf { it.lat } + pad, polyline.maxOf { it.lng } + pad)
    }

    /** Controls within [meters] of the route line. */
    // The corridor queries are CPU-bound over the whole bounding box of the route: a long drive
    // (Davis to San Francisco, ~5000 polyline points, ~50k features in its box) ANR'd the app
    // three times on 2026-09-13 when this ran on the main thread and tested every feature against
    // every segment. Two fixes: callers run these on Dispatchers.Default, and the polyline is
    // indexed into ~1 km cells first so each feature is tested against the handful of segments
    // near it instead of the whole line.
    fun controlsAlong(polyline: List<LatLng>, meters: Double = 120.0): List<TrafficControl> {
        if (polyline.size < 2) return emptyList()
        val b = corridorBox(polyline, meters)
        val t0 = System.nanoTime()
        val idx = SegmentIndex(polyline, meters)
        val t1 = System.nanoTime()
        val out = ArrayList<TrafficControl>()
        var visited = 0
        scan(b[0], b[1], b[2], b[3]) { la, lo, k ->
            val kind = kindOf(k) ?: return@scan
            visited++
            if (idx.near(la, lo)) out.add(TrafficControl(LatLng(la, lo), kind))
        }
        android.util.Log.i("VelaControls", "corridor pts=${polyline.size} kept=${idx.segments} index=${(t1 - t0) / 1_000_000} ms scan=${(System.nanoTime() - t1) / 1_000_000} ms visited=$visited hits=${out.size}")
        return out
    }

    fun camerasAlong(polyline: List<LatLng>, meters: Double = 150.0): List<SpeedCamera> {
        if (polyline.size < 2) return emptyList()
        val b = corridorBox(polyline, meters)
        val idx = SegmentIndex(polyline, meters)
        val out = ArrayList<SpeedCamera>()
        scan(b[0], b[1], b[2], b[3]) { la, lo, k ->
            if (k != 'C') return@scan
            if (idx.near(la, lo)) out.add(SpeedCamera(LatLng(la, lo)))
        }
        return out
    }

    /** Traffic signals on the route, for the "pass the light, then turn" enrichment. */
    fun signalsAlong(polyline: List<LatLng>, meters: Double = 40.0): List<LatLng> =
        controlsAlong(polyline, meters).filter { it.kind == TrafficControl.Kind.SIGNAL }.map { it.loc }

    /** The polyline's segments bucketed into [FINE] degree cells (about 1.1 km), each segment
     *  registered in every cell its padded bounding box touches, so a point-to-line test reads
     *  only the segments that can possibly be within [meters] of the point. */
    private class SegmentIndex(polyline: List<LatLng>, private val meters: Double) {
        private val cells = HashMap<Long, IntArrayList>()
        // The route arrives at polyline6 resolution (a vertex every ~15 m on a highway); for a
        // "within 120 m" test that is 10x more segments than the shape needs, so the line is
        // simplified to 3 m first (Douglas-Peucker, iterative).
        private val poly: List<LatLng> = simplify(polyline, 3.0)
        val segments get() = poly.size - 1
        init {
            val padLat = meters / 111_320.0
            for (i in 0 until poly.size - 1) {
                val a = poly[i]; val b = poly[i + 1]
                val padLng = meters / (111_320.0 * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0))).coerceAtLeast(1.0)
                val r0 = fine(minOf(a.lat, b.lat) - padLat); val r1 = fine(maxOf(a.lat, b.lat) + padLat)
                val c0 = fine(minOf(a.lng, b.lng) - padLng); val c1 = fine(maxOf(a.lng, b.lng) + padLng)
                var r = r0
                while (r <= r1) {
                    var c = c0
                    while (c <= c1) { cells.getOrPut(key(r, c)) { IntArrayList() }.add(i); c++ }
                    r++
                }
            }
        }
        fun near(lat: Double, lng: Double): Boolean {
            val bucket = cells[key(fine(lat), fine(lng))] ?: return false
            val p = LatLng(lat, lng)
            for (j in 0 until bucket.size) {
                val i = bucket.data[j]
                if (segDist(p, poly[i], poly[i + 1]) <= meters) return true
            }
            return false
        }
        private fun fine(v: Double): Long = Math.floor(v / FINE).toLong()
        private fun key(row: Long, col: Long): Long = (row shl 32) xor (col and 0xffffffffL)
        private fun simplify(pts: List<LatLng>, tolM: Double): List<LatLng> {
            if (pts.size < 3) return pts
            val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
            val stack = ArrayDeque<IntArray>(); stack.add(intArrayOf(0, pts.size - 1))
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a < 2) continue
                var best = -1; var bestD = tolM
                val pa = pts[a]; val pb = pts[b]
                for (i in a + 1 until b) {
                    val d = segDist(pts[i], pa, pb)
                    if (d > bestD) { bestD = d; best = i }
                }
                if (best >= 0) { keep[best] = true; stack.add(intArrayOf(a, best)); stack.add(intArrayOf(best, b)) }
            }
            val out = ArrayList<LatLng>()
            for (i in pts.indices) if (keep[i]) out.add(pts[i])
            return out
        }
        private class IntArrayList {
            var data = IntArray(8); var size = 0
            fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        }
        companion object { const val FINE = 0.01 }
    }

}

private fun segDist(p: LatLng, a: LatLng, b: LatLng): Double {
    val mPerLat = 111_320.0
    val mPerLng = 111_320.0 * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0))
    val bx = (b.lng - a.lng) * mPerLng; val by = (b.lat - a.lat) * mPerLat
    val px = (p.lng - a.lng) * mPerLng; val py = (p.lat - a.lat) * mPerLat
    val len2 = bx * bx + by * by
    val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - bx * t; val dy = py - by * t
    return Math.sqrt(dx * dx + dy * dy)
}
