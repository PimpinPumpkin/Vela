package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.data.ValhallaRouter
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TrafficSpan
import app.vela.core.model.distanceTo
import app.vela.core.nav.RouteProjection
import app.vela.core.nav.StepAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * A turn on Google's line that OpenStreetMap forbids (issue #947).
 *
 * Google's line is followed because it knows closures and traffic, and it is still sometimes
 * wrong about a turn ban that a sign and OpenStreetMap both state. Where Google turns at a junction
 * the open route also reaches but leaves another way ([candidates]), both open routers are asked to
 * drive just that turn: from [PROBE_M] before the corner to [PROBE_M] after it on Google's line,
 * with the headings pinned and the snap held to [PROBE_RADIUS_M]. A legal turn comes back about
 * 2 x [PROBE_M] long; a banned one comes back as the way round. Only when both routers go round is
 * the ban taken as real, and only when the way round costs at most [SPLICE_MAX_EXTRA_M] and
 * [SPLICE_MAX_EXTRA_S] does it replace that piece of Google's line ([splice]). A dearer one keeps
 * Google's turn and marks it ([mark], `Maneuver.mayBeBanned`): the turn card says it may not be
 * allowed. A ban once found is remembered for the session ([known]), and a replan during a drive
 * (a reroute) replaces a remembered ban up to [REROUTE_MAX_EXTRA_M], so a driver who obeyed the
 * sign is never sent back to it. A missing road in OpenStreetMap fails the snap, so it never reads
 * as a ban.
 */
object TurnBans {
    /** A corner this sharp on Google's line is a turn worth asking about. */
    const val CORNER_DEG = 35.0
    /** The open route reaches the same junction when it passes this close to the corner. */
    const val SAME_JUNCTION_M = 15.0
    /** Arriving headings this close are the same approach; leaving headings farther apart are
     *  another way out. */
    const val SAME_HEADING_DEG = 30.0
    /** Headings are read over this much line either side of the corner. */
    private const val HEADING_M = 30.0
    /** Corners nearer a trip end than this are places, not turns. */
    private const val END_CLEAR_M = 60.0
    /** At most this many turns are asked about per route, nearest the start first. */
    const val MAX_CANDIDATES = 3

    /** Each probe starts and ends this far from the corner along Google's line. */
    const val PROBE_M = 40.0
    /** A probe end farther than this from its road is not the same road. */
    const val PROBE_RADIUS_M = 15.0
    /** The heading tolerance at each probe end. */
    const val PROBE_HEADING_TOL_DEG = 30
    /** A probe at most this much longer than the line it replaces drove the turn. */
    private const val LEGAL_SLACK_M = 70.0
    /** A probe at least this much longer than the line went round. */
    private const val ROUND_MIN_EXTRA_M = 120.0
    /** A probe end farther than this from where it was asked is not trusted for a ban. */
    private const val SNAP_TRUST_M = 12.0

    /** The way round replaces Google's turn only when it costs no more than this. */
    const val SPLICE_MAX_EXTRA_M = 1_500.0
    const val SPLICE_MAX_EXTRA_S = 150.0
    /** On a reroute any way round up to this replaces a banned turn, whatever the time. */
    const val REROUTE_MAX_EXTRA_M = 10_000.0
    /** A turn on the final route this close to a kept ban's corner is the banned one. */
    const val MARK_NEAR_M = 30.0

    /** Probes give up after this, and Google's line stands. */
    const val PROBE_TIMEOUT_MS = 1_500L
    const val PROBE_TIMEOUT_URGENT_MS = 800L

    /** Off by an adb dial (`turnBansOff`), for comparing routes with and without. */
    @Volatile var off = false
    /** [SPLICE_MAX_EXTRA_M] unless an adb dial (`turnBanSpliceMaxM`) sets another, to see a kept
     *  and marked turn on a ban whose way round is short. */
    @Volatile var spliceMaxM = SPLICE_MAX_EXTRA_M

    data class Candidate(
        val atM: Double, val corner: LatLng, val aM: Double, val bM: Double, val a: LatLng, val b: LatLng,
        val headingA: Double, val headingB: Double,
        /** A ban already found this session at this junction and these headings: no probe. */
        val knownProbe: Probe? = null,
    )

    /** One router's answer for one turn: its line, length and time, and how far its ends snapped. */
    data class Probe(val line: List<LatLng>, val distanceM: Double, val durationS: Double, val snapM: Double)

    enum class Verdict { LEGAL, ROUND, UNKNOWN }

