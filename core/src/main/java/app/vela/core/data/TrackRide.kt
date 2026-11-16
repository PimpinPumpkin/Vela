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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Riding a GPX track (SPEC 4.3, Riding a GPX track). A track opened in Vela is map-matched once
 * as a bicycle route (Valhalla `trace_route`, one-way streets ridden either way, in pieces of at
 * most [PIECE_M]) and kept here while
 * the process lives. A bicycle route request that ends at the track's end is then answered with
 * the whole matched track, wherever it is asked from: Vela never routes to the track or along a
 * line of its own, and guidance starts when the rider reaches the line. A drive on a track route does not reroute ([isTrack], NavSession.reroute).
 * Each piece of [PIECE_M] is matched with bicycle costing. Where that match leaves the track, a
 * window around the place is matched with pedestrian costing (walkways, park paths) and spliced
 * in when it keeps closer to the track ([WINDOW_PAD_M]). A piece the bicycle match fails is
 * matched on foot whole, and a piece both refuse takes its turns from the track's own bends, named from the map tiles (LineNamer);
 * only when that fails too does it keep just its start and end steps. Where a match turns back
 * and the track does not (around a divided road's median), the out-and-back is cut from it
 * ([withoutSpurs]), and two turns closer than [JOG_M] are one movement ([withoutJogs]).
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
    /** Where a piece's bicycle match leaves the track, the track is matched again on foot from
     *  this far before to this far after, and that window is spliced in when it keeps closer to
     *  the track (a park path closed to bikes, which bicycle costing detours around the block). */
    const val WINDOW_PAD_M = 150.0
    /** One match request's time limit. A match is never retried. */
    const val MATCH_TIMEOUT_MS = 10_000L
    /** Pieces matched at once. A driving route sends a match for every stretch where Google's
     *  line leaves the open route at once (4.2); a track's pieces go out the same way, but no
     *  more than this many, so a long track is no bigger a burst on the community server
     *  (fair use) than an ordinary drive. A piece's walked windows follow its own match. */
    const val PIECES_AT_ONCE = 3
    /** The match turns back at a point when the points [REV_M] before and after it along the
     *  line are within [SPUR_GAP_M] of each other (a U-turn, or two turns across a median). */
    const val REV_M = 20.0
    const val SPUR_GAP_M = 15.0
    /** An out-and-back is cut no longer than this each way. */
    const val SPUR_MAX_M = 400.0
    /** A turn-back is the track's own only when the track turns at least this much within
     *  [UTURN_NEAR_M] of it. Else the matcher looped (around a median, a one-way). */
    const val UTURN_TRACK_DEG = 135.0
    const val UTURN_NEAR_M = 40.0
    /** A turn followed by another within this is one movement (a crossing's sidewalk jog). */
    const val JOG_M = 15.0
    /** Two jogged turns that end within this of the heading they started on are no turn at all. */
    const val JOG_STRAIGHT_DEG = 30.0
    /** The heading before and after a jog is taken over this much of the line. */
    const val JOG_SIDE_M = 25.0

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

    private class Tally {
        val n = AtomicInteger(); val matched = AtomicInteger(); val walked = AtomicInteger(); val bends = AtomicInteger()
    }

    /** Map-matches the track piece by piece, [PIECES_AT_ONCE] at a time, and joins the pieces into one route. */
    private suspend fun match(http: OkHttpClient, t: Track): Route {
        val tally = Tally()
        val gate = Semaphore(PIECES_AT_ONCE)
        val pieces = coroutineScope {
            cuts(t.line, t.cum, PIECE_M, SEAM_SEARCH_M).map { (i0, i1) ->
                async(Dispatchers.IO) { gate.withPermit { matchPiece(http, t.line.subList(i0, i1 + 1), tally) } }
            }.awaitAll()
        }
        t.matchNote = "matched ${tally.matched}/${tally.n} walked ${tally.walked} bends ${tally.bends}/${tally.n}"
        return withoutJogs(join(pieces))
    }

    /** One piece as ridden: its bicycle match, with a pedestrian match spliced in where that
     *  leaves the track ([withWalkedWindows]); a piece the bicycle match fails is matched on foot
     *  whole; then the track's own bends; then bare. */
    private suspend fun matchPiece(http: OkHttpClient, shape: List<LatLng>, tally: Tally): Route {
        tally.n.incrementAndGet()
        val bike = matchOf(http, shape, "bicycle")
        if (bike != null) { tally.matched.incrementAndGet(); return withWalkedWindows(http, bike, shape, tally) }
        // A walkway piece matched on foot comes back with walking times: it is ridden, so it is re-timed.
        matchOf(http, shape, "pedestrian")?.let { tally.matched.incrementAndGet(); return ridden(it.first) }
        val named = kotlinx.coroutines.withTimeoutOrNull(20_000) {
            app.vela.core.data.naming.RoadNameTiles.linesAlong(shape)?.let { app.vela.core.data.naming.LineNamer.name(bare(shape), it, TravelMode.BICYCLE, strict = true) }
        }
        if (named != null) tally.bends.incrementAndGet()
        return named?.let { ridden(it).copy(namesShort = true) } ?: bare(shape)
    }

    /** [bike] with each stretch where it leaves [shape] replaced by a pedestrian match of the track
     *  there, [WINDOW_PAD_M] either side, when that one leaves the track less. */
    private suspend fun withWalkedWindows(http: OkHttpClient, bike: Pair<Route, List<Pair<Double, Double>>>, shape: List<LatLng>, tally: Tally): Route {
        val (route, off) = bike
        if (off.isEmpty()) return route
        val cum = RouteProjection.cumulative(shape); val len = cum.last()
        val windows = ArrayList<Pair<Double, Double>>()
        for ((a, b) in off) {
            val w = (a - WINDOW_PAD_M).coerceAtLeast(0.0) to (b + WINDOW_PAD_M).coerceAtMost(len)
            val last = windows.lastOrNull()
            if (last != null && w.first <= last.second) windows[windows.lastIndex] = last.first to maxOf(last.second, w.second) else windows += w
        }
        val walked = ArrayList<Triple<Double, Double, Route>>() // track window and its match
        for ((a, b) in windows) {
            val piece = sliceLine(shape, cum, a, b)
            val walk = matchOf(http, piece, "pedestrian") ?: continue
            val bikeOff = off.sumOf { (x, y) -> (minOf(y, b) - maxOf(x, a)).coerceAtLeast(0.0) }
            if (walk.second.sumOf { it.second - it.first } < bikeOff) { walked += Triple(a, b, ridden(walk.first)); tally.walked.incrementAndGet() }
        }
        if (walked.isEmpty()) return route
        // Where each window starts and ends on the bicycle match, found near the same share of its length.
        val line = route.polyline; val lc = RouteProjection.cumulative(line); val ll = lc.last()
        fun onBike(trackM: Double): Double =
            app.vela.core.nav.NavEngine.projectNearAnchor(line, lc, RouteProjection.pointAt(shape, cum, trackM), trackM / len * ll).first
        val parts = ArrayList<Route>()
        var at = 0.0
        for ((a, b, walk) in walked) {
            val from = onBike(a); val to = onBike(b)
            if (from < at || to <= from) continue
            if (from > at) parts += slice(route, at, from)
            parts += walk
            at = to
        }
        if (at < ll) parts += slice(route, at, ll)
        return join(parts)
    }

    /** [line] from [from] to [to] meters along it ([cum] its running length). */
    private fun sliceLine(line: List<LatLng>, cum: DoubleArray, from: Double, to: Double): List<LatLng> {
        val out = ArrayList<LatLng>()
        out += RouteProjection.pointAt(line, cum, from)
        for (i in line.indices) if (cum[i] > from && cum[i] < to) out += line[i]
        out += RouteProjection.pointAt(line, cum, to)
        return out
    }

    /** The part of [r] from [from] to [to] meters along its line, as a route of its own: a
     *  departure at [from] unless it is the start, and an arrival at [to] unless it is the end.
     *  [join] folds those back in. */
    internal fun slice(r: Route, from: Double, to: Double): Route {
        val line = r.polyline; val cum = RouteProjection.cumulative(line); val total = cum.last()
        val ms = r.maneuvers
        val sum = ms.sumOf { it.distanceMeters }
        val k = if (sum > 0) total / sum else 1.0
        val starts = DoubleArray(ms.size).also { var x = 0.0; for (i in ms.indices) { it[i] = x * k; x += ms[i].distanceMeters } }
        val placed = ArrayList<Pair<Double, Maneuver>>()
        if (from <= 1.0) placed += 0.0 to ms.first()
        else {
            val cover = ms.indices.lastOrNull { starts[it] <= from } ?: 0
            placed += from to Maneuver(ManeuverType.DEPART, say("depart"), RouteProjection.pointAt(line, cum, from), 0.0, 0.0, road = ms[cover].road)
        }
        for (i in ms.indices) if (ms[i].type != ManeuverType.DEPART && ms[i].type != ManeuverType.ARRIVE && starts[i] > from + 2.0 && starts[i] < to - 2.0) placed += starts[i] to ms[i]
        placed += to to (if (to >= total - 1.0) ms.last() else Maneuver(ManeuverType.ARRIVE, say("arrive"), RouteProjection.pointAt(line, cum, to), 0.0, 0.0))
        val pace = if (r.distanceMeters > 0) r.durationSeconds / r.distanceMeters else 1.0 / SPEED_MS
        val out = placed.mapIndexed { i, (x, m) ->
            val run = if (i == placed.lastIndex) 0.0 else (placed[i + 1].first - x).coerceAtLeast(0.0)
            m.copy(distanceMeters = run, durationSeconds = run * pace)
        }
        val poly = sliceLine(line, cum, from, to)
        val len = to - from
        return r.copy(polyline = poly, legs = listOf(RouteLeg(len, len * pace, null, out)), distanceMeters = len, durationSeconds = len * pace,
            durationInTrafficSeconds = null, drawPolyline = null, legTimes = emptyList())
    }

    /** [shape] matched with [costing] (one-way streets either way), its out-and-backs cut
     *  ([withoutSpurs]), and the stretches of track (meters along it) it still leaves
     *  (ValhallaRouter.offLine, measured again after the cut). Null when the matcher fails or its
     *  match strays too far to use. */
    private fun matchOf(http: OkHttpClient, shape: List<LatLng>, costing: String): Pair<Route, List<Pair<Double, Double>>>? {
        val m = ValhallaRouter.matchWithEdges(http, shape, costing, timeoutMs = MATCH_TIMEOUT_MS, ignoreOneways = costing == "bicycle", settleOnFailure = true) ?: return null
        if (m.route.polyline.size < 2) return null
        val r = withoutSpurs(m.route, shape)
        return r to (if (r === m.route) m.off else ValhallaRouter.offLine(r.polyline, shape, 0.0, 0.0) ?: m.off)
    }

    /** Where along [line] (meters) it turns back and the track [shape] does not, one point per
     *  turn-back, at its tightest. */
    internal fun phantomTurnBacks(line: List<LatLng>, cum: DoubleArray, shape: List<LatLng>): List<Double> {
        val total = cum.last()
        if (total < 2 * REV_M || shape.size < 2) return emptyList()
        fun pt(m: Double) = RouteProjection.pointAt(line, cum, m)
        val tc = RouteProjection.cumulative(shape)
        fun trackTurnsBack(at: LatLng): Boolean {
            val j = shape.indices.minByOrNull { shape[it].distanceTo(at) } ?: return false
            var turn = 0.0
            for (k in 1 until shape.size - 1) if (kotlin.math.abs(tc[k] - tc[j]) <= UTURN_NEAR_M) turn += turnDeg(shape[k - 1], shape[k], shape[k + 1])
            return kotlin.math.abs(turn) >= UTURN_TRACK_DEG
        }
        val out = ArrayList<Double>()
        var best = -1.0; var bestGap = Double.MAX_VALUE
        var m = REV_M
        while (m <= total - REV_M) {
            val gap = pt(m - REV_M).distanceTo(pt(m + REV_M))
            if (gap <= SPUR_GAP_M) { if (gap < bestGap) { best = m; bestGap = gap } }
            else if (best >= 0) { if (!trackTurnsBack(pt(best))) out += best; best = -1.0; bestGap = Double.MAX_VALUE }
            m += 5.0
        }
        if (best >= 0 && !trackTurnsBack(pt(best))) out += best
        return out
    }

    /** [r] with each out-and-back the track does not make cut from its line ([phantomTurnBacks]):
     *  from the turn-back the line is followed both ways while the two sides stay within
     *  [SPUR_GAP_M], the stretch between is dropped, and its steps become the one turn the rider
     *  makes there (none when that is within [JOG_STRAIGHT_DEG] of straight on). */
    internal fun withoutSpurs(r: Route, shape: List<LatLng>): Route {
        var cur = r
        repeat(10) {
            if (cur.legs.size != 1) return cur
            val line = cur.polyline
            val cum = RouteProjection.cumulative(line); val total = cum.last()
            val apex = phantomTurnBacks(line, cum, shape).firstOrNull() ?: return cur
            fun pt(m: Double) = RouteProjection.pointAt(line, cum, m.coerceIn(0.0, total))
            var d = REV_M
            while (d + 5 <= SPUR_MAX_M && apex - d - 5 >= 0 && apex + d + 5 <= total && pt(apex - d - 5).distanceTo(pt(apex + d + 5)) <= SPUR_GAP_M) d += 5.0
            cur = excise(cur, line, cum, apex - d, apex + d)
        }
        return cur
    }

    /** [r] without its line between [base] and [rejoin] (meters along it), its steps there
     *  replaced by the net turn. */
    private fun excise(r: Route, line: List<LatLng>, cum: DoubleArray, base: Double, rejoin: Double): Route {
        val total = cum.last()
        fun pt(m: Double) = RouteProjection.pointAt(line, cum, m.coerceIn(0.0, total))
        val ms = r.maneuvers
        val sum = ms.sumOf { it.distanceMeters }
        val k = if (sum > 0) total / sum else 1.0
        val starts = DoubleArray(ms.size).also { var at = 0.0; for (i in ms.indices) { it[i] = at * k; at += ms[i].distanceMeters } }
        val poly = ArrayList<LatLng>()
        for (i in line.indices) if (cum[i] < base) poly += line[i]
        poly += pt(base); poly += pt(rejoin)
        for (i in line.indices) if (cum[i] > rejoin) poly += line[i]
        val cut = rejoin - base
        val inside = ms.indices.filter { i -> i > 0 && ms[i].type != ManeuverType.ARRIVE && starts[i] >= base - 1.0 && starts[i] <= rejoin + 1.0 }
        val placed = ArrayList<Pair<Double, Maneuver>>()
        for (i in ms.indices) if (i !in inside) placed += (if (starts[i] <= base) starts[i] else starts[i] - cut) to ms[i]
        val net = (((bearing(pt(rejoin), pt(rejoin + JOG_SIDE_M)) - bearing(pt(base - JOG_SIDE_M), pt(base))) + 540.0) % 360.0) - 180.0
        if (inside.isNotEmpty() && kotlin.math.abs(net) >= JOG_STRAIGHT_DEG) {
            val last = ms[inside.last()]
            val (type, mod) = turnOf(net)
            placed += base to last.copy(type = type, instruction = say("turn", mod, last.road), instructionNoRoad = say("turn", mod),
                location = pt(base), lanes = emptyList(), laneHint = null, renames = emptyList(), lightsBeforeM = emptyList())
        }
        placed.sortBy { it.first }
        val newTotal = total - cut
        val pace = if (r.distanceMeters > 0) r.durationSeconds / r.distanceMeters else 1.0 / SPEED_MS
        val out = placed.mapIndexed { i, (at, m) ->
            val run = if (m.type == ManeuverType.ARRIVE) 0.0 else ((placed.getOrNull(i + 1)?.first ?: newTotal) - at).coerceAtLeast(0.0)
            m.copy(distanceMeters = run, durationSeconds = run * pace)
        }
        return r.copy(polyline = poly, legs = listOf(r.legs[0].copy(distanceMeters = newTotal, durationSeconds = newTotal * pace, maneuvers = out)),
            distanceMeters = newTotal, durationSeconds = newTotal * pace, drawPolyline = null)
    }

    /** The index ending the piece that starts at [i0]: [PIECE_M] on, moved to the straightest
     *  vertex within [SEAM_SEARCH_M], or the track's end. */
    internal fun seamAfter(t: Track, i0: Int): Int = seamAfter(t.line, t.cum, i0, PIECE_M, SEAM_SEARCH_M)

    internal fun seamAfter(line: List<LatLng>, cum: DoubleArray, i0: Int, pieceM: Double, searchM: Double): Int {
        val target = cum[i0] + pieceM
        if (target >= cum.last() - searchM) return line.size - 1
        var best = -1; var bestTurn = Double.MAX_VALUE
        for (i in i0 + 1 until line.size - 1) {
            if (cum[i] < target - searchM) continue
            if (cum[i] > target + searchM) break
            val turn = kotlin.math.abs(turnDeg(line[i - 1], line[i], line[i + 1]))
            if (turn < bestTurn) { bestTurn = turn; best = i }
        }
        if (best > 0) return best
        var i = i0 + 1; while (i < line.size - 1 && cum[i] < target) i++
        return i
    }

    /** [line] cut into pieces of about [pieceM] ([seamAfter]), as first and last indices. */
    internal fun cuts(line: List<LatLng>, cum: DoubleArray, pieceM: Double, searchM: Double): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i0 = 0
        while (i0 < line.size - 1) { val i1 = seamAfter(line, cum, i0, pieceM, searchM); out += i0 to i1; i0 = i1 }
        return out
    }

    private val SIDED = setOf(
        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
        ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT,
        ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
    )

    /** A turn and the turn within [JOG_M] after it are one movement, by the heading before the
     *  first and after the second: within [JOG_STRAIGHT_DEG] of straight on they are dropped
     *  (crossing a road where the sidewalk jogs), else they become one turn onto the second's road.
     *  A turn or sharp turn the line does not make (under [JOG_STRAIGHT_DEG] across it) is
     *  dropped, and so is a turn within [JOG_M] of the arrival. */
    internal fun withoutJogs(r: Route): Route {
        val line = r.polyline
        if (line.size < 2 || r.legs.size != 1) return r
        val cum = RouteProjection.cumulative(line); val total = cum.last()
        fun pt(m: Double) = RouteProjection.pointAt(line, cum, m.coerceIn(0.0, total))
        val ms = r.maneuvers.toMutableList()
        var changed = false
        var i = 1
        while (i < ms.size - 1) {
            val a = ms[i]; val b = ms[i + 1]
            if (a.type !in SIDED || b.type !in SIDED || a.distanceMeters >= JOG_M) { i++; continue }
            var s = 0.0; for (k in 0 until i) s += ms[k].distanceMeters
            val e = s + a.distanceMeters
            val net = (((bearing(pt(e), pt(e + JOG_SIDE_M)) - bearing(pt(s - JOG_SIDE_M), pt(s))) + 540.0) % 360.0) - 180.0
            val run = a.distanceMeters + b.distanceMeters; val runS = a.durationSeconds + b.durationSeconds
            changed = true
            if (kotlin.math.abs(net) < JOG_STRAIGHT_DEG) {
                val p = ms[i - 1]
                ms[i - 1] = p.copy(distanceMeters = p.distanceMeters + run, durationSeconds = p.durationSeconds + runS)
                ms.removeAt(i); ms.removeAt(i)
                i = maxOf(1, i - 1)
            } else {
                val (type, mod) = turnOf(net)
                ms[i] = b.copy(
                    type = type, instruction = say("turn", mod, b.road), instructionNoRoad = say("turn", mod), location = a.location,
                    distanceMeters = run, durationSeconds = runS, lanes = emptyList(), laneHint = null,
                    renames = b.renames.map { it.copy(atMeters = it.atMeters + a.distanceMeters) }, lightsBeforeM = emptyList(),
                )
                ms.removeAt(i + 1)
            }
        }
        // A turn the line does not make: the matched line kinks where a short way (a bridge drawn
        // at an angle) joins the road, and the matcher says "Turn left" on a straight road. A
        // TURN or SHARP step whose heading [JOG_SIDE_M] before and after it differs by less than
        // [JOG_STRAIGHT_DEG] is dropped; a slight turn, fork or keep is left, since it says which
        // way at a split.
        var k = 1
        while (k < ms.size - 1) {
            val m = ms[k]
            if (m.type !in PHANTOM_TYPES) { k++; continue }
            var s = 0.0; for (j in 0 until k) s += ms[j].distanceMeters
            val net = (((bearing(pt(s), pt(s + JOG_SIDE_M)) - bearing(pt(s - JOG_SIDE_M), pt(s))) + 540.0) % 360.0) - 180.0
            if (kotlin.math.abs(net) >= JOG_STRAIGHT_DEG) { k++; continue }
            val p = ms[k - 1]
            ms[k - 1] = p.copy(distanceMeters = p.distanceMeters + m.distanceMeters, durationSeconds = p.durationSeconds + m.durationSeconds)
            ms.removeAt(k)
            changed = true
        }
        // A turn within [JOG_M] of the arrival is where the match ends, not a turn to make.
        if (ms.size >= 3 && ms[ms.size - 2].type in SIDED && ms[ms.size - 2].distanceMeters < JOG_M) {
            val t = ms.removeAt(ms.size - 2); val p = ms[ms.size - 2]
            ms[ms.size - 2] = p.copy(distanceMeters = p.distanceMeters + t.distanceMeters, durationSeconds = p.durationSeconds + t.durationSeconds)
            changed = true
        }
        if (!changed) return r
        return r.copy(legs = listOf(r.legs[0].copy(maneuvers = ms)))
    }

    /** Steps that claim a real turn, checked against the line by [withoutJogs]. */
    private val PHANTOM_TYPES = setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT)

    /** The step type and phrase modifier for a turn of [deg] (right positive). */
    private fun turnOf(deg: Double): Pair<ManeuverType, String> = when {
        deg >= 120 -> ManeuverType.SHARP_RIGHT to "sharp right"
        deg >= 60 -> ManeuverType.TURN_RIGHT to "right"
        deg > 0 -> ManeuverType.SLIGHT_RIGHT to "slight right"
        deg <= -120 -> ManeuverType.SHARP_LEFT to "sharp left"
        deg <= -60 -> ManeuverType.TURN_LEFT to "left"
        else -> ManeuverType.SLIGHT_LEFT to "slight left"
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
