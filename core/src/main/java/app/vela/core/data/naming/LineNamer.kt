package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoadRename
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Turn-by-turn for a route whose LINE is right but whose steps are not (issue #478): Google's
 * keyless steps are abbreviated, and forcing the open router through points on Google's line adds
 * crossings and double-backs. Here the line is kept exactly, the turns come from its own bends and
 * each stretch takes the name of the map street it runs along ([NamedLine]s from the vector tiles).
 * Nothing is routed, so nothing can loop. What it cannot give is lane guidance: that lives on the
 * router's junctions, not in the tiles. An exit's number and signs come from Google's own exit at
 * the same place ([Route.exitSigns]).
 */
object LineNamer {
    private const val STEP_M = 8.0
    private const val HEADING_SPAN_M = 16.0 // each sample's heading is measured over +-16 m
    private const val TURN_WINDOW_M = 32.0 // a turn is the heading change across +-32 m
    private const val TURN_MIN_DEG = 35.0
    private const val TURN_SAME_NAME_DEG = 70.0 // a bend that keeps the name is only a turn past this
    private const val EVENT_GAP_M = 30.0
    private const val ALIGN_DEG = 35.0
    private const val SHORT_RUN_M = 40.0
    private const val LOOK_M = 400.0
    /** Highway classes: joined by an on-ramp, left by an exit. */
    private val FAST = setOf("motorway", "trunk")
    /** A sample is on a link road when a link running its way is within [LINK_OFF_M] and no
     *  named street is more than [LINK_TIE_M] nearer (a numbered link is in the names too). A
     *  run of such samples, gaps of [LINK_GAP] samples bridged, is a ramp when it is
     *  [RAMP_MIN_M] long and a highway is at one end; a shorter link is a slip lane at an
     *  at-grade junction. The roads at its ends are looked for within twice [LINK_LOOK_M], and
     *  the side of an exit with no bend is the heading change across [LINK_LOOK_M] either side. */
    private const val LINK_OFF_M = 12.0
    private const val LINK_TIE_M = 1.0
    private const val LINK_LOOK_M = 64.0
    private const val LINK_GAP = 2
    private const val RAMP_MIN_M = 80.0
    /** A bend under this on a highway with no other road after it is a curve, and a link that
     *  merges onto a highway under this is a merge: both quiet (Organic Maps: one way out and
     *  under 50 degrees says nothing). */
    private const val CURVE_DEG = 50.0
    /** Two same-way turns this close onto the same street are one turn (OsmAnd: 50 m). */
    private const val SPLIT_TURN_M = 50.0
    /** One of Google's exits ([Route.exitSigns]) is the tiles' exit when its point is this close
     *  to the line and this close along it to where the line moves onto the link. */
    private const val SIGN_OFF_M = 20.0
    private const val SIGN_ALONG_M = 150.0
    private const val SIGN_LATE_M = 400.0
    private val PLAIN = setOf("turn", "continue")
    private val ELEVATED_ROADS = setOf("motorway", "trunk", "primary", "secondary", "tertiary")
    /** `transportation_name` classes a car does not drive on. */
    private val NOT_DRIVEN = setOf("path", "rail", "transit", "aerialway", "pier", "raceway", "busway", "bus_guideway", "bridleway", "cycleway")
    /** Strict mode: a turn names its street only when the line stays on it this long after the
     *  turn, and a ramp or a rename only when the road it reaches runs this long. */
    // Vars, not consts, so the naming study (NamingStudyTest) can sweep them.
    internal var STRICT_RUN_M = 60.0
    internal var STRICT_FAR_RUN_M = 100.0
    /** Strict mode's own matching distance (the lenient one is 30 m driving). */
    internal var STRICT_MAX_OFF_M = 12.0
    /** Strict mode: two differently named streets this close in distance to a sample, both
     *  running its way, leave the sample unnamed. The farther one counts past
     *  [STRICT_MAX_OFF_M] too. */
    internal var AMBIGUOUS_M = 12.0
    /** Part of the line that must carry a name before the result is trusted. */
    const val MIN_NAMED_SHARE = 0.6
    /** Lower on foot: lanes and paths are often unnamed, and an unnamed stretch reads "Turn left"
     *  from the open router too (a Dhaka walk matched 59%). */
    const val MIN_NAMED_SHARE_WALK = 0.5
    /** Share of the last named line that matched a street, for the diagnostics line. */
    @Volatile var lastNamedShare = 0.0
    /** Desk hook (NamingStudyTest): one line per bend with what decided it. Never set in the app. */
    internal var onBend: ((String) -> Unit)? = null

    /**
     * [route] named from [lines], or null when too little of it lines up with a named street.
     * Google's keyless step LIST is not used: it is short, and its steps are placed by their
     * share of the trip, a kilometer off on a highway. Its exits are, at their own points.
     */
    /**
     * [strict] is for driving guidance stitched into a route (HybridRoute), where a wrong street
     * name is worse than none: "Turn right" sends nobody the wrong way, "Turn right onto Smith
     * Street" at John Street does. In strict mode the line is never refused for being thinly
     * named (its turns still come out, bare), a sample between two differently named streets
     * takes neither name, and a turn or a rename carries a name only when the line then stays on
     * that street for a real distance.
     */
    fun name(route: Route, lines: List<NamedLine>, mode: TravelMode, strict: Boolean = false): Route? {
        val poly = route.polyline
        if (poly.size < 2) return null
        val s = resample(poly)
        if (s.size < 3) return null
        val total = s.last().m
        val maxOff = if (strict) STRICT_MAX_OFF_M else if (mode == TravelMode.WALK) 25.0 else 30.0
        val (links, streets) = lines.partition { it.link }
        // On foot, a road flyover's name never labels the street underneath (issue #478, Dhaka).
        // In a car, a footpath, track bed or busway beside the road never names it.
        val usable = when (mode) {
            TravelMode.WALK -> streets.filterNot { it.bridge && it.cls in ELEVATED_ROADS }
            TravelMode.DRIVE -> streets.filterNot { it.cls in NOT_DRIVEN }
            else -> streets
        }
        val idx = SegIndex(usable)

        // 1. A name per sample: the nearest street within maxOff that runs the same way.
        val names = s.map { p -> idx.nearestAligned(p.pt, p.brg, maxOff, strict) }
        val named = names.count { it != null }.toDouble() / names.size
        lastNamedShare = named
        if (!strict && named < (if (mode == TravelMode.WALK) MIN_NAMED_SHARE_WALK else MIN_NAMED_SHARE)) return null

        // Samples on a link road, for telling a ramp from an at-grade turn onto a highway.
        val onLink = if (mode != TravelMode.DRIVE || links.isEmpty()) BooleanArray(s.size) else SegIndex(links).let { li ->
            BooleanArray(s.size) { k ->
                val d = li.alignedDist(s[k].pt, s[k].brg)
                // A link with a name or route number is in the names too, on the same line.
                d < LINK_OFF_M && d <= idx.alignedDist(s[k].pt, s[k].brg) + LINK_TIE_M
            }
        }

        // 2. Runs of one name; short runs (a cross street at a junction) and gaps absorbed.
        val runName = smoothRuns(s, names)

        // Strict: a name counts at sample k only when the same name holds for [minM] onward.
        fun solid(k: Int, minM: Double): NameRun? {
            val r = runName.getOrNull(k) ?: return null
            if (!strict) return r
            var j = k
            while (j + 1 < s.size && runName[j + 1]?.name == r.name) j++
            return if (s[j].m - s[k].m >= minM || j == s.lastIndex && s[j].m - s[k].m >= minM / 2) r else null
        }

        // 3. Turns: local peaks of the heading change across +-TURN_WINDOW_M.
        val w = (TURN_WINDOW_M / STEP_M).toInt().coerceAtLeast(1)
        val delta = DoubleArray(s.size) { i ->
            if (i < w || i + w >= s.size) 0.0 else RouteGeometry.bearingDelta(s[i - w].brg, s[i + w].brg)
        }
        val turns = ArrayList<Int>()
        for (i in s.indices) {
            val a = abs(delta[i])
            if (a < TURN_MIN_DEG) continue
            if ((i > 0 && abs(delta[i - 1]) > a) || (i + 1 < s.size && abs(delta[i + 1]) >= a)) continue
            if (turns.isNotEmpty() && s[i].m - s[turns.last()].m < EVENT_GAP_M) {
                if (a > abs(delta[turns.last()])) turns[turns.lastIndex] = i
                continue
            }
            turns += i
        }

        // 4. Events: a turn is announced when the name changes across it or it bends hard; a name
        // change with no turn is a rename folded into the step before (as the open router's are).
        data class Ev(val i: Int, val type: ManeuverType, val mod: String?, val road: String?, val osrm: String, val exitNo: String? = null, val dest: String? = null)
        val events = ArrayList<Ev>()
        // The nearest named run within LOOK_M either side: a ramp or a link is unnamed in the
        // tiles, and the road it leads to is what the instruction should name.
        fun runAroundAt(i: Int, dir: Int): Int? {
            var k = (i + dir * w).coerceIn(0, s.lastIndex)
            while (k in s.indices && abs(s[k].m - s[i].m) <= LOOK_M) {
                if ((if (dir > 0) solid(k, STRICT_FAR_RUN_M) else runName[k]) != null) return k
                k += dir
            }
            return null
        }
        fun runAround(i: Int, dir: Int): NameRun? = runAroundAt(i, dir)?.let { runName[it] }
        val drive = mode == TravelMode.DRIVE
        val near = (LINK_LOOK_M / STEP_M).toInt()
        fun ramp(a: Double) = if (a > 0) ManeuverType.RAMP_RIGHT else ManeuverType.RAMP_LEFT
        fun linkEvent(i: Int, a: Double, ls: LinkStretch) = when (ls.kind) {
            LinkKind.ON -> Ev(i, ramp(a), classify(a).second, ls.road, "on ramp")
            LinkKind.OFF -> Ev(i, ramp(a), classify(a).second, ls.road, "off ramp")
            LinkKind.STAY -> Ev(i, if (a > 0) ManeuverType.KEEP_RIGHT else ManeuverType.KEEP_LEFT, if (a > 0) "slight right" else "slight left", ls.road, "fork")
        }
        // The named road the line is on just past a link's end in [dir], skipping the link's own
        // name when it carries one.
        fun beside(k: Int, dir: Int): NameRun? {
            var m = k + dir
            while (m in s.indices && abs(m - k) <= 2 * near) {
                if (!onLink[m]) (if (dir > 0) solid(m, STRICT_RUN_M) else runName[m])?.let { return it }
                m += dir
            }
            return null
        }
        fun linkStretches(): List<LinkStretch> {
            val out = ArrayList<LinkStretch>()
            var k = 0
            while (k < s.size) {
                if (!onLink[k]) { k++; continue }
                var j = k; var g = k + 1
                while (g < s.size) { if (onLink[g]) { j = g; g++ } else if (g - j <= LINK_GAP) g++ else break }
                if (s[j].m - s[k].m >= RAMP_MIN_M) {
                    val from = beside(k, -1); val to = beside(j, 1)
                    val toFast = to?.cls in FAST
                    when {
                        // Off a highway and back onto it: a link between its express and local
                        // lanes, a keep rather than an exit.
                        from?.cls in FAST && to != null && to.name == from?.name -> out += LinkStretch(k, j, LinkKind.STAY, to.name, true)
                        from?.cls in FAST -> out += LinkStretch(k, j, LinkKind.OFF, to?.name, toFast)
                        toFast -> out += LinkStretch(k, j, LinkKind.ON, to?.name, true)
                    }
                }
                k = j + 1
            }
            return out
        }

        // Link roads that join or leave a highway, said once each, as a router that reads the
        // junctions says them: the exit where the line leaves the highway (bend or no bend), the
        // ramp where it leaves the street, nothing along the link or where it merges, and a plain
        // turn where it ends at a street.
        val stretches = if (drive) linkStretches() else emptyList()
        onBend?.let { cb -> stretches.forEach { cb("link ${it.kind} ${s[it.from].m.toInt()}..${s[it.to].m.toInt()} m to ${it.road} fast=${it.toFast}") } }
        // A bend belongs to a link when the line is on it within a turn window either side.
        fun stretchAt(i: Int) = stretches.firstOrNull { i + w >= it.from && i - w <= it.to }
        val announced = HashSet<LinkStretch>()

        for (i in turns) {
            // Names come from right at the turn; the look-around only finds the highway a ramp joins
            // or leaves (an unnamed campus path must not borrow a street 300 m away).
            val before = runName.getOrNull((i - w).coerceAtLeast(0))
            val after = solid((i + w).coerceAtMost(s.lastIndex), STRICT_RUN_M)
            val beforeFar = runAround(i, -1)
            val afterFar = runAround(i, 1)
            val a = delta[i]
            val same = before != null && before.name == after?.name
            val ls = stretchAt(i)
            onBend?.invoke("bend ${s[i].m.toInt()} m ${a.toInt()} deg | before ${before?.name}/${before?.cls} after ${after?.name}/${after?.cls} | " +
                "far ${beforeFar?.name}/${beforeFar?.cls} -> ${afterFar?.name}/${afterFar?.cls} | link ${ls?.kind} ${ls?.let { "${s[it.from].m.toInt()}..${s[it.to].m.toInt()}" }}")
            if (ls != null) {
                // A bend onto the link (off it a turn window before) is where it is said; a bend
                // with the line on the link both sides is a curve of the ramp, and a bend off it
                // at the far end is a plain turn onto the street it meets.
                val atStart = ls !in announced && i - w < ls.from
                // A turn onto a named street that the link then leaves is that turn; the link is
                // said where the line moves onto it.
                val ontoStreet = after != null && after.cls !in FAST && after.name != ls.road
                if (atStart && !ontoStreet) {
                    announced += ls
                    events += linkEvent(i, a, ls)
                    continue
                }
                if (!atStart) {
                    // A merge onto a highway is quiet unless it bends like a turn (a ramp ending
                    // at a junction on an at-grade highway).
                    val atEnd = i + w > ls.to
                    if (!atEnd || abs(a) < (if (ls.toFast) CURVE_DEG else TURN_MIN_DEG)) continue
                    // A link onto a highway that ends in a turn is a turn lane: the turn is the step.
                    if (ls.kind == LinkKind.ON) announced += ls
                    val (type, mod) = classify(a)
                    events += Ev(i, type, mod, after?.name ?: ls.road, osrmFor(type))
                    continue
                }
            }
            if (same && abs(a) < TURN_SAME_NAME_DEG) continue
            if (before == null && after == null && abs(a) < 45.0) continue
            val bIn = drive && beforeFar?.cls in FAST; val aIn = drive && afterFar?.cls in FAST
            // A bend in a highway that leads onto no other road is a curve.
            if (bIn && abs(a) < CURVE_DEG && (after == null || same)) continue
            val (type, mod) = classify(a)
            // A gentle split between two highways is a keep. A bend that keeps the name is a
            // "continue". A bend onto or off a highway with no link road there is an at-grade
            // junction and says "Turn left onto", as the open router does.
            events += when {
                bIn && aIn && abs(a) < 55.0 -> Ev(i, if (a > 0) ManeuverType.KEEP_RIGHT else ManeuverType.KEEP_LEFT, if (a > 0) "slight right" else "slight left", afterFar?.name, "fork")
                same -> Ev(i, type, mod, after?.name, "continue")
                else -> Ev(i, type, mod, after?.name, osrmFor(type))
            }
        }
        // A link with no bend where it starts (an exit that peels off gently) is said where the
        // line moves onto it, on the side it moves to.
        for (ls in stretches) {
            if (ls in announced || ls.from < w || ls.from + w > s.lastIndex) continue
            val a = RouteGeometry.bearingDelta(s[(ls.from - near).coerceAtLeast(0)].brg, s[(ls.from + near).coerceAtMost(s.lastIndex)].brg)
            events += linkEvent(ls.from, if (a == 0.0) 1.0 else a.coerceIn(-54.0, 54.0), ls)
        }
        events.sortBy { it.i }
        // One turn read as two (a wide corner, a jog onto the same street): two plain turns the
        // same way within [SPLIT_TURN_M] whose second goes onto the street the first did are one
        // turn of their summed angle, as OsmAnd merges them. Two bends that both keep the name
        // are a hairpin, not a U-turn, and stay as they are.
        var k = 0
        while (k + 1 < events.size) {
            val e1 = events[k]; val e2 = events[k + 1]
            val plain = e1.osrm in PLAIN && e2.osrm in PLAIN && (e1.osrm == "turn" || e2.osrm == "turn")
            val a1 = delta[e1.i]; val a2 = delta[e2.i]
            if (plain && a1 * a2 > 0 && s[e2.i].m - s[e1.i].m < SPLIT_TURN_M && e2.road != null && e2.road == e1.road) {
                val (type, mod) = classify((a1 + a2).coerceIn(-180.0, 180.0))
                events[k] = Ev(e1.i, type, mod, e2.road, osrmFor(type))
                events.removeAt(k + 1)
            } else k++
        }
        // The tiles say where an exit is but not its number or signs; Google's exit at the same
        // place does, and which side it leaves on. Each of Google's exits goes to the tiles' exit
        // nearest it along the line. A tiles' exit found up to [SIGN_LATE_M] past it is the same
        // exit seen late (a long exit lane hugs the highway) and moves back to Google's point.
        // Where the tiles have no step near it at all, Google's exit is said as it is: the tiles
        // missed the link road, and an exit nobody says is the worse miss.
        if (drive && route.exitSigns.isNotEmpty()) {
            val placed = route.exitSigns.mapNotNull { sg ->
                val k = s.indices.minByOrNull { s[it].pt.distanceTo(sg.at) } ?: return@mapNotNull null
                if (s[k].pt.distanceTo(sg.at) <= SIGN_OFF_M) k to sg else null
            }.sortedBy { it.first }
            fun signed(e: Ev, sg: app.vela.core.model.ExitSign, at: Int): Ev {
                val right = sg.left?.not() ?: (e.type == ManeuverType.RAMP_RIGHT)
                val agrees = (e.type == ManeuverType.RAMP_RIGHT) == right
                return e.copy(
                    i = at, type = if (right) ManeuverType.RAMP_RIGHT else ManeuverType.RAMP_LEFT,
                    mod = if (agrees) e.mod else if (right) "slight right" else "slight left",
                    exitNo = sg.number, dest = sg.dest,
                )
            }
            val signedAt = HashSet<Int>()
            for ((k, sg) in placed) {
                val at = s[k].m
                val same = events.indices.filter { it !in signedAt && events[it].osrm == "off ramp" && abs(s[events[it].i].m - at) <= SIGN_ALONG_M }
                    .minByOrNull { abs(s[events[it].i].m - at) }
                val how = when {
                    same != null -> { events[same] = signed(events[same], sg, events[same].i); signedAt += same; "matched" }
                    events.any { abs(s[it.i].m - at) <= SIGN_ALONG_M } -> "left alone, a step is here"
                    // At an end of the line it may be the next piece's exit (a hybrid stretch is
                    // a piece of Google's line, and the open router's steps go on from its ends).
                    k < w || k > s.lastIndex - w -> "left alone, at an end of the line"
                    else -> {
                        val next = events.indices.firstOrNull { s[events[it].i].m > at }
                        if (next != null && next !in signedAt && events[next].osrm == "off ramp" && s[events[next].i].m - at <= SIGN_LATE_M) {
                            events[next] = signed(events[next], sg, k); signedAt += next; "moved back from ${s[events[next].i].m.toInt()} m"
                        } else {
                            val a = sg.left?.let { if (it) -1.0 else 1.0 }
                                ?: RouteGeometry.bearingDelta(s[(k - near).coerceAtLeast(0)].brg, s[(k + near).coerceAtMost(s.lastIndex)].brg).let { if (it == 0.0) 1.0 else it }
                            events += Ev(k, ramp(a), if (a > 0) "slight right" else "slight left", null, "off ramp", sg.number, sg.dest)
                            events.sortBy { it.i }
                            signedAt.clear(); events.indices.filterTo(signedAt) { events[it].exitNo != null || events[it].dest != null }
                            "added"
                        }
                    }
                }
                onBend?.invoke("google exit ${at.toInt()} m ${sg.number} -> ${sg.dest} left=${sg.left}: $how")
            }
        }

        // 5. Maneuvers, with the renames between them.
        val dur = route.durationSeconds.takeIf { it > 0 } ?: 0.0
        fun share(fromM: Double, toM: Double) = if (total > 0) dur * (toM - fromM) / total else 0.0
        val renameAt = ArrayList<Pair<Double, NameRun>>()
        for (i in 1 until s.size) {
            val r = runName[i] ?: continue
            val prev = runName[i - 1]
            if (prev != null && prev.name != r.name && solid(i, STRICT_FAR_RUN_M) != null && events.none { abs(s[it.i].m - s[i].m) < EVENT_GAP_M }) {
                renameAt += s[i].m to r
            }
        }
        val out = ArrayList<Maneuver>()
        val starts = listOf(0.0) + events.map { s[it.i].m }
        val ends = events.map { s[it.i].m } + total
        fun renamesIn(a: Double, b: Double) = renameAt.filter { it.first > a && it.first < b }
            .map { RoadRename(it.first - a, it.second.name, it.second.ref) }
        val first = runName.firstOrNull { it != null }
        out += Maneuver(
            ManeuverType.DEPART, RouteGeometry.osrmPhrase("depart", null, app.vela.core.nav.instructionRoad(first?.name, first?.ref), null, null, null), s[0].pt,
            ends[0] - starts[0], share(starts[0], ends[0]), road = first?.name, ref = first?.ref,
            renames = renamesIn(starts[0], ends[0]),
            instructionNoRoad = RouteGeometry.osrmPhrase("depart", null, null, null, null, null),
        )
        for ((k, e) in events.withIndex()) {
            val a = starts[k + 1]; val b = ends[k + 1]
            val run = runName.getOrNull((e.i + w).coerceAtMost(s.lastIndex)) ?: if (e.osrm == "on ramp" || e.osrm == "fork") runAround(e.i, 1) else null
            out += Maneuver(
                e.type,
                RouteGeometry.osrmPhrase(e.osrm, e.mod, app.vela.core.nav.instructionRoad(e.road, run?.ref), e.dest, e.exitNo, null),
                s[e.i].pt, b - a, share(a, b), road = e.road, ref = run?.ref,
                renames = renamesIn(a, b),
                instructionNoRoad = RouteGeometry.osrmPhrase(e.osrm, e.mod, null, e.dest, e.exitNo, null),
            )
        }
        out += Maneuver(ManeuverType.ARRIVE, RouteGeometry.osrmPhrase("arrive", null, null, null, null, null), poly.last(), 0.0, 0.0)
        return route.copy(
            legs = listOf(RouteLeg(route.distanceMeters, route.durationSeconds, route.durationInTrafficSeconds, out)),
            provisional = false,
            abbreviatedSteps = false,
            source = RouteSource.GOOGLE_LINE_NAMED,
        )
    }

    private enum class LinkKind { ON, OFF, STAY }
    /** A link road the line runs on from sample [from] to [to], leaving a highway ([LinkKind.OFF])
     *  or joining one ([LinkKind.ON]), or leaving one and coming back to it ([LinkKind.STAY]).
     *  [road] is the road it leads to, [toFast] whether that is a highway. */
    private data class LinkStretch(val from: Int, val to: Int, val kind: LinkKind, val road: String?, val toFast: Boolean)

    private fun osrmFor(t: ManeuverType) = when (t) {
        ManeuverType.UTURN -> "continue"
        else -> "turn"
    }

    internal fun classify(a: Double): Pair<ManeuverType, String> {
        val side = if (a > 0) "right" else "left"
        val r = a > 0
        return when {
            abs(a) >= 165.0 -> ManeuverType.UTURN to "uturn"
            abs(a) >= 135.0 -> (if (r) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT) to "sharp $side"
            abs(a) >= 55.0 -> (if (r) ManeuverType.TURN_RIGHT else ManeuverType.TURN_LEFT) to side
            else -> (if (r) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT) to "slight $side"
        }
    }

    data class NameRun(val name: String, val ref: String?, val cls: String?)

    /** A route number worth a shield. The tiles also carry heritage designations as refs ("US 40
     *  Historic" on a downtown street), which are not what any sign on the road says. */
    private fun shieldRef(ref: String?): String? = ref?.takeUnless { it.contains("historic", ignoreCase = true) }

    private fun smoothRuns(s: List<Sample>, names: List<NamedLine?>): List<NameRun?> {
        val raw = names.map { it?.let { l -> NameRun(l.name, shieldRef(l.ref), l.cls) } }.toMutableList()
        // Collapse runs shorter than SHORT_RUN_M into the longer neighbor (a cross street's name
        // picked up for a few samples at a junction, or a short gap).
        repeat(3) {
            var i = 0
            while (i < raw.size) {
                var j = i
                while (j + 1 < raw.size && raw[j + 1] == raw[i]) j++
                val len = s[j].m - s[i].m + STEP_M
                if (len < SHORT_RUN_M && (i > 0 || j < raw.lastIndex)) {
                    val left = if (i > 0) raw[i - 1] else null
                    val right = if (j < raw.lastIndex) raw[j + 1] else null
                    val fill = left ?: right
                    if (fill != null && fill != raw[i]) for (k in i..j) raw[k] = fill
                }
                i = j + 1
            }
        }
        return raw
    }

    data class Sample(val pt: LatLng, val m: Double, var brg: Double = 0.0)

    private fun resample(poly: List<LatLng>): List<Sample> {
        val out = ArrayList<Sample>()
        var acc = 0.0
        out += Sample(poly[0], 0.0)
        var next = STEP_M
        for ((a, b) in poly.zipWithNext()) {
            val d = a.dist(b)
            if (d <= 0.0) continue
            while (acc + d >= next) {
                val t = (next - acc) / d
                out += Sample(LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t), next)
                next += STEP_M
            }
            acc += d
        }
        if (acc - out.last().m > 1.0) out += Sample(poly.last(), acc)
        val span = (HEADING_SPAN_M / STEP_M).toInt().coerceAtLeast(1)
        for (i in out.indices) {
            val a = out[(i - span).coerceAtLeast(0)].pt
            val b = out[(i + span).coerceAtMost(out.lastIndex)].pt
            out[i].brg = a.bearing(b)
        }
        return out
    }

    /** Segments of the named lines, bucketed on a ~50 m grid for the nearest-aligned search. */
    private class SegIndex(lines: List<NamedLine>) {
        private data class Seg(val a: LatLng, val b: LatLng, val brg: Double, val line: NamedLine)
        private val cell = 50.0
        private val grid = HashMap<Long, MutableList<Seg>>()
        private val lat0 = lines.firstOrNull()?.points?.firstOrNull()?.lat ?: 0.0
        private val kx = 111_320.0 * cos(Math.toRadians(lat0))
        private fun key(gx: Int, gy: Int) = (gx.toLong() shl 32) xor (gy.toLong() and 0xffffffffL)
        init {
            for (l in lines) for ((a, b) in l.points.zipWithNext()) {
                val seg = Seg(a, b, a.bearing(b), l)
                val x0 = (minOf(a.lng, b.lng) * kx / cell).toInt(); val x1 = (maxOf(a.lng, b.lng) * kx / cell).toInt()
                val y0 = (minOf(a.lat, b.lat) * 111_320.0 / cell).toInt(); val y1 = (maxOf(a.lat, b.lat) * 111_320.0 / cell).toInt()
                if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 400) continue
                for (gx in x0..x1) for (gy in y0..y1) grid.getOrPut(key(gx, gy)) { ArrayList() } += seg
            }
        }
        fun nearestAligned(p: LatLng, brg: Double, maxOff: Double, strict: Boolean = false): NamedLine? {
            val gx = (p.lng * kx / cell).toInt(); val gy = (p.lat * 111_320.0 / cell).toInt()
            // The nearest street, and the nearest with ANOTHER name running the same way (a frontage
            // road beside a highway, the next street of a tight grid): close enough, and the sample
            // is unsure. Both are taken over every segment and maxOff is applied last, so a street
            // past maxOff still counts as the other one whatever order the segments come in.
            var best: NamedLine? = null; var bestD = Double.MAX_VALUE
            var otherD = Double.MAX_VALUE
            for (dx in -1..1) for (dy in -1..1) for (seg in grid[key(gx + dx, gy + dy)].orEmpty()) {
                val diff = abs(RouteGeometry.bearingDelta(brg, seg.brg)).let { if (it > 90.0) 180.0 - it else it }
                if (diff > ALIGN_DEG) continue
                val d = segDist(p, seg.a, seg.b)
                if (d < bestD) {
                    if (best != null && best.name != seg.line.name) otherD = minOf(otherD, bestD)
                    bestD = d; best = seg.line
                } else if (best != null && seg.line.name != best.name) otherD = minOf(otherD, d)
            }
            if (bestD >= maxOff) return null
            if (strict && otherD - bestD < AMBIGUOUS_M) return null
            return best
        }
        /** Meters to the nearest line running [brg]'s way near [p], or infinity past one cell. */
        fun alignedDist(p: LatLng, brg: Double): Double {
            val gx = (p.lng * kx / cell).toInt(); val gy = (p.lat * 111_320.0 / cell).toInt()
            var bestD = Double.POSITIVE_INFINITY
            for (dx in -1..1) for (dy in -1..1) for (seg in grid[key(gx + dx, gy + dy)].orEmpty()) {
                val diff = abs(RouteGeometry.bearingDelta(brg, seg.brg)).let { if (it > 90.0) 180.0 - it else it }
                if (diff <= ALIGN_DEG) bestD = minOf(bestD, segDist(p, seg.a, seg.b))
            }
            return bestD
        }
        private fun segDist(p: LatLng, a: LatLng, b: LatLng): Double {
            val ax = a.lng * kx; val ay = a.lat * 111_320.0
            val bx = b.lng * kx - ax; val by = b.lat * 111_320.0 - ay
            val px = p.lng * kx - ax; val py = p.lat * 111_320.0 - ay
            val l = bx * bx + by * by
            val t = if (l == 0.0) 0.0 else ((px * bx + py * by) / l).coerceIn(0.0, 1.0)
            return hypot(px - t * bx, py - t * by)
        }
    }
}

private fun LatLng.dist(o: LatLng): Double {
    val dy = (lat - o.lat) * 111_320.0
    val dx = (lng - o.lng) * 111_320.0 * cos(Math.toRadians(lat))
    return hypot(dx, dy)
}

private fun LatLng.bearing(o: LatLng): Double {
    val dy = o.lat - lat
    val dx = (o.lng - lng) * cos(Math.toRadians(lat))
    return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
}
