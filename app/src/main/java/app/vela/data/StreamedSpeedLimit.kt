package app.vela.data

import app.vela.core.data.OsmMaxspeed
import app.vela.core.model.LatLng
import app.vela.core.model.bearingTo
import app.vela.core.model.distanceTo
import app.vela.core.util.MvtLines
import app.vela.offline.PmtilesReader
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs
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
    private const val CONTINUITY_SNAP_M = 12.0
    private const val SNAP_BEARING_TOL_DEG = 35.0
    private const val FREE_DRIVE_MAX_DRIFT_DEG = 45.0
    private const val FREE_DRIVE_MAX_TURN_DEG = 35.0
    private const val FREE_DRIVE_NODE_TURN_DEG = 30.0
    private const val MISS_RETRY_MS = 60_000L
    private val cacheLock = Any()
    private val archives = HashMap<String, PmtilesReader.Archive>()
    private class Held(val lines: List<MvtLines.Line>, val at: Long)
    private val tiles = object : LinkedHashMap<String, Held>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Held>?) = size > 12
    }
    @Volatile private var lastMatchedPts: List<LatLng>? = null

    internal data class LineSnap(
        val line: MvtLines.Line,
        val segIdx: Int,
        val forward: Boolean?,
        val proj: LatLng,
        val distM: Double,
        val kmh: Double,
    )

    internal fun clearContinuity() {
        synchronized(cacheLock) { lastMatchedPts = null }
    }

    internal fun directedAngleDiff(aDeg: Double, bDeg: Double): Double {
        val d = abs(((aDeg - bDeg) % 360.0 + 540.0) % 360.0 - 180.0)
        return d
    }

    internal fun linesConnected(a: List<LatLng>, b: List<LatLng>, maxGapM: Double = 6.0): Boolean {
        if (a === b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val refLat = a[0].lat
        val k = 111_320.0 * cos(Math.toRadians(refLat))
        val max2 = maxGapM * maxGapM
        for (pa in a) {
            for (pb in b) {
                val dx = (pa.lng - pb.lng) * k
                val dy = (pa.lat - pb.lat) * 111_320.0
                if (dx * dx + dy * dy <= max2) return true
            }
        }
        return false
    }

    internal fun snapLine(
        lines: List<MvtLines.Line>,
        lat: Double,
        lng: Double,
        headingDeg: Double?,
        prevPts: List<LatLng>?,
        now: LocalDateTime,
    ): LineSnap? {
        val k = 111_320.0 * cos(Math.toRadians(lat))
        var bestAny: LineSnap? = null
        var bestConnected: LineSnap? = null
        for (l in lines) {
            val pts = l.points
            if (pts.size < 2) continue
            val connected = prevPts != null && linesConnected(prevPts, pts)
            for (i in 0 until pts.size - 1) {
                val ax = (pts[i].lng - lng) * k
                val ay = (pts[i].lat - lat) * 111_320.0
                val bx = (pts[i + 1].lng - lng) * k
                val by = (pts[i + 1].lat - lat) * 111_320.0
                val dx = bx - ax
                val dy = by - ay
                val isForward: Boolean? = if (headingDeg != null && (dx != 0.0 || dy != 0.0)) {
                    val segFwdDeg = (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
                    val fwdDiff = directedAngleDiff(headingDeg, segFwdDeg)
                    val bwdDiff = directedAngleDiff(headingDeg, (segFwdDeg + 180.0) % 360.0)
                    if (minOf(fwdDiff, bwdDiff) > SNAP_BEARING_TOL_DEG) continue
                    fwdDiff <= bwdDiff
                } else {
                    null
                }
                val kmh = OsmMaxspeed.fromTags(
                    l.props["maxspeed"], l.props["maxspeed:forward"], l.props["maxspeed:backward"],
                    l.props["maxspeed:conditional"], now, isForward,
                ) ?: continue
                val len = dx * dx + dy * dy
                val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d >= SNAP_M) continue
                val proj = LatLng(
                    pts[i].lat + (pts[i + 1].lat - pts[i].lat) * t,
                    pts[i].lng + (pts[i + 1].lng - pts[i].lng) * t,
                )
                val cand = LineSnap(l, i, isForward, proj, d, kmh)
                if (bestAny == null || d < bestAny.distM) bestAny = cand
                if (connected && d <= CONTINUITY_SNAP_M && (bestConnected == null || d < bestConnected.distM)) {
                    bestConnected = cand
                }
            }
        }
        return bestConnected ?: bestAny
    }

    private fun tileLines(uri: String, lat: Double, lng: Double): List<MvtLines.Line> {
        val a = synchronized(cacheLock) { archive(uri) } ?: return emptyList()
        // Network header/tile reads run outside cacheLock so concurrent callers never stall on I/O.
        val h = a.header ?: run {
            synchronized(cacheLock) { archives.remove(uri) }?.let { runCatching { it.close() } }
            return emptyList()
        }
        val z = h.maxZoom
        val n = 1 shl z
        val x = floor((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val r = Math.toRadians(lat)
        val y = floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val key = "$uri|$z|$x|$y"
        val now = android.os.SystemClock.elapsedRealtime()
        val existing = synchronized(cacheLock) { tiles[key] }
        if (existing != null && !(existing.lines.isEmpty() && now - existing.at > MISS_RETRY_MS)) {
            return existing.lines
        }
        val raw = a.tile(z, x, y)
        val decoded = if (raw == null) emptyList()
        else runCatching { MvtLines.decode(raw, z, x, y, "maxspeed") }.getOrDefault(emptyList())
        val held = Held(decoded, now)
        synchronized(cacheLock) { tiles[key] = held }
        return held.lines
    }

    private fun linesForPoint(uris: List<String>, lat: Double, lng: Double): List<MvtLines.Line> =
        if (uris.size == 1) tileLines(uris[0], lat, lng)
        else uris.flatMap { tileLines(it, lat, lng) }

    /** km/h at the point from the first of [uris] (`pmtiles://https://..` or `pmtiles://file://..`)
     *  that has a limit there, or null. Blocking: call off the main thread. */
    fun limitAt(uris: List<String>, lat: Double, lng: Double, headingDeg: Double? = null): Double? {
        val now = app.vela.ui.DemoClock.now() ?: LocalDateTime.now()
        val prevPts = lastMatchedPts
        for (uri in uris) {
            val snap = snapLine(tileLines(uri, lat, lng), lat, lng, headingDeg, prevPts, now)
            if (snap != null) {
                lastMatchedPts = snap.line.points
                return snap.kmh
            }
        }
        return null
    }

    /** First speed limit change `(limitKmh, distanceAheadMeters)` along [poly] between [startMeters]
     *  and `startMeters + lookAheadMeters` that differs from [currentLimitKmh], or null. */
    fun upcomingLimitAlong(
        uris: List<String>,
        poly: List<LatLng>,
        startMeters: Double,
        lookAheadMeters: Double,
        currentLimitKmh: Double?,
    ): Pair<Double, Double>? {
        val base = currentLimitKmh ?: return null
        if (uris.isEmpty() || poly.size < 2 || lookAheadMeters < 40.0) return null
        val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
        val total = cum.last()
        if (startMeters >= total - 30.0) return null
        val now = app.vela.ui.DemoClock.now() ?: LocalDateTime.now()
        val startPt = app.vela.core.nav.RouteProjection.pointAt(poly, cum, startMeters)
        val startBrg = app.vela.core.nav.RouteProjection.bearingAt(poly, cum, startMeters)
        val initialPrev = lastMatchedPts
        var prevPts: List<LatLng>? = snapLine(
            linesForPoint(uris, startPt.lat, startPt.lng),
            startPt.lat, startPt.lng, startBrg, initialPrev, now,
        )?.line?.points ?: initialPrev
        fun sampleAt(m: Double): Double? {
            val pt = app.vela.core.nav.RouteProjection.pointAt(poly, cum, m)
            val brg = app.vela.core.nav.RouteProjection.bearingAt(poly, cum, m)
            val snap = snapLine(linesForPoint(uris, pt.lat, pt.lng), pt.lat, pt.lng, brg, prevPts, now) ?: return null
            prevPts = snap.line.points
            return snap.kmh
        }
        val endM = minOf(total, startMeters + lookAheadMeters)
        var d = startMeters + 45.0
        while (d <= endM) {
            val kmh = sampleAt(d)
            if (kmh != null && abs(kmh - base) >= 3.0) {
                val confirmM = minOf(total, d + 25.0)
                val confirmKmh = if (confirmM > d + 5.0) sampleAt(confirmM) else kmh
                if (confirmKmh != null && abs(confirmKmh - kmh) < 3.0) {
                    return kmh to (d - startMeters)
                }
            }
            d += 40.0
        }
        return null
    }

    /** First speed limit change `(limitKmh, distanceAheadMeters)` walking along the connected road
     *  ahead from ([lat],[lng]) moving [headingDeg] within [lookAheadMeters], or null. */
    fun upcomingLimitAhead(
        uris: List<String>,
        lat: Double,
        lng: Double,
        headingDeg: Double,
        lookAheadMeters: Double,
        currentLimitKmh: Double?,
    ): Pair<Double, Double>? {
        if (uris.isEmpty()) return null
        return upcomingInLines(
            lat, lng, headingDeg, lookAheadMeters, currentLimitKmh,
            lastMatchedPts, app.vela.ui.DemoClock.now() ?: LocalDateTime.now(),
        ) { pLat, pLng -> linesForPoint(uris, pLat, pLng) }
    }

    private data class LineBranch(
        val line: MvtLines.Line,
        val vertexIdx: Int,
        val forward: Boolean,
        val turnDeg: Double,
        val kmh: Double,
    )

    private fun lineVisitKey(pts: List<LatLng>, forward: Boolean): String {
        val a = pts.first()
        val b = pts.last()
        return "${(a.lat * 1e6).toLong()},${(a.lng * 1e6).toLong()}|${(b.lat * 1e6).toLong()},${(b.lng * 1e6).toLong()}|$forward"
    }

    internal fun upcomingInLines(
        lat: Double,
        lng: Double,
        headingDeg: Double,
        lookAheadMeters: Double,
        currentLimitKmh: Double?,
        prevPts: List<LatLng>?,
        now: LocalDateTime,
        linesAt: (Double, Double) -> List<MvtLines.Line>,
    ): Pair<Double, Double>? {
        val base = currentLimitKmh ?: return null
        if (lookAheadMeters < 40.0) return null
        val startSnap = snapLine(linesAt(lat, lng), lat, lng, headingDeg, prevPts, now) ?: return null
        var curLine = startSnap.line
        var curFwd = startSnap.forward ?: return null
        var curPt = startSnap.proj
        var nextIdx = if (curFwd) startSnap.segIdx + 1 else startSnap.segIdx
        var prevBrg = headingDeg
        var distM = 0.0
        var pendingKmh: Double? = null
        var pendingAtM = 0.0
        var pendingSpanM = 0.0
        val visited = HashSet<String>()
        visited.add(lineVisitKey(curLine.points, curFwd))

        while (distM <= lookAheadMeters + 30.0 && visited.size <= 24) {
            val step = if (curFwd) 1 else -1
            val pts = curLine.points
            val lineKmh = OsmMaxspeed.fromTags(
                curLine.props["maxspeed"], curLine.props["maxspeed:forward"], curLine.props["maxspeed:backward"],
                curLine.props["maxspeed:conditional"], now, curFwd,
            )
            while (nextIdx in pts.indices) {
                val v = pts[nextIdx]
                val segLen = curPt.distanceTo(v)
                if (segLen > 2.0) {
                    val segBrg = curPt.bearingTo(v)
                    if (directedAngleDiff(headingDeg, segBrg) > FREE_DRIVE_MAX_DRIFT_DEG ||
                        directedAngleDiff(prevBrg, segBrg) > FREE_DRIVE_MAX_TURN_DEG
                    ) {
                        return if (pendingKmh != null && pendingSpanM >= 20.0) pendingKmh to pendingAtM else null
                    }
                    prevBrg = segBrg
                }
                if (lineKmh != null && abs(lineKmh - base) >= 3.0) {
                    if (pendingKmh == null || abs(lineKmh - pendingKmh) >= 3.0) {
                        if (distM > lookAheadMeters) return null
                        pendingKmh = lineKmh
                        pendingAtM = maxOf(1.0, distM)
                        pendingSpanM = segLen
                    } else {
                        pendingSpanM += segLen
                    }
                    if (pendingSpanM >= 20.0) return pendingKmh to pendingAtM
                } else {
                    pendingKmh = null
                    pendingSpanM = 0.0
                }
                distM += segLen
                curPt = v
                if (distM > lookAheadMeters + 30.0) break
                nextIdx += step
            }
            if (distM > lookAheadMeters + 30.0) break

            val near = linesAt(curPt.lat, curPt.lng)
            val curSameEndKey = lineVisitKey(curLine.points, !curFwd)
            val branches = ArrayList<LineBranch>()
            for (cand in near) {
                val cPts = cand.points
                if (cPts.size < 2) continue
                var matchK = -1
                var bestD = 6.0001
                for (k in cPts.indices) {
                    val d = curPt.distanceTo(cPts[k])
                    if (d < bestD) { bestD = d; matchK = k }
                }
                if (matchK < 0) continue
                for (candFwd in listOf(true, false)) {
                    val candStep = if (candFwd) 1 else -1
                    val adjIdx = matchK + candStep
                    if (adjIdx !in cPts.indices) continue
                    val candKey = lineVisitKey(cPts, candFwd)
                    if (candKey == curSameEndKey || candKey in visited) continue
                    val kmh = OsmMaxspeed.fromTags(
                        cand.props["maxspeed"], cand.props["maxspeed:forward"], cand.props["maxspeed:backward"],
                        cand.props["maxspeed:conditional"], now, candFwd,
                    ) ?: continue
                    val kPt = cPts[matchK]
                    var probe = adjIdx
                    var outPt = cPts[probe]
                    while (probe + candStep in cPts.indices && kPt.distanceTo(outPt) < 2.0) {
                        probe += candStep
                        outPt = cPts[probe]
                    }
                    if (kPt.distanceTo(outPt) < 0.5) continue
                    val outBrg = kPt.bearingTo(outPt)
                    val turnDeg = directedAngleDiff(prevBrg, outBrg)
                    if (turnDeg <= FREE_DRIVE_NODE_TURN_DEG &&
                        directedAngleDiff(headingDeg, outBrg) <= FREE_DRIVE_MAX_DRIFT_DEG
                    ) {
                        branches += LineBranch(cand, matchK, candFwd, turnDeg, kmh)
                    }
                }
            }

            val chosen = when {
                branches.isEmpty() -> null
                branches.size == 1 -> branches[0]
                branches.all { abs(it.kmh - branches[0].kmh) < 3.0 } -> branches.minBy { it.turnDeg }
                else -> null
            } ?: break

            curLine = chosen.line
            curFwd = chosen.forward
            val kPt = curLine.points[chosen.vertexIdx]
            distM += curPt.distanceTo(kPt)
            curPt = kPt
            nextIdx = chosen.vertexIdx + (if (curFwd) 1 else -1)
            visited.add(lineVisitKey(curLine.points, curFwd))
        }
        return if (pendingKmh != null && pendingSpanM >= 20.0) pendingKmh to pendingAtM else null
    }

    private fun archive(uri: String): PmtilesReader.Archive? {
        // A rebake replaced the file behind a streamed address: open it again (PmtilesReader.HttpRangeReader).
        archives[uri]?.let { if (!it.replaced) return it; archives.remove(uri); runCatching { it.close() } }
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
