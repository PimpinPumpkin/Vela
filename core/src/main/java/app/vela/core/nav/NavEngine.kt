package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.distanceTo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure turn-by-turn logic: given a [Route], the previous [NavState] and a fresh
 * location, return the next state plus any [NavEvent]s (voice lines, reroute
 * request, arrival). Pure and side-effect free so it unit-tests without Android
 * — the ViewModel feeds it the location stream and performs the events.
 */
object NavEngine {
    private const val OFF_ROUTE_M = 45.0
    private const val OFF_ROUTE_HITS = 4      // debounce GPS jitter before rerouting
    private const val ARRIVE_RADIUS_M = 25.0
    private const val ARRIVE_PROX_M = 40.0    // crow-flies arrival fallback (dest snapped to the road; lots/driveways)
    private const val DEST_ZONE_M = 150.0     // no rerouting this close to the destination (arrival territory)
    private const val ON_ROUTE_M = 60.0       // within this of the windowed route → keep tracking progress
    private const val STOP_ON_ROUTE_M = 150.0 // a waypoint farther than this from the line isn't on this route
    private const val PASSED_SLACK_M = 75.0   // this far PAST a maneuver = it happened during a gap → advance silently
    private const val REACQUIRE_JUMP_M = 1_500.0 // a global re-acquire jumping farther than this needs persistence

    private fun round50(m: Double) = kotlin.math.round(m / 50.0) * 50.0
    private fun round10(m: Double) = kotlin.math.round(m / 10.0) * 10.0

    /** Remaining travel time from the remaining STEPS (pro-rated current leg), scaled by the
     *  route's live-traffic ratio. The old `remaining / whole-route-average-speed` read "4 min"
     *  for a 15-minute downtown tail after a fast freeway (and poisoned the faster-route
     *  comparison, which measures candidates against this number). Falls back to the average
     *  when the steps don't carry usable durations (Google's abbreviated fallback). */
    private fun remainingDuration(
        route: Route,
        maneuvers: List<Maneuver>,
        stepIndex: Int,
        distToNext: Double,
        remaining: Double,
        approachLegM: Double, // geometric length of the leg being driven (resolved positions)
    ): Double {
        val base = route.durationSeconds
        val stepDurSum = maneuvers.sumOf { it.durationSeconds }
        if (route.polyline.size < 2 || base <= 0.0 || stepDurSum < base * 0.7) return remaining / avgSpeed(route)
        // Time to reach the target = the approach leg's own pace over what's left of it…
        val approachLeg = maneuvers.getOrNull(stepIndex - 1)
        val approach = if (approachLeg != null && approachLegM > 1.0) {
            (distToNext / approachLegM).coerceIn(0.0, 1.0) * approachLeg.durationSeconds
        } else {
            distToNext / avgSpeed(route)
        }
        // …plus every whole leg after the target (durationSeconds = travel AFTER maneuver k).
        var rest = 0.0
        for (k in stepIndex until maneuvers.lastIndex) rest += maneuvers[k].durationSeconds
        val ratio = (route.durationInTrafficSeconds ?: base) / base
        return (approach + rest) * ratio
    }

    fun update(
        route: Route,
        state: NavState,
        loc: LatLng,
        imperial: Boolean = false,
        // Live GPS speed (m/s) — scales the prompt/turn-now distances with how fast the driver is
        // actually moving and gates off-route counting while stationary. null (tests, replays
        // without speed, unknown) keeps the legacy fixed distances + counts as "moving".
        speedMps: Double? = null,
        // "Stationary" floor for the off-route gate + arrival clause, MODE-AWARE (NavSession
        // passes it): 2 m/s for driving, ~0.6 for walking — a walker's 1.4 m/s must count as
        // moving or pedestrian rerouting never fires and walkers "arrive" 50 m early.
        movingFloorMps: Double = 2.0,
    ): Pair<NavState, List<NavEvent>> {
        val events = mutableListOf<NavEvent>()
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty() || state.arrived) return state to events

