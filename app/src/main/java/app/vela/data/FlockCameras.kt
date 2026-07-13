package app.vela.data

import android.content.Context
import app.vela.core.data.AlprCamera
import app.vela.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

/**
 * The bundled ALPR / Flock surveillance-camera dataset, queried ON-DEVICE.
 *
 * The cameras are the community DeFlock project's OpenStreetMap nodes (`surveillance:type=ALPR`). The whole
 * global set is tiny (~124k points), so instead of a live Overpass query per viewport (slow, and it 504s
 * under load) it's baked into `assets/flock_cameras.tsv.gz` by `scripts/build-flock-cameras.py` and read
 * here. That makes the map layer draw instantly (like the basemap) and the route "passes N cameras" count
 * instant + reliable (so the avoid-cameras re-rank actually has data to work with). `OverpassAlprCameras`
 * stays as the fallback for the brief window before this finishes loading, or if the asset is unreadable.
 *
 * Loaded once off the main thread into flat lat/lng arrays + a coarse grid index (0.1 deg cells).
 */
object FlockCameras {
    // NB the asset is gzipped but named `.bin`, NOT `.gz`: aapt SPECIAL-CASES `.gz` assets and silently
    // un-gzips them at build time (renaming to strip the suffix), which broke `open("...tsv.gz")`. A neutral
    // extension is left untouched, so we gunzip it ourselves here.
    private const val ASSET = "flock_cameras.bin"
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

    /** Parse the bundled asset once, off the main thread. Safe to call repeatedly (a loaded call no-ops). */
    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            try {
                val las = ArrayList<Double>(130_000)
                val los = ArrayList<Double>(130_000)
                val ops = ArrayList<String>(130_000)
                val intern = HashMap<String, String>() // operator column is highly repetitive - intern it
                context.assets.open(ASSET).use { raw ->
                    GZIPInputStream(raw).bufferedReader().useLines { lines ->
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
                lat = las.toDoubleArray()
                lng = los.toDoubleArray()
                op = ops.toTypedArray()
                grid.clear()
                for (i in lat.indices) grid.getOrPut(key(rowOf(lat[i]), rowOf(lng[i]))) { ArrayList() }.add(i)
                loaded = true
            } catch (e: Exception) {
                // asset missing/corrupt: stay unloaded so callers fall back to live Overpass
            }
        }
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
