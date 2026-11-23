package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * A driving route's turns put where its line turns (SPEC 4.2, Steps on the line). Steps made for
 * one line and drawn on another (Google's line named from a match, a stitched stretch) can sit
 * tens of meters from the corner they are about, and a match through a parking lot hops between
 * aisles and adds turns the line does not make. Each left, right or sharp turn is checked where
 * the nav engine places it ([NavEngine.placeSteps]):
 *
 * - The line turns its way ([TURN_DEG] or more, its heading over [LEG_M] after a point against
 *   [LEG_M] before) within [OK_M]: kept.
 * - Else it moves onto the nearest such bend within [MOVE_M] that no other step is on.
 * - Else it is dropped when it names no road and the line does not turn its way even over
 *   [GENTLE_LEG_M] either side (a ramp's gentle curve is a turn too). A turn that names one is
 *   kept. A turn may move past bare turns that are off their bend too, and a bare turn a moved
 *   turn has passed is dropped.
 * - Two turns within [JOG_M] that end within [JOG_STRAIGHT_DEG] of the heading they started on,
 *   neither naming a road, are dropped together.
 * - A turn that names no road, followed within [ONTO_M] by a step that continues onto a named
 *   road, becomes the turn onto that road ("Turn right", then "Continue onto Elm Street").
 *
 * A dropped or moved step's meters and seconds go to the step before it, so the route's totals
 * stay. Turns within [END_M] of either end are left as they are: the bend there cannot be
 * measured.
 */
object StepFit {
    class Result(val route: Route, val moved: Int, val dropped: Int, val jogs: Int, val onto: Int) {
        val changed get() = moved + dropped + jogs + onto > 0
        /** For [Route.made] and the log: counts only. */
        val note get() = "fit=m${moved}d${dropped}j${jogs}o$onto"
    }

    fun fit(route: Route): Result {
        val line = route.polyline
        val unchanged = Result(route, 0, 0, 0, 0)
        if (line.size < 2 || route.maneuvers.size < 3) return unchanged
        val cum = RouteProjection.cumulative(line)
        val total = cum.last()
        if (total < 2 * END_M) return unchanged
        val placed = NavEngine.placeSteps(route, cum)
        // One working list across the legs: a step is moved or dropped only inside its own leg.
        class S(var m: Maneuver, var at: Double, val leg: Int, val first: Boolean)
        val steps = ArrayList<S>()
        var k = 0
        route.legs.forEachIndexed { li, leg -> leg.maneuvers.forEachIndexed { i, m -> steps += S(m, placed[k++], li, i == 0) } }
        // The line's turn right at a point: its heading over the LEG_M after it against the LEG_M
        // before. StepAudit.bendAt reads 8 to 40 m out, which sees a corner from 40 m away.
        fun brgAt(a: Double, b: Double): Double {
            val p = RouteProjection.pointAt(line, cum, a.coerceIn(0.0, total)); val q = RouteProjection.pointAt(line, cum, b.coerceIn(0.0, total))
            return Math.toDegrees(atan2((q.lng - p.lng) * cos(Math.toRadians(p.lat)), q.lat - p.lat))
        }
        fun bend(at: Double, leg: Double = LEG_M): Double {
            var x = brgAt(at, at + leg) - brgAt(at - leg, at)
            while (x > 180) x -= 360
            while (x < -180) x += 360
            return x
        }
        fun right(t: ManeuverType) = t == ManeuverType.TURN_RIGHT || t == ManeuverType.SHARP_RIGHT
        fun checked(s: S) = s.m.type in TURNS && !s.first && s.at > END_M && s.at < total - END_M
        fun bare(m: Maneuver) = m.road.isNullOrBlank() && m.ref.isNullOrBlank()
        fun bendsHisWay(at: Double, wantRight: Boolean, leg: Double = LEG_M): Boolean {
            var o = -OK_M
            while (o <= OK_M) { val b = bend(at + o, leg); if (if (wantRight) b >= TURN_DEG else b <= -TURN_DEG) return true; o += 2.0 }
            return false
        }
        fun prevOf(i: Int): Int { var j = i - 1; while (j >= 0 && steps[j].leg != steps[i].leg) j--; return j }
        /** Step [i] moved to [newAt]: the meters between go to or from the step before it. */
        fun shift(i: Int, newAt: Double) {
            val p = prevOf(i); if (p < 0) return
            val d = newAt - steps[i].at
            val pm = steps[p].m; val sm = steps[i].m
            val pPace = if (pm.distanceMeters > 0) pm.durationSeconds / pm.distanceMeters else 0.0
            val sPace = if (sm.distanceMeters > 0) sm.durationSeconds / sm.distanceMeters else pPace
            // Below zero for now when the turn moved past steps that are dropped next: their meters
            // come back to it then.
            steps[p].m = pm.copy(distanceMeters = pm.distanceMeters + d, durationSeconds = pm.durationSeconds + d * pPace)
            steps[i].m = sm.copy(distanceMeters = sm.distanceMeters - d, durationSeconds = sm.durationSeconds - d * sPace,
                location = RouteProjection.pointAt(line, cum, newAt))
            steps[i].at = newAt
        }
        fun drop(i: Int) {
            val p = prevOf(i); if (p < 0) return
            val pm = steps[p].m; val sm = steps[i].m
            steps[p].m = pm.copy(distanceMeters = pm.distanceMeters + sm.distanceMeters, durationSeconds = pm.durationSeconds + sm.durationSeconds)
            steps.removeAt(i)
        }

        // 1. Each turn on a bend its way: kept, moved onto a free one near, or dropped.
        var moved = 0; var dropped = 0
        val ok = BooleanArray(steps.size) { i -> !checked(steps[i]) || bendsHisWay(steps[i].at, right(steps[i].m.type)) }
        val claims = ArrayList<Double>()
        for (i in steps.indices) if (ok[i] && steps[i].m.type != ManeuverType.DEPART && steps[i].m.type != ManeuverType.ARRIVE) claims += steps[i].at
        val gone = BooleanArray(steps.size)
        var lo = 0.0 // the last kept step's place: a moved turn stays after it
        for (i in steps.indices) {
            if (ok[i]) { lo = steps[i].at; continue }
            val s = steps[i]; val wantRight = right(s.m.type)
            // It may pass a bare turn that is off its bend too (that one moves or goes next), but
            // not one on its bend or one that names a road.
            val hi = (i + 1 until steps.size).firstOrNull { ok[it] || !bare(steps[it].m) }?.let { steps[it].at } ?: total
            var best = Double.NaN; var bestGap = Double.MAX_VALUE
            var o = -MOVE_M
            while (o <= MOVE_M) {
                val x = s.at + o
                if (x > lo + 1.0 && x < hi - 1.0 && x > END_M && x < total - END_M && abs(o) < bestGap) {
                    val b = bend(x)
                    val its = if (wantRight) b >= TURN_DEG else b <= -TURN_DEG
                    if (its && claims.none { abs(it - x) <= CLAIM_M }) { best = x; bestGap = abs(o) }
                }
                o += 2.0
            }
            if (!best.isNaN()) {
                // On the bend's strongest point within 6 m either side.
                var at = best; var top = abs(bend(best))
                var e = -6.0
                while (e <= 6.0) { val b = abs(bend(best + e)); if (b > top) { top = b; at = best + e }; e += 1.0 }
                shift(i, at); claims += at; moved++; lo = at
            } else if (bare(s.m) && (s.at <= lo || !bendsHisWay(s.at, wantRight, GENTLE_LEG_M))) { gone[i] = true; dropped++ }
            else lo = s.at
        }
        for (i in steps.indices.reversed()) if (gone[i]) drop(i)

        // 2. Jogs: two turns within JOG_M that go nowhere, neither naming a road.
        var jogs = 0
        var i = 0
        while (i < steps.size - 1) {
            val a = steps[i]; val b = steps[i + 1]
            if (checked(a) && checked(b) && a.leg == b.leg && b.at - a.at < JOG_M && bare(a.m) && bare(b.m)) {
                var net = brgAt(b.at, b.at + JOG_SIDE_M) - brgAt(a.at - JOG_SIDE_M, a.at)
                while (net > 180) net -= 360
                while (net < -180) net += 360
                if (abs(net) < JOG_STRAIGHT_DEG) { drop(i + 1); drop(i); jogs++; continue }
            }
            i++
        }

        // 3. A bare turn and the road it lands on, said as one.
        var onto = 0
        i = 0
        while (i < steps.size - 1) {
            val a = steps[i]; val b = steps[i + 1]
            if (checked(a) && bare(a.m) && a.leg == b.leg && (b.m.type == ManeuverType.STRAIGHT || b.m.type == ManeuverType.CONTINUE) &&
                !bare(b.m) && b.at - a.at < ONTO_M) {
                val mod = when (a.m.type) {
                    ManeuverType.TURN_LEFT -> "left"; ManeuverType.TURN_RIGHT -> "right"
                    ManeuverType.SHARP_LEFT -> "sharp left"; else -> "sharp right"
                }
                val phrase = app.vela.core.data.RouteGeometry::osrmPhrase
                a.m = a.m.copy(
                    road = b.m.road, ref = b.m.ref,
                    instruction = phrase("turn", mod, instructionRoad(b.m.road, b.m.ref), null, null, null),
                    instructionNoRoad = phrase("turn", mod, null, null, null, null),
                    distanceMeters = a.m.distanceMeters + b.m.distanceMeters, durationSeconds = a.m.durationSeconds + b.m.durationSeconds,
                    renames = a.m.renames + b.m.renames.map { it.copy(atMeters = it.atMeters + a.m.distanceMeters) },
                    lightsBeforeM = a.m.lightsBeforeM,
                )
                steps.removeAt(i + 1)
                onto++
            }
            i++
        }

        val res = Result(route, moved, dropped, jogs, onto)
        if (!res.changed) return unchanged
        val legs = route.legs.mapIndexed { li, leg ->
            leg.copy(maneuvers = steps.filter { it.leg == li }.map { if (it.m.distanceMeters < 0 || it.m.durationSeconds < 0) it.m.copy(distanceMeters = it.m.distanceMeters.coerceAtLeast(0.0), durationSeconds = it.m.durationSeconds.coerceAtLeast(0.0)) else it.m })
        }
        return Result(
            route.copy(legs = legs, made = listOf(route.made, res.note).filter { it.isNotBlank() }.joinToString(";")),
            moved, dropped, jogs, onto,
        )
    }

    /** How many of [route]'s left, right and sharp turns (away from the ends) have no bend their
     *  way within [OK_M]: the turns [fit] would move or drop. For the trip study. */
    internal fun turnsOffBend(route: Route): Int {
        val line = route.polyline
        if (line.size < 2) return 0
        val cum = RouteProjection.cumulative(line); val total = cum.last()
        val placed = NavEngine.placeSteps(route, cum)
        fun brgAt(a: Double, b: Double): Double {
            val p = RouteProjection.pointAt(line, cum, a.coerceIn(0.0, total)); val q = RouteProjection.pointAt(line, cum, b.coerceIn(0.0, total))
            return Math.toDegrees(atan2((q.lng - p.lng) * cos(Math.toRadians(p.lat)), q.lat - p.lat))
        }
        fun bend(at: Double): Double { var x = brgAt(at, at + LEG_M) - brgAt(at - LEG_M, at); while (x > 180) x -= 360; while (x < -180) x += 360; return x }
        var n = 0
        route.maneuvers.forEachIndexed { i, m ->
            val at = placed[i]
            if (i == 0 || m.type !in TURNS || at <= END_M || at >= total - END_M) return@forEachIndexed
            val wantRight = m.type == ManeuverType.TURN_RIGHT || m.type == ManeuverType.SHARP_RIGHT
            var o = -OK_M; var hit = false
            while (o <= OK_M) { val b = bend(at + o); if (if (wantRight) b >= TURN_DEG else b <= -TURN_DEG) { hit = true; break }; o += 2.0 }
            if (!hit) n++
        }
        return n
    }

    private val TURNS = setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT)

    /** A turn is on a bend when the line turns its way by this much... */
    const val TURN_DEG = 30.0
    /** ...between its heading this far before a point and this far after. */
    const val LEG_M = 12.0
    /** ...within this of where the engine places it. */
    const val OK_M = 20.0
    /** A bare turn with no bend near is dropped only when the line does not turn its way over this
     *  much either side of it either: a gentle curve's turn is real. */
    const val GENTLE_LEG_M = 25.0
    /** A turn off its bend moves onto one this near at most. */
    const val MOVE_M = 75.0
    /** A bend another step sits within this of is that step's. */
    const val CLAIM_M = 20.0
    /** Turns this near either end of the line are left alone. */
    const val END_M = 40.0
    /** Two turns this close are one movement... */
    const val JOG_M = 15.0
    /** ...that goes nowhere when it ends within this of its starting heading... */
    const val JOG_STRAIGHT_DEG = 30.0
    /** ...taken over this much line either side. */
    const val JOG_SIDE_M = 25.0
    /** A bare turn takes the road of a "continue onto" step this near after it. */
    const val ONTO_M = 60.0
}