        // Forward progress along the route (monotonic). Project the fix onto the polyline
        // within a window around how far we'd already travelled — NOT globally — so a route
        // that passes near itself (switchback / cloverleaf / parallel return leg) can't make
        // "remaining" collapse by matching a far leg. Only re-acquire globally when we've
        // clearly left the window (a reroute or a big GPS gap); when genuinely off-route we
        // hold the last progress rather than snapping it to a wrong leg. Also produced here:
        // [offDist], the perpendicular distance of the projection we ADOPTED (or the best seen
        // while holding) — the off-route check below keys on it, NOT on the whole-polyline
        // minimum, so "near the RETURN leg of an out-and-back" can't mask a genuine exit.
        val cum = cumulative(route.polyline)
        val total = cum.lastOrNull() ?: 0.0
        var reacquireHits = state.reacquireHits
        val traveled: Double
        val offDist: Double
        if (route.polyline.size < 2) {
            traveled = 0.0
            offDist = 0.0
        } else {
            val (wM, wD) = projectAlong(route.polyline, cum, loc, state.traveledM - 60.0, state.traveledM + 600.0)
            if (wD <= ON_ROUTE_M && state.traveledM <= total) {
                traveled = maxOf(state.traveledM, wM)
                offDist = wD
                reacquireHits = 0
            } else {
                // Re-acquire globally, but prefer the candidate NEAREST our last progress: a plain
                // nearest-perpendicular global search on a route that reuses the same asphalt
                // (out-and-back, divided highway, cloverleaf return leg) matches the OTHER pass
                // about half the time — teleporting `traveled` miles ahead, which the monotonic
                // ratchet then locks in for the rest of the drive. The along-distance penalty makes
                // any same-perpendicular tie resolve to the near pass; a genuine far re-entry still
                // wins because nothing near the anchor matches at all. A BIG along-jump must also
                // PERSIST (a couple of fixes) before we adopt it — a single accepted outlier landing
                // near a far leg would otherwise teleport progress, firing every stop cue it passes
                // (permanently) and skipping turns; a genuine gap (tunnel) keeps producing the same
                // far match and gets adopted on the 3rd fix.
                val (gM, gD) = projectNearAnchor(route.polyline, cum, loc, state.traveledM)
                val bigJump = kotlin.math.abs(gM - state.traveledM) > REACQUIRE_JUMP_M && gD >= 20.0
                if (gD <= ON_ROUTE_M && (!bigJump || reacquireHits >= 2)) {
                    traveled = gM
                    offDist = gD
                    reacquireHits = 0
                } else {
                    traveled = state.traveledM.coerceIn(0.0, total)
                    offDist = minOf(wD, gD)
                    // CONSECUTIVE persistence: a plausible far candidate counts up, any fix with
                    // no candidate resets — else isolated outliers minutes apart could sum to the
                    // bar and the third-ever outlier would still teleport progress.
                    reacquireHits = if (gD <= ON_ROUTE_M) reacquireHits + 1 else 0
                }
            }
        }
        val remaining = (total - traveled).coerceAtLeast(0.0)

