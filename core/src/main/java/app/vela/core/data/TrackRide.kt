package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.nav.RouteProjection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Riding a GPX track (SPEC 4.3, Riding a GPX track). A track opened in Vela is map-matched once
 * as a bicycle route (Valhalla `trace_route`, in pieces of at most [PIECE_M]) and kept here while
 * the process lives. A bicycle route request that ends at the track's end is then answered with
 * the whole matched track, wherever it is asked from: Vela never routes to the track or along a
 * line of its own, and guidance starts when the rider reaches the line. A drive on a track route does not reroute ([isTrack], NavSession.reroute).
 * Each piece of [PIECE_M] is matched with bicycle costing, then pedestrian costing (walkways), and
 * a piece both refuse takes its turns from the track's own bends, named from the map tiles
 * (LineNamer); only when that fails too does it keep just its start and end steps.
 */
object TrackRide {
    /** Track matched per request: a short piece keeps one stretch the matcher refuses (a walkway,
     *  a line drawn on other map data) from costing the whole track its turns. */
    const val PIECE_M = 5_000.0
    /** A piece ends at the straightest track point within this of [PIECE_M], so no turn sits on a seam. */
    const val SEAM_SEARCH_M = 400.0
    /** At a seam the line turns more than this: the next piece's departure is kept as a turn. */
    const val SEAM_TURN_DEG = 40.0
    /** Marks a route served from a track ([Route.made] starts with it). */
    const val MADE = "track"
    /** A request ends at the track when its destination is this near the track's last point. */
    const val END_M = 300.0
    /** Track points closer than this to the last kept one are dropped before matching. */
    const val THIN_M = 5.0
    /** Riding speed for a piece the matcher refused (m/s, about 15 km/h). */
    const val SPEED_MS = 4.2

    class Track(val name: String, val line: List<LatLng>) {
        val cum: DoubleArray = RouteProjection.cumulative(line)
        val length: Double get() = cum.lastOrNull() ?: 0.0
        @Volatile internal var matched: Route? = null
        /** How the pieces were named, for the trip log: "matched 18/20 bends 2/20". */
        @Volatile var matchNote: String = ""
        internal val lock = Mutex()
    }

    @Volatile var active: Track? = null
        private set

    /** A step's words in the directions language, from the same templates as every other route. */
    private fun say(type: String, mod: String? = null, road: String? = null) =
        NavStringsRegistry.current().phrase(type, mod, road, null, null, null)

    /** Starts riding [line] (already parsed). The match runs on the first route request. */
    fun start(name: String, line: List<LatLng>): Track? {
        val pts = thin(line)
        if (pts.size < 2) return null
        return Track(name, pts).also { active = it }
    }

    private suspend fun matchedOf(t: Track, http: OkHttpClient): Route =
        t.matched ?: t.lock.withLock { t.matched ?: withContext(Dispatchers.IO) { match(http, t) }.also { t.matched = it } }

    fun clear() { active = null }

    /** [r] was served from a track: a drive on it follows the line and never reroutes. */
    fun isTrack(r: Route?): Boolean = r?.made?.startsWith(MADE) == true

    /** The routes for a bicycle request to [destination]: the whole matched track, or null when no
     *  track is being ridden or the request does not end at it. */
    suspend fun routeFor(http: OkHttpClient, destination: LatLng, waypoints: List<LatLng>): List<Route>? {
        val t = active ?: return null
        if (waypoints.isNotEmpty() || t.line.last().distanceTo(destination) > END_M) return null
        return listOf(matchedOf(t, http).copy(source = RouteSource.VALHALLA, savedName = t.name, made = "$MADE ${t.matchNote}"))
    }

    internal fun thin(line: List<LatLng>): List<LatLng> {
        val out = ArrayList<LatLng>(line.size)
        for (p in line) if (out.isEmpty() || out.last().distanceTo(p) >= THIN_M) out += p
        if (line.isNotEmpty() && out.last() != line.last()) out += line.last()
        return out
    }

