package app.vela.data

import app.vela.core.data.OsmMaxspeed
import app.vela.core.util.MvtLines
import app.vela.offline.PmtilesReader
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/**
 * The posted limit under the car, read from the speed-limit archive itself: the one tile the car
 * is in, kept until it leaves it. The limits used to be mounted on the map as an invisible layer
 * and read back with a rendered-features query, which made the map load every tile of the archive
 * the tilted view covered, hundreds of range requests every ten seconds of a drive, for one number.
 */
object StreamedSpeedLimit {
    /** Set once at start (the shared client); null = no lookups. */
    @Volatile var http: OkHttpClient? = null

    private const val SNAP_M = 20.0
    private const val MISS_RETRY_MS = 60_000L
    private val archives = HashMap<String, PmtilesReader.Archive>()
    private class Held(val lines: List<MvtLines.Line>, val at: Long)
    private val tiles = object : LinkedHashMap<String, Held>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Held>?) = size > 12
    }

    /** km/h at the point from the first of [uris] (`pmtiles://https://..` or `pmtiles://file://..`)
     *  that has a limit there, or null. Blocking: call off the main thread. */
    @Synchronized
    fun limitAt(uris: List<String>, lat: Double, lng: Double): Double? {
        for (uri in uris) {
            val a = archive(uri) ?: continue
            val h = a.header ?: continue
            val z = h.maxZoom
            val n = 1 shl z
            val x = floor((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val r = Math.toRadians(lat)
            val y = floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            val key = "$uri|$z|$x|$y"
            val now = android.os.SystemClock.elapsedRealtime()
            var held = tiles[key]
            // An empty answer may be a failed read, so it is asked again after a while.
            if (held == null || (held.lines.isEmpty() && now - held.at > MISS_RETRY_MS)) {
                val raw = a.tile(z, x, y)
                held = Held(if (raw == null) emptyList() else runCatching { MvtLines.decode(raw, z, x, y, "maxspeed") }.getOrDefault(emptyList()), now)
                tiles[key] = held
            }
            nearest(held.lines, lat, lng)?.let { return it }
        }
        return null
    }

    private fun nearest(lines: List<MvtLines.Line>, lat: Double, lng: Double): Double? {
        val k = 111_320.0 * cos(Math.toRadians(lat))
        var best: Double? = null
        var bestD = SNAP_M
        for (l in lines) {
            val kmh = OsmMaxspeed.fromTags(l.props["maxspeed"], l.props["maxspeed:forward"], l.props["maxspeed:backward"]) ?: continue
            val pts = l.points
            for (i in 0 until pts.size - 1) {
                val ax = (pts[i].lng - lng) * k; val ay = (pts[i].lat - lat) * 111_320.0
                val bx = (pts[i + 1].lng - lng) * k; val by = (pts[i + 1].lat - lat) * 111_320.0
                val dx = bx - ax; val dy = by - ay
                val len = dx * dx + dy * dy
                val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < bestD) { bestD = d; best = kmh }
            }
        }
        return best
    }

    private fun archive(uri: String): PmtilesReader.Archive? {
        archives[uri]?.let { return it }
        val target = uri.removePrefix("pmtiles://")
        val a = when {
            target.startsWith("file://") -> File(target.removePrefix("file://")).takeIf { it.exists() }?.let { PmtilesReader.Archive.file(it) }
            target.startsWith("http") -> http?.let { PmtilesReader.Archive.http(it, target) }
            else -> null
        } ?: return null
        if (archives.size > 6) { archives.values.forEach { runCatching { it.close() } }; archives.clear() }
        archives[uri] = a
        return a
    }
}