        // Off-route: the adopted-projection distance, debounced over several fixes. NOT counted
        // while stationary — red-light multipath drift toward a parallel street must not reroute
        // a parked car (Google visibly refuses to reroute while stationary). The stationary floor
        // is MODE-AWARE ([movingFloorMps]): a walker's 1.4 m/s must count as moving or pedestrian
        // rerouting is dead. Unknown speed counts as moving so tests/replays keep old behaviour.
        val moving = (speedMps ?: 99.0) >= movingFloorMps
        val offHits = when {
            offDist <= OFF_ROUTE_M -> 0
            !moving -> state.offRouteHits
            else -> state.offRouteHits + 1
        }
        val offRoute = offHits >= OFF_ROUTE_HITS
        // Consecutive on-corridor+moving fixes — the SUSTAINED "back on the line" signal. offRoute clears
        // on ONE fix within OFF_ROUTE_M, which is too weak to justify abandoning an in-flight reroute (a
        // single spurious graze on a parallel/overlapping leg would kill a legitimate reroute). This counts
        // UP only while genuinely on-corridor and moving, and resets the instant we're off — so NavSession's
        // back-on-course discard can require a real, multi-fix rejoin, not a one-frame coincidence.
        val onRouteStreak = when {
            offDist > OFF_ROUTE_M -> 0
            !moving -> state.onRouteStreak
            else -> state.onRouteStreak + 1
        }
        // No rerouting in the destination zone (parked 50 m short of the snapped endpoint is an
        // ARRIVAL, not off-route) — but the zone is measured by CROW distance to the endpoint,
        // never by `remaining`: the progress hold freezes `remaining` the moment you leave the
        // line, so a remaining-based guard suppressed the (edge-triggered, once-only) reroute
        // event FOREVER for a turn missed near the destination — frozen banner, no voice, no
        // arrival, while the driver leaves the area. Crow distance keeps growing as you drive
        // away, so the guard releases and the reroute fires.
        val crowToDest = loc.distanceTo(maneuvers.last().location)
        val outsideDestZone = remaining > DEST_ZONE_M || crowToDest > DEST_ZONE_M
        var rerouteBlocked = state.rerouteBlocked
        if (offRoute && !state.offRoute) {
            // Rising edge: fire, unless we're in the destination zone — then REMEMBER the
            // suppression, because the edge only happens once and the driver may keep going.
            if (outsideDestZone) events += NavEvent.RerouteNeeded else rerouteBlocked = true
        } else if (offRoute && rerouteBlocked && outsideDestZone) {
            // The suppressed excursion left the destination zone (wrong parking entrance that
            // exits to another street, one-way around the block) — fire the deferred reroute.
            events += NavEvent.RerouteNeeded
            rerouteBlocked = false
        }
        if (!offRoute) rerouteBlocked = false

        // Along-route position of each maneuver, resolved SEQUENTIALLY: each maneuver projects
        // onto the polyline strictly FORWARD of the previous one (maneuvers are ordered along
        // the route by definition — the same non-decreasing technique stopMarks uses). This is
        // exact on reused asphalt by construction (a return-leg turn can never match its
        // outbound twin — the previous maneuver already sits past it; the old GLOBAL projection
        // read such a turn "in 1 mile" when it was 12 miles away), AND it is independent of the
        // step-length metadata — a prefix-sum estimate broke on any source whose distances don't
        // tile the polyline (Google's abbreviated fallback; old trip recordings made before via
        // distances were folded carry km-wrong step lengths). A location that can't be found on
        // the polyline at all (damaged data) falls back to the anchored global match.
        val manAlong = DoubleArray(maneuvers.size)
        run {
            var fromM = 0.0
            for (k in maneuvers.indices) {
                // +0.5 nudge past the previous position: a segment ENDING exactly at fromM still
                // overlaps the window and its (earlier) projection would win the strict-less tie.
                val (wm, wd) = projectAlong(route.polyline, cum, maneuvers[k].location, fromM + 0.5, total)
                manAlong[k] = (
                    if (wd <= ON_ROUTE_M) wm
                    else projectNearAnchor(route.polyline, cum, maneuvers[k].location, fromM).first
                    ).coerceAtLeast(fromM)
                fromM = manAlong[k]
            }
        }
        fun maneuverAlong(k: Int): Double = manAlong[k]

        var spoken = state.spoken
        // Silent catch-up: fast-forward past maneuvers CLEARLY behind us. A GPS gap (tunnel,
        // garage) can pass several maneuvers in one update — the old one-step-per-fix advance
        // replayed each missed turn as an at-the-turn command with a firm haptic, one per second
        // ("Turn left!" buzz, "Turn right!" buzz) for turns already made. The slack is bigger
        // than one fix's travel at highway speed, so a normally-approached turn still gets its
        // spoken turn-now via the advance logic below.
        var idxCur = state.stepIndex.coerceIn(0, maneuvers.lastIndex)
        while (idxCur < maneuvers.lastIndex && route.polyline.size >= 2 &&
            traveled - maneuverAlong(idxCur) > PASSED_SLACK_M
        ) {
            idxCur += 1
            spoken = emptySet()
        }
        val target = maneuvers[idxCur]

        // Distance to the CURRENT maneuver measured ALONG the route, not crow-flies. Crow-flies
        // fired the prompt + advanced the step whenever the maneuver was geographically near,
        // even if miles ahead along the road — a highway curving back near an exit announced
        // "take the exit" miles early, then skipped the real one. The maneuver sits ON the line,
        // so project it (window-anchored, above) and subtract how far we've travelled.
        val dtn = if (route.polyline.size < 2) loc.distanceTo(target.location)
            else (maneuverAlong(idxCur) - traveled).coerceAtLeast(0.0)

