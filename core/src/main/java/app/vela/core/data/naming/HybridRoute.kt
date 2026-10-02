package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.distanceTo

/**
 * Google's line with the best steps available for each part of it (2026-10-02).
 *
 * Google's route knows about closures and traffic; the open router's steps carry lanes, exit
 * numbers and sign text. Where the two routes run along the same road the open router's maneuvers
 * are kept as they are. Where Google's line leaves the open route (a [Stretch]) the turns come
 * from that piece of Google's line, named from the map tiles ([LineNamer]). Nothing is routed
 * through points, so the result cannot loop or double back, and it is Google's line exactly.
 */
object HybridRoute {
    /** A sample of Google's line this far from the open route is off it. Tight on purpose: at
     *  45 m a frontage road beside a highway counted as the same road, and the open router's
     *  steps for the one would have been read out on the other; at 25 m a parking aisle 18 m
     *  beside a street did (the open router's "Turn left onto G Street" was read out in the lot,
     *  found reading a step list, 2026-10-02). Two drawings of one road are a lane or two apart. */
    const val OFF_M = 15.0
    /** A shorter run off the open route is drawing noise (the other side of a wide junction),
     *  not a different way. It was 120 m, and a different way out of a parking lot or round one
     *  block went unnoticed: the open router's turn where it rejoins was read out on a line that
     *  goes straight there, and Google's own turns were not said (end-to-end study, 2026-10-02). */
    const val MIN_RUN_M = 40.0
    /** Two runs off the open route with less than this of shared road between them are one. */
    const val JOIN_GAP_M = 60.0
    /** A stretch's edge keeps this far from a corner of the line sharper than [EDGE_BEND_DEG]. */
    const val EDGE_CLEAR_M = 60.0
    const val EDGE_BEND_DEG = 35.0
    /** Each stretch reaches this far into the shared road at both ends, so the turn off the
     *  shared road and the turn back onto it belong to the stretch. */
    const val PAD_M = 90.0
    /** A stretch really begins where the two lines part, not where they are already [OFF_M]
     *  apart: an exit ramp runs beside its highway for hundreds of meters first, and the open
     *  router's "take the exit" sits at the fork. Each end is walked back (and forward) to where
     *  the lines are within this of each other, at most [WALK_MAX_M], before the pad. */
    private const val TIGHT_M = 10.0
    private const val WALK_MAX_M = 600.0
    /** An open-router maneuver is carried over only when the open route's own path after it
     *  stays on Google's line: checked every [AGREE_STEP_M] for up to [AGREE_M] of its leg. */
    private const val AGREE_M = 400.0
    private const val AGREE_STEP_M = 40.0
    private const val AGREE_OFF_M = 15.0
    private const val AGREE_BACK_M = 80.0
    private const val AGREE_BACK_STEP_M = 20.0
    /** Two tile-named maneuvers this close with the same road are one (a ramp's two bends). */
    private const val SAME_ROAD_M = 150.0
    private const val STEP_M = 20.0
    /** An open-router maneuver has to sit this close to Google's line to be carried over. */
    private const val SNAP_M = 40.0
    private val HARD_TURNS = setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT)
    private const val FLAT_DEG = 20.0
    private const val FLAT_REACH_M = 40.0
    private const val FLAT_CHECK_FROM_M = 50.0
    private const val PLACE_REACH_M = 40.0
    private const val PLACE_REACH_END_M = 150.0
    private const val TRIP_END_M = 400.0
    /** Two maneuvers this close together are the same junction; the open router's is kept. */
    private const val MERGE_M = 30.0

    /** Meters along Google's line, padded. */
    data class Stretch(val fromM: Double, val toM: Double)

    /** Where [google] leaves [open], as padded and merged stretches along [google]. Empty when
     *  the two are the same way. */
    fun stretches(google: List<LatLng>, open: List<LatLng>): List<Stretch> {
        if (google.size < 2 || open.size < 2) return emptyList()
        val grid = RouteGeometry.SegmentGrid(open, cumulative(open))
        val cum = cumulative(google)
        val total = cum.last()
        val offRuns = ArrayList<Stretch>()
        var start = -1.0
        var m = 0.0
        fun farFrom(at: Double, tol: Double) = grid.along(pointAt(google, cum, at), tol) == null
        while (m <= total) {
            val off = farFrom(m, OFF_M)
            if (off && start < 0) start = m
            if (!off && start >= 0) { offRuns += Stretch(start, m); start = -1.0 }
            m += STEP_M
        }
        if (start >= 0) offRuns += Stretch(start, total)
        // Runs a short way apart are one departure: a line that crosses the open route between
        // two aisles of a parking lot is off it the whole way, not twice for 60 m.
        val joined = ArrayList<Stretch>()
        for (r in offRuns) {
            val last = joined.lastOrNull()
            if (last != null && r.fromM - last.toM <= JOIN_GAP_M) joined[joined.lastIndex] = Stretch(last.fromM, r.toM) else joined += r
        }
        val raw = joined.filter { it.toM - it.fromM >= MIN_RUN_M }
        // Walk each end out to where the two lines actually part and meet again.
        val runs = raw.map { r ->
            var a = r.fromM
            while (a > 0.0 && r.fromM - a < WALK_MAX_M && farFrom(a, TIGHT_M)) a -= STEP_M
            var b = r.toM
            while (b < total && b - r.toM < WALK_MAX_M && farFrom(b, TIGHT_M)) b += STEP_M
            Stretch(a.coerceAtLeast(0.0), b.coerceAtMost(total))
        }
        // Pad, clamp, merge what now touches. An end is never left just before or after a
        // corner of the line: the piece handed to the matcher would begin (or end) AT the turn,
        // which then reads as its start and is not said (a left 2 m into a stretch was lost).
        fun clearOfCorner(at: Double, dir: Int): Double {
            var edge = at
            repeat(3) {
                var x = 0.0
                var corner = -1.0
                while (x <= EDGE_CLEAR_M) {
                    val p = edge - dir * x // looking INTO the stretch from its edge
                    if (p in 0.0..total && kotlin.math.abs(app.vela.core.nav.StepAudit.bendAt(google, cum, p)) >= EDGE_BEND_DEG) { corner = p; break }
                    x += 10.0
                }
                if (corner < 0) return edge
                edge = (corner + dir * EDGE_CLEAR_M).coerceIn(0.0, total)
            }
            return edge
        }
        val out = ArrayList<Stretch>()
        for (r in runs) {
            val a = clearOfCorner((r.fromM - PAD_M).coerceAtLeast(0.0), -1); val b = clearOfCorner((r.toM + PAD_M).coerceAtMost(total), 1)
            val last = out.lastOrNull()
            if (last != null && a <= last.toM) out[out.lastIndex] = Stretch(last.fromM, maxOf(last.toM, b)) else out += Stretch(a, b)
        }
        return out
    }

    /**
     * Null when the open route's maneuver [k] can be read out on Google's line: the open route
     * COMES Google's way into it (the 80 m before it, or its "turn left" where it joins the line
     * from a side street is a turn the line does not make) and GOES Google's way after it (up to
     * 400 m of its leg, the end of the leg always sampled, or it is the open router leaving the
     * line: an exit Google does not take peels away slowly). Otherwise how far back and how far
     * on the first disagreement is, as (back, on), 0 for the side that agrees.
     */
    private fun unvouched(
        openMs: List<Maneuver>, k: Int, here: Double, grid: RouteGeometry.SegmentGrid, openLine: List<LatLng>, openCum: DoubleArray,
    ): Pair<Double, Double>? {
        val man = openMs[k]
        val back = minOf(if (k > 0) openMs[k - 1].distanceMeters else 0.0, AGREE_BACK_M)
        var bk = AGREE_BACK_STEP_M
        while (bk <= back) {
            if (grid.along(pointAt(openLine, openCum, here - bk), AGREE_OFF_M) == null) return bk to 0.0
            bk += AGREE_BACK_STEP_M
        }
        val upTo = minOf(man.distanceMeters, AGREE_M)
        var d = minOf(AGREE_STEP_M, upTo)
        while (d <= upTo && upTo > 0.0) {
            if (grid.along(pointAt(openLine, openCum, here + d), AGREE_OFF_M) == null) return 0.0 to d
            d = if (d < upTo && d + AGREE_STEP_M > upTo) upTo else d + AGREE_STEP_M
        }
        return null
    }

    /**
     * [stretches], widened so that NO open-route step is left that cannot be read out: a step
     * outside every stretch that fails [unvouched] gets a stretch of its own around it, out to
     * where the routes part. Without this such a step was simply dropped and, when both routes
     * make the turn and Google leaves that street 200 m later, nobody said it (end-to-end
     * study, 2026-10-02).
     */
    fun stretchesFor(google: List<LatLng>, open: Route): List<Stretch> {
        val base = stretches(google, open.polyline)
        if (google.size < 2 || open.polyline.size < 2) return base
        val cum = cumulative(google)
        val total = cum.last()
        val grid = RouteGeometry.SegmentGrid(google, cum)
        val openMs = open.maneuvers
        val openCum = cumulative(open.polyline)
        val more = ArrayList<Stretch>(base)
        var openAt = 0.0
        for ((k, man) in openMs.withIndex()) {
            val here = openAt
            openAt += man.distanceMeters
            if (k == 0 || k == openMs.lastIndex) continue
            val a = grid.along(man.location, SNAP_M) ?: continue
            if (base.any { a >= it.fromM && a <= it.toM }) continue
            val (bk, on) = unvouched(openMs, k, here, grid, open.polyline, openCum) ?: continue
            more += Stretch((a - bk - PAD_M).coerceAtLeast(0.0), (a + on + PAD_M).coerceAtMost(total))
        }
        if (more.size == base.size) return base
        val out = ArrayList<Stretch>()
        for (r in more.sortedBy { it.fromM }) {
            val last = out.lastOrNull()
            if (last != null && r.fromM <= last.toM) out[out.lastIndex] = Stretch(last.fromM, maxOf(last.toM, r.toM)) else out += r
        }
        return out
    }

    /** The piece of [line] between [fromM] and [toM] along it. */
    fun slice(line: List<LatLng>, fromM: Double, toM: Double): List<LatLng> {
        val cum = cumulative(line)
        val out = ArrayList<LatLng>()
        out += pointAt(line, cum, fromM)
        for (k in line.indices) if (cum[k] > fromM && cum[k] < toM) out += line[k]
        out += pointAt(line, cum, toM)
        return out
    }

    /**
     * [google] with maneuvers stitched from [open] (outside the stretches) and from [named]
     * (inside them; each list is LineNamer's maneuvers for that stretch's slice, DEPART and ARRIVE
     * included, in order). Null when the result would not start with a departure and end with an
     * arrival, which means a piece could not be placed.
     */
    fun stitch(google: Route, open: Route, named: List<Pair<Stretch, List<Maneuver>>>): Route? {
        val g = google.polyline
        if (g.size < 2 || named.isEmpty()) return null
        val cum = cumulative(g)
        val total = cum.last()
        val grid = RouteGeometry.SegmentGrid(g, cum)
        fun inStretch(a: Double) = named.any { (s, _) -> a >= s.fromM && a <= s.toM }

        data class At(val m: Double, val man: Maneuver, val fromOpen: Boolean)
        val all = ArrayList<At>()
        val openMs = open.maneuvers
        val openLine = open.polyline
        val openCum = cumulative(openLine)
        var openAt = 0.0 // where this maneuver sits along the OPEN route (its steps tile that line)
        for ((k, man) in openMs.withIndex()) {
            val here = openAt
            openAt += man.distanceMeters
            val a = when {
                k == 0 && man.type == ManeuverType.DEPART -> 0.0
                k == openMs.lastIndex && man.type == ManeuverType.ARRIVE -> total
                else -> grid.along(man.location, SNAP_M) ?: continue
            }
            if (inStretch(a)) continue
            // Only a step the open route both arrives at and leaves along Google's line
            // (stretchesFor has already put the others inside a stretch; this is the backstop).
            if (unvouched(openMs, k, here, grid, openLine, openCum) == null) all += At(a, man, true)
        }
        for ((s, ms) in named) {
            var pos = 0.0
            var drift = 0.0   // how far the placed turns have run ahead of the summed step lengths
            var prevLen = 0.0
            var lastPlaced = s.fromM
            for (man in ms) {
                // Where the turn IS on Google's line, when that is near where the steps' lengths
                // put it: the matched path and the line differ in length by a few percent, and
                // adding lengths up put a turn 100 m early by the end of a long stretch.
                // The search is a WINDOW along the line around where the lengths (plus the drift
                // so far) put the turn, sized by the step just driven: the nearest point of the
                // whole line is the wrong pass when the line goes round a block.
                val byLength = s.fromM + pos + drift
                // Wider in the first and last stretch of a trip, where the match is allowed to go
                // round by the street while the line cuts through a lot and the lengths disagree
                // by more. Never before the turn placed just before it: the order is the matcher's.
                val tripEnd = (s.fromM <= 0.0 && byLength < TRIP_END_M) || (s.toM >= total - 1.0 && byLength > total - TRIP_END_M)
                val reach = if (tripEnd) PLACE_REACH_END_M else maxOf(PLACE_REACH_M, prevLen * 0.08)
                val a = (alongNear(g, cum, man.location, maxOf(byLength - reach, lastPlaced), byLength + reach, SNAP_M) ?: byLength).coerceAtLeast(lastPlaced)
                lastPlaced = a
                // A hard turn is said only where Google's line turns. The matcher can open a
                // piece that starts at a junction on the cross street and "turn left" onto the
                // road the line was on all along (seen at the end of an exit ramp, 2026-10-02).
                if (man.type in HARD_TURNS && a > FLAT_CHECK_FROM_M && a < total - FLAT_CHECK_FROM_M) {
                    var most = 0.0
                    var o = -FLAT_REACH_M
                    while (o <= FLAT_REACH_M) { most = maxOf(most, kotlin.math.abs(app.vela.core.nav.StepAudit.bendAt(g, cum, a + o))); o += 10.0 }
                    if (most < FLAT_DEG) { pos += man.distanceMeters; prevLen = man.distanceMeters; continue }
                }
                drift += a - byLength
                prevLen = man.distanceMeters
                pos += man.distanceMeters
                // The slice's own ends are real only where the slice starts or ends the trip.
                if (man.type == ManeuverType.DEPART && s.fromM > 0.0) continue
                if (man.type == ManeuverType.ARRIVE && s.toM < total) continue
                all += At(if (man.type == ManeuverType.ARRIVE) total else a, man, false)
            }
        }
        all.sortBy { it.m }
        val kept = ArrayList<At>()
        for (x in all) {
            val last = kept.lastOrNull()
            // One junction told by both sources. Two turns from the SAME source that close are
            // two turns (right, then left onto the street 20 m on) and both stay.
            if (last != null && x.fromOpen != last.fromOpen && x.m - last.m < MERGE_M && x.man.type != ManeuverType.ARRIVE && last.man.type != ManeuverType.DEPART) {
                if (x.fromOpen && !last.fromOpen) kept[kept.lastIndex] = x
                continue
            }
            // A ramp's two bends, both named for the road it reaches: one instruction.
            if (last != null && !x.fromOpen && !last.fromOpen && x.m - last.m < SAME_ROAD_M &&
                x.man.road != null && x.man.road == last.man.road && x.man.type == last.man.type && x.man.type !in HARD_TURNS) continue
            kept += x
        }
        if (kept.size < 2 || kept.first().man.type != ManeuverType.DEPART || kept.last().man.type != ManeuverType.ARRIVE) return null
        val dur = google.durationSeconds
        val out = kept.mapIndexed { k, x ->
            val len = if (k == kept.lastIndex) 0.0 else (kept[k + 1].m - x.m).coerceAtLeast(0.0)
            x.man.copy(
                location = if (x.man.type == ManeuverType.ARRIVE) g.last() else if (k == 0) g.first() else x.man.location,
                distanceMeters = len,
                durationSeconds = if (total > 0.0) dur * len / total else 0.0,
                // A name change the open router placed farther along its own leg than this leg now runs.
                renames = x.man.renames.filter { it.atMeters < len },
            )
        }
        return google.copy(
            legs = listOf(RouteLeg(google.distanceMeters, google.durationSeconds, google.durationInTrafficSeconds, out)),
            provisional = false,
            abbreviatedSteps = false,
            source = RouteSource.GOOGLE_HYBRID,
        )
    }

    /** Along-distance of the point of [line] nearest [p] between [lo] and [hi] meters along it,
     *  or null when nothing there is within [tolM]. */
    private fun alongNear(line: List<LatLng>, cum: DoubleArray, p: LatLng, lo: Double, hi: Double, tolM: Double): Double? {
        val k = kotlin.math.cos(Math.toRadians(p.lat))
        var best: Double? = null
        var bestD = tolM
        for (i in 0 until line.size - 1) {
            if (cum[i + 1] < lo) continue
            if (cum[i] > hi) break
            val a = line[i]; val b = line[i + 1]
            val bx = (b.lng - a.lng) * k; val by = b.lat - a.lat
            val px = (p.lng - a.lng) * k; val py = p.lat - a.lat
            val len2 = bx * bx + by * by
            val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val along = (cum[i] + (cum[i + 1] - cum[i]) * t).coerceIn(lo, hi)
            val q = pointAt(line, cum, along)
            val d = q.distanceTo(p)
            if (d < bestD) { bestD = d; best = along }
        }
        return best
    }

    private fun cumulative(line: List<LatLng>): DoubleArray {
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + line[i - 1].distanceTo(line[i])
        return cum
    }

    private fun pointAt(line: List<LatLng>, cum: DoubleArray, m: Double): LatLng {
        if (m <= 0.0) return line.first()
        if (m >= cum.last()) return line.last()
        var i = 1
        while (i < cum.size - 1 && cum[i] < m) i++
        val seg = cum[i] - cum[i - 1]
        val f = if (seg <= 0.0) 0.0 else (m - cum[i - 1]) / seg
        return LatLng(line[i - 1].lat + (line[i].lat - line[i - 1].lat) * f, line[i - 1].lng + (line[i].lng - line[i - 1].lng) * f)
    }
}