    /** What the check found and did, for the route's `made` note. [kept] are the corners of bans
     *  left on Google's line because the way round costs too much. */
    data class Outcome(
        val candidates: Int, val banned: Int, val spliced: Int, val late: Boolean, val extraM: Double,
        val kept: List<LatLng> = emptyList(), val known: Int = 0,
    ) {
        fun note(): String? = if (candidates == 0) null else
            "bans=$banned/$candidates" + (if (known > 0) ";banKnown=$known" else "") +
                (if (spliced > 0) ";banSplice=$spliced;banExtra=${extraM.toInt()}m" else "") +
                (if (kept.isNotEmpty()) ";banKept=${kept.size}" else "") + (if (late) ";banLate" else "")
    }

    /** A ban found this session: the junction, the headings through it, and OSRM's way round. */
    private data class Known(val corner: LatLng, val headingIn: Double, val headingOut: Double, val probe: Probe)
    private val known = ArrayList<Known>()

    /**
     * Turns on [google] worth probing: a corner of at least [CORNER_DEG] where [open] passes within
     * [SAME_JUNCTION_M], arriving the same way and leaving another way. Nearest the start first, at
     * most [MAX_CANDIDATES].
     */
    fun candidates(google: List<LatLng>, open: List<LatLng>): List<Candidate> {
        if (google.size < 2 || open.size < 2) return emptyList()
        val gc = RouteProjection.cumulative(google)
        val oc = RouteProjection.cumulative(open)
        val total = gc.last()
        if (total < 2 * END_CLEAR_M + 2 * PROBE_M) return emptyList()
        val og = RouteGeometry.SegmentGrid(open, oc)
        val out = ArrayList<Candidate>()
        for (x in corners(google, gc)) {
            val c = RouteProjection.pointAt(google, gc, x)
            val gIn = heading(RouteProjection.pointAt(google, gc, x - HEADING_M), c)
            val gOut = heading(c, RouteProjection.pointAt(google, gc, (x + HEADING_M).coerceAtMost(total)))
            // Every time the open route passes the junction: it can turn away there and come back
            // through it later from another side (the way round a banned turn often does).
            val leftAnotherWay = og.allAlong(c, SAME_JUNCTION_M).any { o ->
                val oIn = heading(RouteProjection.pointAt(open, oc, (o - HEADING_M).coerceAtLeast(0.0)), RouteProjection.pointAt(open, oc, o))
                val oOut = heading(RouteProjection.pointAt(open, oc, o), RouteProjection.pointAt(open, oc, (o + HEADING_M).coerceAtMost(oc.last())))
                diff(gIn, oIn) <= SAME_HEADING_DEG && diff(gOut, oOut) > SAME_HEADING_DEG
            }
            if (!leftAnotherWay) continue
            out += candidateAt(google, gc, x)
            if (out.size >= MAX_CANDIDATES) break
        }
        return out
    }

    /** Corners of [google] that are bans already found this session, arriving and leaving the
     *  way the ban was found. Found without the open route: after a reroute it no longer passes. */
    fun knownOn(google: List<LatLng>): List<Candidate> {
        val ks = synchronized(known) { known.toList() }
        if (ks.isEmpty() || google.size < 2) return emptyList()
        val gc = RouteProjection.cumulative(google)
        val total = gc.last()
        if (total < 2 * END_CLEAR_M + 2 * PROBE_M) return emptyList()
        val out = ArrayList<Candidate>()
        for (x in corners(google, gc)) {
            val c = RouteProjection.pointAt(google, gc, x)
            val gIn = heading(RouteProjection.pointAt(google, gc, x - HEADING_M), c)
            val gOut = heading(c, RouteProjection.pointAt(google, gc, (x + HEADING_M).coerceAtMost(total)))
            val k = ks.firstOrNull {
                it.corner.distanceTo(c) <= SAME_JUNCTION_M && diff(it.headingIn, gIn) <= SAME_HEADING_DEG && diff(it.headingOut, gOut) <= SAME_HEADING_DEG
            } ?: continue
            out += candidateAt(google, gc, x).copy(knownProbe = k.probe)
        }
        return out
    }

    /** Remembered for the session: [corner] is banned arriving [headingIn] and leaving [headingOut]. */
    fun remember(corner: LatLng, headingIn: Double, headingOut: Double, probe: Probe) = synchronized(known) {
        if (known.none { it.corner.distanceTo(corner) <= SAME_JUNCTION_M && diff(it.headingIn, headingIn) <= SAME_HEADING_DEG && diff(it.headingOut, headingOut) <= SAME_HEADING_DEG }) {
            known += Known(corner, headingIn, headingOut, probe)
            if (known.size > 32) known.removeAt(0)
        }
    }

    /** Forget every remembered ban (tests). */
    fun forget() = synchronized(known) { known.clear() }

