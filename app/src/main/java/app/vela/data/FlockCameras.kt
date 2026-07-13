package app.vela.data

import android.content.Context
import app.vela.core.data.AlprCamera
import app.vela.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The ALPR / Flock surveillance-camera dataset, queried ON-DEVICE.
 *
 * The cameras are the community DeFlock project's OpenStreetMap nodes (`surveillance:type=ALPR`). The whole
 * global set is tiny (~124k points), so instead of a live Overpass query per viewport (slow, and it 504s
 * under load) the map layer + route "passes N cameras" count read a compact baked file: gzipped TSV
 * `lat<TAB>lon<TAB>operator`, parsed once off the main thread into flat arrays + a 0.1 deg grid index.
 *
 * TWO tiers of that file, newest wins:
 *  - a **bundled** floor in `assets/flock_cameras.bin` (+ its version in `assets/flock_cameras_version.txt`),
 *    so a fresh install has cameras immediately and offline;
 *  - a **hosted** copy on the `flock-cameras` GitHub release, refreshed by CI (weekly cron), which
 *    [refresh] downloads to `filesDir/flock/cameras.bin` when the manifest version beats what's installed -
 *    so camera data updates WITHOUT shipping an app release (the user's ask 2026-07-13).
 *
 * `OverpassAlprCameras` stays as the fallback for the brief window before the file finishes loading.
 */
object FlockCameras {
    private const val BUNDLED = "flock_cameras.bin"
    private const val BUNDLED_VER = "flock_cameras_version.txt"
    private const val CELL = 0.1 // grid cell size in degrees (~11 km) for the bucket index

    @Volatile private var loaded = false
    private var lat = DoubleArray(0)
    private var lng = DoubleArray(0)
    private var op = arrayOf<String>()
    private val grid = HashMap<Long, MutableList<Int>>()

    val isLoaded: Boolean get() = loaded
    val size: Int get() = lat.size

    private fun key(row: Long, col: Long): Long = (row shl 32) xor (col and 0xffffffffL)
    private fun rowOf(v: Double): Long = Math.floor(v / CELL).toLong()

    private fun dir(context: Context) = File(context.filesDir, "flock").apply { mkdirs() }
    private fun downloadedBin(context: Context) = File(dir(context), "cameras.bin")
    private fun downloadedVer(context: Context) = File(dir(context), "version.txt")

    /** The version currently on disk: the downloaded copy's if present, else the bundled floor's. */
    private fun installedVersion(context: Context): Int {
        val d = downloadedVer(context)
        if (downloadedBin(context).exists() && d.exists()) d.readText().trim().toIntOrNull()?.let { return it }
        return runCatching { context.assets.open(BUNDLED_VER).bufferedReader().use { it.readText() }.trim().toInt() }
            .getOrDefault(0)
    }

    /** Parse the newest available file once, off the main thread. Safe to call repeatedly (a loaded call no-ops). */
    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            val dl = downloadedBin(context)
            val stream = if (dl.exists()) runCatching { dl.inputStream() }.getOrNull()
                else runCatching { context.assets.open(BUNDLED) }.getOrNull()
            if (stream != null) runCatching { loadFrom(stream) }
        }
    }

    /** Build the arrays + index from a gzipped-TSV stream and publish them (never leaves `loaded` false once set). */
    private fun loadFrom(raw: InputStream) {
        val las = ArrayList<Double>(130_000)
        val los = ArrayList<Double>(130_000)
        val ops = ArrayList<String>(130_000)
        val intern = HashMap<String, String>() // operator column is highly repetitive - intern it
        raw.use { r ->
            GZIPInputStream(r).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t'); if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1); if (t2 < 0) continue
                    val la = line.substring(0, t1).toDoubleOrNull() ?: continue
                    val lo = line.substring(t1 + 1, t2).toDoubleOrNull() ?: continue
                    val o = line.substring(t2 + 1)
                    las.add(la); los.add(lo); ops.add(intern.getOrPut(o) { o })
                }
            }
        }
        val g = HashMap<Long, MutableList<Int>>()
        for (i in las.indices) g.getOrPut(key(rowOf(las[i]), rowOf(los[i]))) { ArrayList() }.add(i)
        // Publish (a bad/partial parse threw before here, so we never swap in a half-built set).
        lat = las.toDoubleArray(); lng = los.toDoubleArray(); op = ops.toTypedArray()
        grid.clear(); grid.putAll(g)
        loaded = true
    }

    private val downloadHttp: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /**
     * Check the hosted manifest and, if it carries a newer version than what's on disk, download the fresh
     * dataset and reload from it. Best-effort: any network/parse failure keeps the current data. Called once
     * per launch after [ensureLoaded], so camera data can update without an app release.
     */
    suspend fun refresh(context: Context, manifestUrl: String) = withContext(Dispatchers.IO) {
        runCatching {
            val body = downloadHttp.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { if (!it.isSuccessful) return@withContext; it.body?.string().orEmpty() }
            // org.json, not kotlinx.serialization: the :app module stays off kotlinx (a deliberate boundary).
            val obj = org.json.JSONObject(body)
            val version = obj.optInt("version", -1); if (version < 0) return@withContext
            val url = obj.optString("url", ""); if (url.isBlank()) return@withContext
            if (version <= installedVersion(context)) return@withContext
            // Download to a temp file, validate the gzip magic, then atomically swap into place.
            val tmp = File(dir(context), "cameras.bin.tmp")
            downloadHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                val bytes = resp.body?.bytes() ?: return@withContext
                if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return@withContext // not gzip
                tmp.writeBytes(bytes)
            }
            val target = downloadedBin(context)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            downloadedVer(context).writeText(version.toString())
            loadFrom(target.inputStream()) // hot-swap the in-memory set to the fresh data
        }
        Unit
    }

    /** Cameras inside the bbox, for DRAWING. Empty if not loaded yet (caller falls back to Overpass). */
    fun inBox(south: Double, west: Double, north: Double, east: Double): List<AlprCamera> {
        if (!loaded) return emptyList()
        val out = ArrayList<AlprCamera>()
        val r0 = rowOf(south); val r1 = rowOf(north)
        val c0 = rowOf(west); val c1 = rowOf(east)
        var r = r0
        while (r <= r1) {
            var c = c0
            while (c <= c1) {
                grid[key(r, c)]?.let { bucket ->
                    for (i in bucket) {
                        if (lat[i] in south..north && lng[i] in west..east) out.add(AlprCamera(LatLng(lat[i], lng[i]), op[i]))
                    }
                }
                c++
            }
            r++
        }
        return out
    }

    /** Cameras within [meters] of any SEGMENT of [polyline], for the route count. Empty if not loaded. */
    fun along(polyline: List<LatLng>, meters: Double = 120.0): List<AlprCamera> {
        if (!loaded || polyline.size < 2) return emptyList()
        val pad = 0.01
        val r0 = rowOf(polyline.minOf { it.lat } - pad); val r1 = rowOf(polyline.maxOf { it.lat } + pad)
        val c0 = rowOf(polyline.minOf { it.lng } - pad); val c1 = rowOf(polyline.maxOf { it.lng } + pad)
        val out = ArrayList<AlprCamera>()
        var r = r0
        while (r <= r1) {
            var c = c0
            while (c <= c1) {
                grid[key(r, c)]?.let { bucket ->
                    for (i in bucket) {
                        val p = LatLng(lat[i], lng[i])
                        if (nearPolyline(p, polyline, meters)) out.add(AlprCamera(p, op[i]))
                    }
                }
                c++
            }
            r++
        }
        return out
    }

    // Point-to-segment nearness, mirrors OverpassAlprCameras (kept local so :app doesn't reach :core internals).
    private fun nearPolyline(p: LatLng, poly: List<LatLng>, meters: Double): Boolean {
        for (i in 0 until poly.size - 1) if (segDistMeters(p, poly[i], poly[i + 1]) <= meters) return true
        return false
    }

    private fun segDistMeters(p: LatLng, a: LatLng, b: LatLng): Double {
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0))
        val bx = (b.lng - a.lng) * mPerLng; val by = (b.lat - a.lat) * mPerLat
        val px = (p.lng - a.lng) * mPerLng; val py = (p.lat - a.lat) * mPerLat
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val ex = px - t * bx; val ey = py - t * by
        return Math.sqrt(ex * ex + ey * ey)
    }
}