        var stepIndex = idxCur

        // The DEPART ("Head out / Head east on …") maneuver sits at the start point (dtn ≈ 0), and it's
        // ALREADY spoken by NavSession.start ("Starting navigation. Head east on F St"). Announcing it
        // here again — the approach prompts AND the interrupt on arrival — is what cut the start prompt
        // off with "a similar direction". Skip it entirely and advance silently to the first real turn,
        // which announces itself as you approach it (Google does the same).
        val isDepart = target.type == ManeuverType.DEPART
        val isArrive = target.type == ManeuverType.ARRIVE
        // Lane guidance from OSRM's per-lane data (same info as the banner arrows) — spoken as a
        // PREFACE on the first prompt that fires for the step, Google-style ("use the right 2
        // lanes to take exit 172 toward Sacramento"), so the lanes come BEFORE the maneuver.
        val lane = app.vela.core.model.laneGuidance(target.lanes)
        // ManeuverType.CONTINUE is minted ONLY for "same physical road, keep driving straight" —
        // OSRM continue/new-name+straight (RouteGeometry.osrmType) and GraphHopper
        // CONTINUE_ON_STREET (ghType) — so it can never carry a turn, fork, ramp, merge or u-turn.
        // Saying it is pure noise ("Continue onto X" when you do nothing, even when the NAME
        // changes under you — Google stays silent there too), so drop the voice + haptics; the
        // step stays on the map + step list. STRAIGHT is the same "no driver action" case — OSRM
        // stamps a dead-straight rename / straight-through as "turn"+"straight" (→ STRAIGHT), and a
        // spoken "go straight" while the road just renames under you is exactly the reported noise;
        // silence it too. One escape hatch for BOTH: only when the lanes show a GENUINE fork — an off
        // lane that ALSO goes straight-ish, i.e. a parallel road you'd drift onto ("use the left 2 lanes
        // to stay on I-80") — do we speak (continueHasGenuineFork). A plain turn bay at an intersection
        // (an off lane marked only left/right) is NOT a fork: you sail straight through, Google stays
        // silent, and so do we — the reported "it says use the lanes to continue when only the name changes".
        val redundantContinue =
            (target.type == ManeuverType.CONTINUE || target.type == ManeuverType.STRAIGHT) &&
                !app.vela.core.model.continueHasGenuineFork(target.lanes)
        val voiceSilent = isDepart || redundantContinue

        // Approach prompts, SPEED-SCALED (Google/OsmAnd scale announcements with speed — the fixed
        // 400 m gave a 75 mph driver 12 s to cross three lanes for an exit). max(fixed, v×T) keeps
        // city/walking behaviour — and every existing test — byte-identical. `spoken` stores the
        // band SLOT (0=far, 1=near), not the metre value: the thresholds move between fixes.
        val v = speedMps ?: 0.0
        val farM = maxOf(400.0, round50(v * 25.0))    // ~25 s out on the open road
        val nearM = maxOf(150.0, round50(v * 8.0))    // ~8 s out
        if (!voiceSilent) {
            val bands = listOf(farM, nearM)
            val due = bands.withIndex().filter { (slot, d) ->
                dtn <= d && slot !in spoken &&
                    // Arrival gets ONE approach cue at the near band ("your destination will be
                    // ahead") — it used to be excluded entirely: silence from the last turn until
                    // "You have arrived". Skip it when we're already at the arrival line.
                    (!isArrive || (slot == 1 && dtn > ARRIVE_RADIUS_M * 2))
            }
            if (due.isNotEmpty()) {
                val firstForStep = spoken.isEmpty()
                spoken = spoken + due.map { it.index }
                val band = due.last().value // the NEAREST due band is the one spoken
                // Speak the REAL distance: entering a short step used to announce the literal
                // threshold ("In 400 meters" for a turn 40 m away) — and both bands fired
                // back-to-back. One prompt per update, larger bands silently consumed.
                val sayM = (if (dtn >= band * 0.85) band else round10(dtn)).coerceAtLeast(10.0)
                val instruction = when {
                    isArrive -> nav().destinationAhead()
                    firstForStep && lane != null -> nav().useLanesToDo(lane.side, lane.count, target.instruction)
                    else -> target.instruction
                }
                events += NavEvent.Speak(nav().inThen(spokenDistance(sayM, imperial), instruction))
                // A light "get ready" tick once the NEAR band is reached, so bikers/walkers feel
                // the turn coming without looking or hearing.
                if (due.last().index == 1 && !isArrive) events += NavEvent.Haptic(target.type, approaching = true)
            }
        }