    /** Corners of [line]: local peaks of the bend of at least [CORNER_DEG], every 5 m, kept
     *  [END_CLEAR_M] from the trip's ends and [PROBE_M] x 2 apart. */
    private fun corners(line: List<LatLng>, cum: DoubleArray): List<Double> {
        val total = cum.last()
        val xs = generateSequence(END_CLEAR_M) { it + 5.0 }.takeWhile { it <= total - END_CLEAR_M }.toList()
        val bends = xs.map { abs(StepAudit.bendAt(line, cum, it)) }
        val out = ArrayList<Double>()
        for (i in xs.indices) {
            val b = bends[i]
            if (b < CORNER_DEG) continue
            if ((i > 0 && bends[i - 1] > b) || (i < xs.lastIndex && bends[i + 1] >= b)) continue
            if (out.isNotEmpty() && xs[i] - out.last() < 2 * PROBE_M) continue
            out += xs[i]
        }
        return out
    }

    private fun candidateAt(line: List<LatLng>, cum: DoubleArray, x: Double): Candidate {
        val total = cum.last()
        val aM = x - PROBE_M; val bM = x + PROBE_M
        val a = RouteProjection.pointAt(line, cum, aM); val b = RouteProjection.pointAt(line, cum, bM)
        val ha = heading(RouteProjection.pointAt(line, cum, aM - 10.0), RouteProjection.pointAt(line, cum, aM + 10.0))
        val hb = heading(RouteProjection.pointAt(line, cum, bM - 10.0), RouteProjection.pointAt(line, cum, (bM + 10.0).coerceAtMost(total)))
        return Candidate(x, RouteProjection.pointAt(line, cum, x), aM, bM, a, b, ha, hb)
    }

    /** [route] with each turn within [MARK_NEAR_M] of a kept ban's corner marked as maybe not
     *  allowed. Only turns: a straight-on step there is not the banned move. */
    fun mark(route: Route, kept: List<LatLng>): Route {
        if (kept.isEmpty()) return route
        val turns = setOf(
            app.vela.core.model.ManeuverType.TURN_LEFT, app.vela.core.model.ManeuverType.TURN_RIGHT,
            app.vela.core.model.ManeuverType.SLIGHT_LEFT, app.vela.core.model.ManeuverType.SLIGHT_RIGHT,
            app.vela.core.model.ManeuverType.SHARP_LEFT, app.vela.core.model.ManeuverType.SHARP_RIGHT,
            app.vela.core.model.ManeuverType.UTURN, app.vela.core.model.ManeuverType.FORK_LEFT,
            app.vela.core.model.ManeuverType.FORK_RIGHT, app.vela.core.model.ManeuverType.KEEP_LEFT,
            app.vela.core.model.ManeuverType.KEEP_RIGHT, app.vela.core.model.ManeuverType.RAMP_LEFT,
            app.vela.core.model.ManeuverType.RAMP_RIGHT,
        )
        var any = false
        val legs = route.legs.map { leg ->
            leg.copy(maneuvers = leg.maneuvers.map { m ->
                if (m.type in turns && kept.any { it.distanceTo(m.location) <= MARK_NEAR_M }) { any = true; m.copy(mayBeBanned = true) } else m
            })
        }
        return if (any) route.copy(legs = legs) else route
    }

    /** What one router's answer says about a turn whose line between the probe ends is [lineM]. */
    fun verdict(p: Probe?, lineM: Double): Verdict = when {
        p == null -> Verdict.UNKNOWN
        p.distanceM <= lineM + LEGAL_SLACK_M -> Verdict.LEGAL
        p.distanceM >= lineM + ROUND_MIN_EXTRA_M && p.snapM <= SNAP_TRUST_M -> Verdict.ROUND
        else -> Verdict.UNKNOWN
    }

    /**
     * [route] with its line between [aM] and [bM] replaced by [piece], its length and time raised
     * by the way round, and its traffic spans moved along with the meters after the splice.
     */
    fun splice(route: Route, aM: Double, bM: Double, piece: Probe): Route {
        val line = route.polyline
        val cum = RouteProjection.cumulative(line)
        val out = ArrayList<LatLng>()
        for (k in line.indices) if (cum[k] < aM) out += line[k]
        out += piece.line
        for (k in line.indices) if (cum[k] > bM) out += line[k]
        val extraM = (piece.distanceM - (bM - aM)).coerceAtLeast(0.0)
        val lineS = if (route.distanceMeters > 0) route.durationSeconds * (bM - aM) / route.distanceMeters else 0.0
        val extraS = (piece.durationS - lineS).coerceAtLeast(0.0)
        // A span's part before the splice stays, its part after moves on by the way round's extra
        // meters, and the replaced piece has none.
        val spans = route.trafficSpans.flatMap { s ->
            val end = s.startMeters + s.lengthMeters
            buildList {
                if (s.startMeters < aM) add(s.copy(lengthMeters = minOf(end, aM) - s.startMeters))
                if (end > bM) {
                    val from = maxOf(s.startMeters, bM)
                    add(TrafficSpan(s.level, from + extraM, end - from))
                }
            }
        }
        var legStart = 0.0
        var raised = false
        val legs = route.legs.map { leg ->
            val here = legStart
            legStart += leg.distanceMeters
            if (!raised && aM >= here && aM < legStart) {
                raised = true
                leg.copy(
                    distanceMeters = leg.distanceMeters + extraM,
                    durationSeconds = leg.durationSeconds + extraS,
                    durationInTrafficSeconds = leg.durationInTrafficSeconds?.plus(extraS),
                )
            } else leg
        }
        return route.copy(
            polyline = out,
            legs = legs,
            distanceMeters = route.distanceMeters + extraM,
            durationSeconds = route.durationSeconds + extraS,
            durationInTrafficSeconds = route.durationInTrafficSeconds?.plus(extraS),
            trafficSpans = spans,
        )
    }