    /** Map-matches the track piece by piece and joins the pieces into one route. */
    private suspend fun match(http: OkHttpClient, t: Track): Route {
        val pieces = mutableListOf<Route>()
        var matched = 0; var bends = 0
        var i0 = 0
        while (i0 < t.line.size - 1) {
            val i1 = seamAfter(t, i0)
            val shape = t.line.subList(i0, i1 + 1)
            // A walkway piece matched on foot comes back with walking times: it is ridden, so it is re-timed.
            val r = ValhallaRouter.match(http, shape, costing = "bicycle", timeoutMs = 15_000)?.takeIf { it.polyline.size >= 2 }
                ?: ValhallaRouter.match(http, shape, costing = "pedestrian", timeoutMs = 15_000)?.takeIf { it.polyline.size >= 2 }?.let { ridden(it) }
            val piece = if (r != null) { matched++; r } else {
                val named = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    app.vela.core.data.naming.RoadNameTiles.linesAlong(shape)?.let { app.vela.core.data.naming.LineNamer.name(bare(shape), it, TravelMode.BICYCLE, strict = true) }
                }
                if (named != null) bends++
                named?.let { ridden(it).copy(namesShort = true) } ?: bare(shape)
            }
            pieces += piece
            i0 = i1
        }
        val n = pieces.size
        t.matchNote = "matched $matched/$n bends $bends/$n"
        return join(pieces)
    }

    /** The index ending the piece that starts at [i0]: [PIECE_M] on, moved to the straightest
     *  vertex within [SEAM_SEARCH_M], or the track's end. */
    internal fun seamAfter(t: Track, i0: Int): Int {
        val target = t.cum[i0] + PIECE_M
        if (target >= t.length - SEAM_SEARCH_M) return t.line.size - 1
        var best = -1; var bestTurn = Double.MAX_VALUE
        for (i in i0 + 1 until t.line.size - 1) {
            if (t.cum[i] < target - SEAM_SEARCH_M) continue
            if (t.cum[i] > target + SEAM_SEARCH_M) break
            val turn = kotlin.math.abs(turnDeg(t.line[i - 1], t.line[i], t.line[i + 1]))
            if (turn < bestTurn) { bestTurn = turn; best = i }
        }
        if (best > 0) return best
        var i = i0 + 1; while (i < t.line.size - 1 && t.cum[i] < target) i++
        return i
    }

    private fun bearing(a: LatLng, b: LatLng): Double {
        val k = Math.cos(Math.toRadians(a.lat))
        return Math.toDegrees(Math.atan2((b.lng - a.lng) * k, b.lat - a.lat))
    }
    /** Signed change of direction at [b] going a -> b -> c, degrees (right turn positive). */
    internal fun turnDeg(a: LatLng, b: LatLng, c: LatLng): Double {
        val d = bearing(b, c) - bearing(a, b)
        return ((d + 540.0) % 360.0) - 180.0
    }

    /** [r] timed at riding speed [SPEED_MS]: a piece matched with pedestrian costing carries walking
     *  times (a 4 km track read 50 minutes), and a piece named from its bends carries none of its own. */
    internal fun ridden(r: Route): Route {
        val ms = r.maneuvers.map { it.copy(durationSeconds = it.distanceMeters / SPEED_MS) }
        val dur = r.distanceMeters / SPEED_MS
        return r.copy(legs = listOf(RouteLeg(r.distanceMeters, dur, null, ms)), durationSeconds = dur, durationInTrafficSeconds = null, legTimes = emptyList())
    }

    /** A route on [shape] as drawn, with only its start and end steps. */
    internal fun bare(shape: List<LatLng>): Route {
        val len = RouteProjection.cumulative(shape).last()
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, say("depart"), shape.first(), len, len / SPEED_MS),
            Maneuver(ManeuverType.ARRIVE, say("arrive"), shape.last(), 0.0, 0.0),
        )
        return Route(polyline = shape, legs = listOf(RouteLeg(len, len / SPEED_MS, null, ms)), distanceMeters = len,
            durationSeconds = len / SPEED_MS, durationInTrafficSeconds = null, namesShort = true)
    }

    /** Routes ridden one after another as one route: each piece's arrival and the next piece's
     *  departure are dropped, and the departure's run is added to the step before it. */
    internal fun join(parts: List<Route>): Route {
        if (parts.size == 1) return parts[0]
        val poly = ArrayList<LatLng>(); val ms = ArrayList<Maneuver>()
        parts.forEachIndexed { k, r ->
            val pl = if (poly.isNotEmpty() && r.polyline.firstOrNull() == poly.last()) r.polyline.drop(1) else r.polyline
            poly.addAll(pl)
            var steps = r.maneuvers
            if (k < parts.lastIndex) steps = steps.filter { it.type != ManeuverType.ARRIVE }
            if (k > 0 && steps.firstOrNull()?.type == ManeuverType.DEPART && ms.isNotEmpty()) {
                val dep = steps.first()
                val prev = parts[k - 1].polyline; val next = r.polyline
                val turn = if (prev.size >= 2 && next.size >= 2) turnDeg(prev[prev.size - 2], prev.last(), next[1]) else 0.0
                if (kotlin.math.abs(turn) > SEAM_TURN_DEG) {
                    // The line turns at the seam: the departure becomes that turn instead of vanishing.
                    val (type, mod) = when {
                        turn > 120 -> ManeuverType.SHARP_RIGHT to "sharp right"; turn > 0 -> ManeuverType.TURN_RIGHT to "right"
                        turn < -120 -> ManeuverType.SHARP_LEFT to "sharp left"; else -> ManeuverType.TURN_LEFT to "left"
                    }
                    steps = listOf(dep.copy(type = type, instruction = say("turn", mod, dep.road), instructionNoRoad = say("turn", mod))) + steps.drop(1)
                } else {
                    val last = ms.removeAt(ms.lastIndex)
                    ms += last.copy(distanceMeters = last.distanceMeters + dep.distanceMeters, durationSeconds = last.durationSeconds + dep.durationSeconds)
                    steps = steps.drop(1)
                }
            }
            ms.addAll(steps)
        }
        val dist = parts.sumOf { it.distanceMeters }; val dur = parts.sumOf { it.durationSeconds }
        return parts[0].copy(polyline = poly, legs = listOf(RouteLeg(dist, dur, null, ms)), distanceMeters = dist, durationSeconds = dur,
            durationInTrafficSeconds = null, trafficSpans = emptyList(), namesShort = parts.any { it.namesShort }, drawPolyline = null, legTimes = emptyList())
    }
}

/** The track (`trk/trkseg/trkpt`) or, without one, the route (`rte/rtept`) of a GPX file. */
object GpxTrack {
    private val TRKPT = Regex("""<(?:\w+:)?(trkpt|rtept)\b([^>]*)>""")
    private val LAT = Regex("""\blat\s*=\s*["']([-0-9.eE+]+)["']""")
    private val LON = Regex("""\blon\s*=\s*["']([-0-9.eE+]+)["']""")
    private val NAME = Regex("""<(?:\w+:)?name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</(?:\w+:)?name>""", RegexOption.DOT_MATCHES_ALL)

    fun looksLikeGpx(text: String): Boolean = text.contains("<gpx", ignoreCase = true)

    fun parse(text: String): Pair<String?, List<LatLng>> {
        val trk = mutableListOf<LatLng>(); val rte = mutableListOf<LatLng>()
        for (m in TRKPT.findAll(text)) {
            val attrs = m.groupValues[2]
            val lat = LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            (if (m.groupValues[1] == "trkpt") trk else rte) += LatLng(lat, lon)
        }
        val name = NAME.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return name to (if (trk.size >= 2) trk else rte)
    }
}
