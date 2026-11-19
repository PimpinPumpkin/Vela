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
    fun limitAt(uris: List<String>, lat: Double, lng: Double, headingDeg: Double? = null): Double? {
        for (uri in uris) {
            val a = archive(uri) ?: continue
            // A header that could not be read (no signal on the first lookup) is held by the
            // archive for good: let it go, and the lookup after next asks again.
            val h = a.header ?: run { archives.remove(uri)?.let { runCatching { it.close() } }; continue }
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
            nearest(held.lines, lat, lng, headingDeg)?.let { return it }
        }
        return null
    }

    /** First speed limit change `(limitKmh, distanceAheadMeters)` along [poly] between [startMeters]
     *  and `startMeters + lookAheadMeters` that differs from [currentLimitKmh], or null. */
    @Synchronized
    fun upcomingLimitAlong(
        uris: List<String>,
        poly: List<app.vela.core.model.LatLng>,
        startMeters: Double,
        lookAheadMeters: Double,
        currentLimitKmh: Double?,
    ): Pair<Double, Double>? {
        val base = currentLimitKmh ?: return null
        if (uris.isEmpty() || poly.size < 2 || lookAheadMeters < 40.0) return null
        val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
        val total = cum.last()
        if (startMeters >= total - 30.0) return null
        val endM = minOf(total, startMeters + lookAheadMeters)
        var d = startMeters + 45.0
        while (d <= endM) {
            val pt = app.vela.core.nav.RouteProjection.pointAt(poly, cum, d)
            val brg = app.vela.core.nav.RouteProjection.bearingAt(poly, cum, d)
            val kmh = limitAt(uris, pt.lat, pt.lng, brg)
            if (kmh != null && kotlin.math.abs(kmh - base) >= 3.0) {
                val confirmM = minOf(total, d + 25.0)
                val confirmKmh = if (confirmM > d + 5.0) {
                    val cpt = app.vela.core.nav.RouteProjection.pointAt(poly, cum, confirmM)
                    val cbrg = app.vela.core.nav.RouteProjection.bearingAt(poly, cum, confirmM)
                    limitAt(uris, cpt.lat, cpt.lng, cbrg)
                } else kmh
                if (confirmKmh != null && kotlin.math.abs(confirmKmh - kmh) < 3.0) {
                    return kmh to (d - startMeters)
                }
            }
            d += 40.0
        }
        return null
    }

    private fun nearest(lines: List<MvtLines.Line>, lat: Double, lng: Double, headingDeg: Double? = null): Double? {
        val k = 111_320.0 * cos(Math.toRadians(lat))
        // Local time on the phone: a timed limit ("130 @ (19:00-06:00)") is read at each lookup,
        // so the badge changes at 19:00 without the tile being read again.
        val now = java.time.LocalDateTime.now()
        var best: Double? = null
        var bestD = SNAP_M
        for (l in lines) {
            val kmh = OsmMaxspeed.fromTags(
                l.props["maxspeed"], l.props["maxspeed:forward"], l.props["maxspeed:backward"],
                l.props["maxspeed:conditional"], now,
            ) ?: continue
            val pts = l.points
            for (i in 0 until pts.size - 1) {
                val ax = (pts[i].lng - lng) * k; val ay = (pts[i].lat - lat) * 111_320.0
                val bx = (pts[i + 1].lng - lng) * k; val by = (pts[i + 1].lat - lat) * 111_320.0
                val dx = bx - ax; val dy = by - ay
                if (headingDeg != null && (dx != 0.0 || dy != 0.0)) {
                    val segDeg = (((Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 180.0)).toInt()
                    if (!app.vela.core.nav.RouteProjection.alignedWithRoad(headingDeg, segDeg, 35.0)) continue
                }
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