    /** Probes answered this session, by junction and the two headings: a reroute asks again. */
    private val seen = object : LinkedHashMap<String, Pair<Verdict, Probe?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Verdict, Probe?>>?) = size > 64
    }

    private fun key(c: Candidate) = "%.4f,%.4f,%d,%d".format(java.util.Locale.ROOT, c.corner.lat, c.corner.lng, (c.headingA / 10).toInt(), (c.headingB / 10).toInt())

    /**
     * [google] with each banned turn the open route avoids replaced by the way round, when that is
     * cheap, or null when nothing was asked. The routers are asked in parallel and the whole check
     * gives up after [timeoutMs] with Google's line as it was.
     */
    suspend fun check(http: OkHttpClient, google: Route, open: Route, timeoutMs: Long, reroute: Boolean = false): Pair<Route, Outcome>? {
        if (off) return null
        val fresh = candidates(google.polyline, open.polyline)
        val again = knownOn(google.polyline).filter { k -> fresh.none { abs(it.atM - k.atM) < 2 * PROBE_M } }
        val cands = (fresh + again).sortedBy { it.atM }
        if (cands.isEmpty()) return null
        val answers = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                cands.map { c ->
                    async<Pair<Candidate, Pair<Verdict, Probe?>>>(Dispatchers.IO) {
                        c.knownProbe?.let { return@async c to (Verdict.ROUND to it) }
                        synchronized(seen) { seen[key(c)] }?.let { return@async c to it }
                        val lineM = c.bM - c.aM
                        val osrm = RouteGeometry.probeTurn(http, c.a, c.headingA, c.b, c.headingB, timeoutMs)
                        val vo = verdict(osrm, lineM)
                        // The second router is asked only when the first went round.
                        val both = if (vo != Verdict.ROUND) vo else {
                            val vv = verdict(ValhallaRouter.probeTurn(http, c.a, c.headingA, c.b, c.headingB, timeoutMs), lineM)
                            if (vv == Verdict.ROUND) Verdict.ROUND else Verdict.UNKNOWN
                        }
                        val ans = both to osrm
                        if (osrm != null) synchronized(seen) { seen[key(c)] = ans }
                        c to ans
                    }
                }.awaitAll()
            }
        }
        // Known bans still apply when the routers are late.
        val got = answers ?: cands.filter { it.knownProbe != null }.map { it to (Verdict.ROUND to it.knownProbe) }
        val banned = got.filter { it.second.first == Verdict.ROUND }
        val gc = RouteProjection.cumulative(google.polyline)
        for ((c, ans) in banned) {
            val p = ans.second ?: continue
            val total = gc.last()
            remember(
                c.corner,
                heading(RouteProjection.pointAt(google.polyline, gc, c.atM - HEADING_M), c.corner),
                heading(c.corner, RouteProjection.pointAt(google.polyline, gc, (c.atM + HEADING_M).coerceAtMost(total))),
                p,
            )
        }
        // Splice from the end back, so the meters of the earlier turns stay where they were.
        var r = google
        var spliced = 0
        var extra = 0.0
        val kept = ArrayList<LatLng>()
        for ((c, ans) in banned.sortedByDescending { it.first.atM }) {
            val p = ans.second ?: continue
            val extraM = p.distanceM - (c.bM - c.aM)
            val lineS = if (google.distanceMeters > 0) google.durationSeconds * (c.bM - c.aM) / google.distanceMeters else 0.0
            val cheap = if (reroute) extraM <= REROUTE_MAX_EXTRA_M else extraM <= spliceMaxM && p.durationS - lineS <= SPLICE_MAX_EXTRA_S
            if (!cheap) { kept += c.corner; continue }
            r = splice(r, c.aM, c.bM, p)
            spliced++
            extra += extraM
        }
        return r to Outcome(cands.size, banned.size, spliced, late = answers == null, extraM = extra, kept = kept, known = again.size)
    }

    private fun heading(a: LatLng, b: LatLng): Double {
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun diff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
