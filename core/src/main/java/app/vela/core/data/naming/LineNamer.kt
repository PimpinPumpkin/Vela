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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Turn-by-turn for a route whose LINE is right but whose steps are not (issue #478): Google's
 * keyless steps are abbreviated, and forcing the open router through points on Google's line adds
 * crossings and double-backs. Here the line is kept exactly, the turns come from its own bends and
 * each stretch takes the name of the map street it runs along ([NamedLine]s from the vector tiles).
 * Nothing is routed, so nothing can loop. What it cannot give is lane guidance or sign destinations:
 * those live on the router's junctions, not in the tiles.
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
    private val ELEVATED_ROADS = setOf("motorway", "trunk", "primary", "secondary", "tertiary")
    /** Strict mode: a turn names its street only when the line stays on it this long after the
     *  turn, and a ramp or a rename only when the road it reaches runs this long. */
    private const val STRICT_RUN_M = 60.0
    private const val STRICT_FAR_RUN_M = 100.0
    /** Strict mode: two differently named streets this close in distance to a sample, both
     *  running its way, leave the sample unnamed. */
    private const val AMBIGUOUS_M = 12.0
    /** Part of the line that must carry a name before the result is trusted. */
    const val MIN_NAMED_SHARE = 0.6
    /** Lower on foot: lanes and paths are often unnamed, and an unnamed stretch reads "Turn left"
     *  from the open router too (a Dhaka walk matched 59%). */
    const val MIN_NAMED_SHARE_WALK = 0.5
    /** Share of the last named line that matched a street, for the diagnostics line. */
    @Volatile var lastNamedShare = 0.0

    /**
     * [route] named from [lines], or null when too little of it lines up with a named street.
     * Google's own step positions are not used: its keyless steps sit at the start of the stretch
     * before the maneuver, a kilometer early on a highway ramp.
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
        val maxOff = if (mode == TravelMode.WALK) 25.0 else 30.0
        // On foot, a road flyover's name never labels the street underneath (issue #478, Dhaka).
        val usable = if (mode == TravelMode.WALK) lines.filterNot { it.bridge && it.cls in ELEVATED_ROADS } else lines
        val idx = SegIndex(usable)

        // 1. A name per sample: the nearest street within maxOff that runs the same way.
        val names = s.map { p -> idx.nearestAligned(p.pt, p.brg, maxOff, strict) }
        val named = names.count { it != null }.toDouble() / names.size
        lastNamedShare = named
        if (!strict && named < (if (mode == TravelMode.WALK) MIN_NAMED_SHARE_WALK else MIN_NAMED_SHARE)) return null

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
        data class Ev(val i: Int, val type: ManeuverType, val mod: String?, val road: String?, val osrm: String)
        val events = ArrayList<Ev>()
        // The nearest named run within LOOK_M either side: a ramp or a link is unnamed in the
        // tiles, and the road it leads to is what the instruction should name.
        fun runAround(i: Int, dir: Int): NameRun? {
            var k = (i + dir * w).coerceIn(0, s.lastIndex)
            while (k in s.indices && abs(s[k].m - s[i].m) <= LOOK_M) {
                (if (dir > 0) solid(k, STRICT_FAR_RUN_M) else runName[k])?.let { return it }
                k += dir
            }
            return null
        }
        for (i in turns) {
            // Names come from right at the turn; the look-around only finds the highway a ramp joins
            // or leaves (an unnamed campus path must not borrow a street 300 m away).
            val before = runName.getOrNull((i - w).coerceAtLeast(0))
            val after = solid((i + w).coerceAtMost(s.lastIndex), STRICT_RUN_M)
            val beforeFar = runAround(i, -1)
            val afterFar = runAround(i, 1)
            val a = delta[i]
            val same = before != null && before == after
            if (same && abs(a) < TURN_SAME_NAME_DEG) continue
            if (before == null && after == null && abs(a) < 45.0) continue
            val (type, mod) = classify(a)
            // Highways from the tiles' road class: joining one is a ramp, leaving one an exit, and a
            // gentle split between two is a keep. A bend that keeps the name is a "continue".
            val fast = setOf("motorway", "trunk")
            val drive = mode == TravelMode.DRIVE
            val bIn = drive && beforeFar?.cls in fast; val aIn = drive && afterFar?.cls in fast
            events += when {
                aIn && !bIn -> Ev(i, if (a > 0) ManeuverType.RAMP_RIGHT else ManeuverType.RAMP_LEFT, mod, afterFar?.name, "on ramp")
                bIn && !aIn -> Ev(i, if (a > 0) ManeuverType.RAMP_RIGHT else ManeuverType.RAMP_LEFT, mod, after?.name ?: afterFar?.name, "off ramp")
                bIn && aIn && abs(a) < 55.0 -> Ev(i, if (a > 0) ManeuverType.KEEP_RIGHT else ManeuverType.KEEP_LEFT, if (a > 0) "slight right" else "slight left", afterFar?.name, "fork")
                same -> Ev(i, type, mod, after?.name, "continue")
                else -> Ev(i, type, mod, after?.name, osrmFor(type))
            }
        }
        events.sortBy { it.i }

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
            ManeuverType.DEPART, RouteGeometry.osrmPhrase("depart", null, first?.name, null, null, null), s[0].pt,
            ends[0] - starts[0], share(starts[0], ends[0]), road = first?.name, ref = first?.ref,
            renames = renamesIn(starts[0], ends[0]),
            instructionNoRoad = RouteGeometry.osrmPhrase("depart", null, null, null, null, null),
        )
        for ((k, e) in events.withIndex()) {
            val a = starts[k + 1]; val b = ends[k + 1]
            val run = runName.getOrNull((e.i + w).coerceAtMost(s.lastIndex)) ?: if (e.osrm == "on ramp" || e.osrm == "fork") runAround(e.i, 1) else null
            out += Maneuver(
                e.type,
                RouteGeometry.osrmPhrase(e.osrm, e.mod, e.road, null, null, null),
                s[e.i].pt, b - a, share(a, b), road = e.road, ref = run?.ref,
                renames = renamesIn(a, b),
                instructionNoRoad = RouteGeometry.osrmPhrase(e.osrm, e.mod, null, null, null, null),
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
            var best: NamedLine? = null; var bestD = maxOff
            // The nearest street with ANOTHER name running the same way (a frontage road beside a
            // highway, the next street of a tight grid): close enough, and the sample is unsure.
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
            if (strict && best != null && otherD - bestD < AMBIGUOUS_M) return null
            return best
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