        // Turn-now / step advance, also speed-scaled (~2.5 s before the maneuver; the fixed 25 m
        // fired ≤1 s before a 75 mph gore point). Final ARRIVAL adds proximity fallbacks: OSRM
        // snaps the destination to the road, and parking 30-50 m short/off used to never arrive —
        // then "Rerouting" fired in the parking lot.
        val turnNowM = (v * 2.5).coerceIn(ARRIVE_RADIUS_M, 90.0)
        if (idxCur < maneuvers.lastIndex) {
            if (dtn <= turnNowM) {
                if (!voiceSilent) {
                    events += NavEvent.Speak(target.instruction, interrupt = true)
                    events += NavEvent.Haptic(target.type) // firm, direction-coded buzz at the turn
                }
                stepIndex = idxCur + 1
                spoken = emptySet()
            }
        } else {
            val crow = loc.distanceTo(target.location)
            // Stationary clause requires crow ≤ 60 (not 120): a red light 45 m along-route short
            // of a just-past-the-intersection destination must not end the session from the
            // stop line (arrival tears the whole nav session + service down).
            val arrivedNow = dtn <= ARRIVE_RADIUS_M ||
                crow <= ARRIVE_PROX_M ||
                (remaining <= 50.0 && !moving && crow <= 60.0)
            if (arrivedNow) {
                events += NavEvent.Arrived
                events += NavEvent.Speak(nav().arrived(), interrupt = true)
                return state.copy(
                    arrived = true,
                    distanceToNextManeuver = 0.0,
                    remainingDistance = 0.0,
                    remainingDuration = 0.0,
                ) to events
            }
        }
        val newTarget = maneuvers[stepIndex]
        // Distance to the next turn measured ALONG the road, not crow-flies (which on a long
        // curved step reads wildly wrong) — the maneuver's window-anchored projection (see
        // maneuverAlong above; the old forward-window-from-traveled projection could still
        // match a LATER pass of the same asphalt and read a far turn as near) minus progress.
        val distToNext = when {
            newTarget.type == ManeuverType.ARRIVE -> remaining
            route.polyline.size < 2 -> loc.distanceTo(newTarget.location)
            else -> (maneuverAlong(stepIndex) - traveled).coerceAtLeast(0.0)
        }
        // The approach leg's GEOMETRIC length (between the two resolved maneuver positions) —
        // pro-rates the remaining-time estimate without trusting the step-length metadata.
        val approachLegM = if (stepIndex > 0) (manAlong[stepIndex] - manAlong[stepIndex - 1]) else manAlong[stepIndex]
        val newState = state.copy(
            stepIndex = stepIndex,
            distanceToNextManeuver = distToNext,
            remainingDistance = remaining,
            remainingDuration = remainingDuration(route, maneuvers, stepIndex, distToNext, remaining, approachLegM),
            offRoute = offRoute,
            offRouteHits = offHits,
            onRouteStreak = onRouteStreak,
            spoken = spoken,
            traveledM = traveled,
            reacquireHits = reacquireHits,
            rerouteBlocked = rerouteBlocked,
        )
        return newState to events
    }

    /** The active language's nav strings (spoken frame + distance + arrival), English by default. */
    private fun nav() = app.vela.core.i18n.NavStringsRegistry.current()

    /** A distance phrased for SPEECH, honouring the imperial/metric preference — now localized via the
     *  active [NavStrings] (English is byte-identical to the old inline logic). */
    private fun spokenDistance(meters: Double, imperial: Boolean): String = nav().spokenDistance(meters, imperial)

    private fun avgSpeed(route: Route): Double {
        val dur = route.durationInTrafficSeconds ?: route.durationSeconds
        return if (dur > 0) (route.distanceMeters / dur).coerceAtLeast(1.0) else 13.4
    }

    /** For each intermediate [stops] waypoint, the metres-along-[route] of its nearest point on the route
     *  line — the "you're passing this stop" mark that drives the per-stop arrival cue — or null when the
     *  stop sits farther than [STOP_ON_ROUTE_M] from the line (not really on this route). Marks are
     *  NON-DECREASING: each stop is projected only onto the route AFTER the previous stop's mark, so an
     *  out-and-back route that passes a later stop's location on the way to an earlier one can't hand the
     *  later stop its first (wrong) pass and fire its cue early. Pure + testable. */
    fun stopMarks(route: Route, stops: List<LatLng>): List<Double?> {
        if (stops.isEmpty() || route.polyline.size < 2) return stops.map { null }
        val cum = cumulative(route.polyline)
        val total = cum.lastOrNull() ?: 0.0
        var from = 0.0
        return stops.map { s ->
            // Nudge the window start past the previous mark: a segment ENDING exactly at `from` still
            // "overlaps" the window, and its projection (before `from`) would win projectAlong's
            // strictly-less tie against the true later pass. Clamp the result for the same reason —
            // ordering is what the cue logic needs; a metre of positional slack is irrelevant at
            // STOP_ARRIVE tolerances.
            val (m, d) = projectAlong(route.polyline, cum, s, from + 0.5, total)
            if (d <= STOP_ON_ROUTE_M) { from = m.coerceAtLeast(from); from } else null
        }
    }

    /** Cumulative geometric length (m) of [path] at each vertex (cum[0] = 0). */
    internal fun cumulative(path: List<LatLng>): DoubleArray {
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + path[i - 1].distanceTo(path[i])
        return cum
    }

    /** Nearest projection of [p] onto [path], searched only among segments overlapping the
     *  along-route window `[loM, hiM]`. Returns (metres-along-route, perpendicular-metres).
     *  Windowing is what stops a route that passes near itself from matching a far leg; a
     *  caller passes the window around the last known progress. Returns the clamped window
     *  start with a huge distance if no segment falls in the window. */
    internal fun projectAlong(path: List<LatLng>, cum: DoubleArray, p: LatLng, loM: Double, hiM: Double): Pair<Double, Double> {
        val total = cum.lastOrNull() ?: 0.0
        var bestD = Double.MAX_VALUE
        var bestAlong = loM.coerceIn(0.0, total)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        for (i in 0 until path.size - 1) {
            if (cum[i + 1] < loM || cum[i] > hiM) continue // segment entirely outside the window
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.lng - p.lng) * mPerDegLng
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestD) {
                bestD = d
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return bestAlong to bestD
    }

    /** Global projection of [p] onto [path], scored by perpendicular distance PLUS a small
     *  penalty (2 cm per metre) of along-route distance from [anchorM] — so where the route
     *  reuses the same asphalt (out-and-back, divided highway) and several passes tie on
     *  perpendicular distance, the pass NEAREST the anchor wins. A far leg must be >20 m
     *  perpendicular-closer per km of along-distance to beat a near match — impossible inside
     *  the ON_ROUTE acceptance band, so re-acquire can no longer teleport progress onto the
     *  return leg. Returns (metres-along-route, ACTUAL perpendicular metres of the winner). */
    internal fun projectNearAnchor(path: List<LatLng>, cum: DoubleArray, p: LatLng, anchorM: Double): Pair<Double, Double> {
        val total = cum.lastOrNull() ?: 0.0
        var bestScore = Double.MAX_VALUE
        var bestD = Double.MAX_VALUE
        var bestAlong = anchorM.coerceIn(0.0, total)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.lng - p.lng) * mPerDegLng
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            val along = cum[i] + t * (cum[i + 1] - cum[i])
            val score = d + 0.02 * kotlin.math.abs(along - anchorM)
            if (score < bestScore) {
                bestScore = score
                bestD = d
                bestAlong = along
            }
        }
        return bestAlong to bestD
    }

}
