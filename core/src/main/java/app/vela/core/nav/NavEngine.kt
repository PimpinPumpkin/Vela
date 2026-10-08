package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
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
    private const val OFF_ROUTE_M = 40.0      // was 45; moving GPS error is <20 m + a lane offset,
                                              // and the corridor width is most of the wrong-turn lag
                                              // on shallow-angle divergences (user 2026-07-15)
    private const val OFF_ROUTE_HITS = 3      // debounce GPS jitter before rerouting (was 4 - a
                                              // moving fix >40 m off three times running is real)
    private const val FAR_OFF_M = 90.0        // this far off counts toward a reroute at ANY speed
                                              // (parking-lot creep sits under the moving floor);
                                              // while MOVING it also counts DOUBLE - >90 m is never
                                              // jitter, so a clear wrong road reroutes in ~2 fixes
    private const val ARRIVE_RADIUS_M = 25.0
    /** The step advances no farther than this from its turn at a crawl or a standstill. */
    private const val ADVANCE_MIN_M = 5.0
    /** In [NavState.spoken]: the turn-now line of the step has been said (0 and 1 are the bands). */
    const val TURN_NOW_SLOT = 2
    private const val ARRIVE_PROX_M = 40.0    // crow-flies arrival fallback (dest snapped to the road; lots/driveways)
    private const val END_PARK_ALONG_M = 60.0 // stopped this close to the end along the route, with turns still unsaid, is arrived
    private const val DEST_ZONE_M = 150.0     // no rerouting this close to the destination (arrival territory)
    private const val ON_ROUTE_M = 60.0       // within this of the windowed route → keep tracking progress
    private const val STOP_ON_ROUTE_M = 150.0 // a waypoint farther than this from the line isn't on this route
    private const val PASSED_SLACK_M = 75.0   // this far PAST a maneuver = it happened during a gap → advance silently
    private const val ANCHOR_PENALTY = 0.02 // meters of perpendicular distance per meter of along-route distance from the anchor
    private const val REACQUIRE_JUMP_M = 1_500.0 // a global re-acquire jumping farther than this needs persistence
    private const val DEFAULT_ACC_M = 12.0    // assumed GPS accuracy when a fix carries none (dead-reckoning)
    private const val HEADING_OFF_DEG = 60.0  // moving this far AGAINST the route's local direction counts
                                              // as an off-route hit even INSIDE the distance corridor. A
                                              // wrong turn onto a nearby-parallel road can sit within the
                                              // corridor for blocks - distance alone never latched, so the
                                              // engine kept guiding on the OLD route with no reroute and no
                                              // redrawn line (device report 2026-07-16). The same 3-hit
                                              // debounce absorbs turn transients and lane changes.

    /**
     * Accuracy-scaled off-route corridor, in meters, for a travel mode. Real navigators (OsmAnd,
     * Organic Maps) don't threshold on a fixed distance - they widen the tolerance with the GPS
     * fix's own reported accuracy: TIGHT when the fix is clean (a wrong turn is caught fast), WIDE
     * when it's noisy (urban-canyon multipath can't false-reroute). `base + K*accuracy`, clamped per
     * mode. Foot/bike ride tighter than driving because the PATH is narrow (a meter or two), but they
     * still widen under bad GPS - which is why fixed 22/28 m felt extreme (user 2026-07-15): those
     * only make sense when the fix is good. `accuracyM` null (dead-reckoning) → a typical-GPS default.
     *
     * Sanity points (accuracy → driving corridor): 5 m → ~28, 12 m → ~42, 30 m → 70 (cap). Driving
     * self-tightens well below the old flat 40 m when GPS is clean, which is the "40 is too high"
     * the user hit, without inviting false reroutes when GPS degrades.
     */
    fun offRouteCorridor(mode: TravelMode, accuracyM: Double?): Double {
        val acc = (accuracyM ?: DEFAULT_ACC_M).coerceIn(3.0, 40.0)
        return when (mode) {
            TravelMode.WALK -> (8.0 + 1.8 * acc).coerceIn(15.0, 50.0)
            TravelMode.BICYCLE -> (12.0 + 1.9 * acc).coerceIn(18.0, 55.0)
            else -> (18.0 + 2.0 * acc).coerceIn(24.0, 70.0)
        }
    }

    /** The "unambiguously far, counts at any speed / counts double while moving" distance: twice the
     *  off-route corridor, capped per mode so it can't run away when GPS is very coarse. */
    fun farOffDistance(mode: TravelMode, offRouteM: Double): Double {
        val cap = when (mode) {
            TravelMode.WALK -> 60.0
            TravelMode.BICYCLE -> 75.0
            else -> 110.0
        }
        return (offRouteM * 2.0).coerceAtMost(cap)
    }

    /** Compass bearing of the route segment at [m] meters along, or null on a degenerate line. */
    private fun routeBearingAt(poly: List<LatLng>, cum: DoubleArray, m: Double): Double? {
        if (poly.size < 2 || cum.size != poly.size) return null
        var i = 1
        while (i < poly.size - 1 && cum[i] < m) i++
        val a = poly[i - 1]
        val b = poly[i]
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = kotlin.math.sin(dLng) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * kotlin.math.sin(Math.toRadians(b.lat)) -
            kotlin.math.sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

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
        // Off-route corridor + "unambiguously far" distance, MODE-AWARE (NavSession passes them).
        // Driving keeps the wide 40 m corridor: a car has lane offset + shallow-angle divergence lag,
        // so a narrower corridor false-reroutes. Walking/biking is far more precise (a walker is on a
        // known sidewalk/path a few meters wide), so NavSession hands down a much tighter pair -
        // otherwise a pedestrian who takes the wrong footpath drifts 40 m before Vela notices.
        offRouteM: Double = OFF_ROUTE_M,
        farOffM: Double = FAR_OFF_M,
        // The fix's COURSE (compass deg, from GPS bearing), when known. Off-route detection is
        // distance-first, but distance alone can't catch a wrong turn onto a road that runs within
        // the corridor of the planned one - the heading term ([HEADING_OFF_DEG]) does.
        bearingDeg: Double? = null,
    ): Pair<NavState, List<NavEvent>> {
        val events = mutableListOf<NavEvent>()
        val maneuvers = route.maneuvers
        if (maneuvers.isEmpty() || state.arrived) return state to events

        // Forward progress along the route (monotonic). Project the fix onto the polyline
        // within a window around how far we'd already traveled — NOT globally — so a route
        // that passes near itself (switchback / cloverleaf / parallel return leg) can't make
        // "remaining" collapse by matching a far leg. Only re-acquire globally when we've
        // clearly left the window (a reroute or a big GPS gap); when genuinely off-route we
        // hold the last progress rather than snapping it to a wrong leg. Also produced here:
        // [offDist], the perpendicular distance of the projection we ADOPTED (or the best seen
        // while holding) — the off-route check below keys on it, NOT on the whole-polyline
        // minimum, so "near the RETURN leg of an out-and-back" can't mask a genuine exit.
        val geom = geomFor(route)
        val cum = geom.cum
        val total = cum.lastOrNull() ?: 0.0
        var reacquireHits = state.reacquireHits
        val traveled: Double
        val offDist: Double
        if (route.polyline.size < 2) {
            traveled = 0.0
            offDist = 0.0
        } else {
            // Anchored on the progress so far: on a road driven out and back (a U-turn by
            // roundabout, "3rd exit onto the road you came on") both passes are under the fix,
            // and the plain nearest one put progress 600 m ahead on the way IN, skipping the
            // roundabout's steps and misreading the next card by that much (a captured Milton
            // Keynes route, 2026-10-02).
            val (wM, wD) = projectAlong(route.polyline, cum, loc, state.traveledM - 60.0, state.traveledM + 600.0, anchorM = state.traveledM)
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
        // rerouting is dead. Unknown speed counts as moving so tests/replays keep old behavior.
        // EXCEPTION - a FAR deviation counts at ANY speed (real drive 2026-07-14): creeping out of
        // a parking lot sits under the 2 m/s floor the whole way, so a driver leaving the route at
        // walking pace never accumulated hits and the reroute (and the redrawn line) never came.
        // Stationary multipath jitter is tens of meters at worst; FAR_OFF_M is comfortably beyond
        // anything a parked car's GPS invents, so counting it can't bring back red-light reroutes.
        val moving = (speedMps ?: 99.0) >= movingFloorMps
        // Heading term: moving with a course >HEADING_OFF_DEG against the route's local direction is
        // an off-route hit even INSIDE the distance corridor. A wrong turn onto a nearby-parallel
        // road (or right after a missed fork, before the roads separate) stays within the corridor
        // for blocks - the projection kept snapping the driver onto the OLD route and guidance
        // carried on with its turns, no reroute, no redrawn line. Turn transients and lane changes
        // are absorbed by the same OFF_ROUTE_HITS debounce; a legit route turn flips the snapped
        // segment's bearing along with the driver's, so it doesn't accumulate hits.
        val headingOff = moving && bearingDeg != null &&
            routeBearingAt(route.polyline, cum, traveled)?.let { rb ->
                kotlin.math.abs(((bearingDeg - rb + 540.0) % 360.0) - 180.0) > HEADING_OFF_DEG
            } == true
        val offHits = when {
            offDist <= offRouteM && !headingOff -> 0
            !moving && offDist <= farOffM -> state.offRouteHits
            // Moving AND unambiguously far: no jitter reaches the far distance at speed, so escalate -
            // the reroute fires after ~2 fixes instead of a full debounce (user 2026-07-15,
            // "waits far too long after a wrong turn").
            moving && offDist > farOffM -> state.offRouteHits + 2
            // Moving AGAINST the route and already a quarter-corridor off the line: a wrong turn,
            // not a wide legit one (which stays within a few meters of the corner while the
            // projection catches up). Counts double, so a deliberate left-instead-of-straight
            // reroutes on the 2nd fix after the turn instead of the 3rd; the sustained
            // back-on-course check in NavSession discards the rare false one (real drive
            // 2026-09-07: "kept going on the correct route for too long").
            moving && headingOff && offDist > offRouteM * 0.25 -> state.offRouteHits + 2
            else -> state.offRouteHits + 1
        }
        val offRoute = offHits >= OFF_ROUTE_HITS
        // Consecutive on-corridor+moving fixes — the SUSTAINED "back on the line" signal. offRoute clears
        // on ONE fix within OFF_ROUTE_M, which is too weak to justify abandoning an in-flight reroute (a
        // single spurious graze on a parallel/overlapping leg would kill a legitimate reroute). This counts
        // UP only while genuinely on-corridor and moving, and resets the instant we're off — so NavSession's
        // back-on-course discard can require a real, multi-fix rejoin, not a one-frame coincidence.
        val onRouteStreak = when {
            // A heading-diverged fix is NOT "back on the line" even inside the corridor - else the
            // back-on-course check would discard a legitimate wrong-way reroute mid-fetch.
            offDist > offRouteM || headingOff -> 0
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
        // Computed once per ROUTE in [geomFor] (it depends on nothing but the route), not per fix.
        val manAlong = geom.manAlong
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
        // so project it (window-anchored, above) and subtract how far we've traveled.
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
        // OSRM continue/new-name+straight (RouteGeometry.osrmType) and the obf engine
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
        // MUTE turn guidance while off-route (Google's behavior): the progress snap still maps
        // the driver onto the OLD route while a reroute is pending (or failing in a dead spot),
        // and as the phantom snap drifts past old maneuvers the engine happily announced them -
        // "turn right onto X" spoken on a street that turn doesn't exist on (the wrong-direction
        // report, 2026-07-13). "Rerouting" is the only thing worth saying until a route matches
        // the road under the wheels. Step advance below stays live so progress/back-on-course
        // bookkeeping is untouched.
        val voiceSilent = isDepart || redundantContinue || offRoute

        // Approach prompts, SPEED-SCALED (Google/OsmAnd scale announcements with speed — the fixed
        // 400 m gave a 75 mph driver 12 s to cross three lanes for an exit). max(fixed, v×T) keeps
        // city/walking behavior — and every existing test — byte-identical. `spoken` stores the
        // band SLOT (0=far, 1=near), not the meter value: the thresholds move between fixes.
        val v = speedMps ?: 0.0
        // 35/10 s (were 25/8, 2026-07-17): a real-drive A/B had Google announcing up to ~0.2 mi
        // sooner at highway speed and lockstep in town — the floors keep city/walking behavior
        // byte-identical, the T is what moves the open-road prompt earlier (~+0.2 mi at 70 mph).
        val farM = maxOf(400.0, round50(v * 35.0))    // ~35 s out on the open road
        val nearM = maxOf(150.0, round50(v * 10.0))   // ~10 s out
        if (!voiceSilent) {
            val bands = listOf(farM, nearM)
            val due = bands.withIndex().filter { (slot, d) ->
                dtn <= d && slot !in spoken &&
                    // Arrival gets ONE approach cue at the near band ("your destination will be
                    // ahead") — it used to be excluded entirely: silence from the last turn until
                    // "You have arrived". Skip it when we're already at the arrival line.
                    (!isArrive || (slot == 1 && dtn > ARRIVE_RADIUS_M * 2)) &&
                    // A MERGE gets ONE approach prompt (near band only): the ramp step just said
                    // where you're going, and a long on-ramp otherwise narrated the same road
                    // three more times in declining counts (real-drive report, 2026-07-17).
                    (target.type != ManeuverType.MERGE || slot == 1)
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
                    // spokenSign drops the secondary sign destinations for SPEECH (the banner keeps
                    // the full sign) - speaking the whole sign took long enough that the next
                    // prompt interrupted it mid-sentence.
                    firstForStep && lane != null -> nav().useLanesToDo(lane.side, lane.count, nav().spokenSign(target.spokenInstruction()))
                    firstForStep -> nav().spokenSign(target.spokenInstruction())
                    // The step's SECOND prompt drops the sign-destination tail ("toward X"):
                    // the far band already named it, and repeating the whole thing at every
                    // band read as "the same shit in declining feet counts" off an exit
                    // (real-drive report, 2026-07-17; Google shortens repeats the same way).
                    else -> nav().repeatShort(target.spokenInstruction())
                }
                // The landmark cue rides on the plain form only: a lane line already says where
                // to be, and "pass the light, then use the left 2 lanes" has the order backward.
                val said = if (isArrive || (firstForStep && lane != null)) instruction else lightLead(target, dtn, instruction)
                events += NavEvent.Speak(nav().inThen(spokenDistance(sayM, imperial), said))
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
        // THE STEP MOVES ON WHEN THE CAR IS AT THE TURN, which at low speed is later than the
        // voice says it. Both used to happen 25 m out, so a car waiting at a stop line 20 m short
        // of its left turn was shown the turn after it, 0.9 miles on, and the road name under the
        // car was the next road's (a real drive, 2026-10-07). At speed the two still coincide.
        val advanceM = (v * 2.5).coerceIn(ADVANCE_MIN_M, 90.0)
        // Parked just short of the last turn (the lot's entrance, a driveway round the corner):
        // the step stays on that turn until the car is at it, so without this the arrival test
        // below, which runs on the last step, is never reached and the drive never ends.
        val crowEnd = loc.distanceTo(maneuvers.last().location)
        val parkedAtEnd = !moving && remaining <= END_PARK_ALONG_M && crowEnd <= ARRIVE_PROX_M
        if (idxCur < maneuvers.lastIndex && !parkedAtEnd) {
            if (dtn <= turnNowM && TURN_NOW_SLOT !in spoken) {
                if (!voiceSilent) {
                    // Turn-now repeats short once any approach band already spoke the full
                    // instruction — "Take the ramp", not the whole sign again (see repeatShort).
                    val turnText = if (spoken.isEmpty()) nav().spokenSign(target.spokenInstruction()) else nav().repeatShort(target.spokenInstruction())
                    events += NavEvent.Speak(turnText, interrupt = true)
                    events += NavEvent.Haptic(target.type) // firm, direction-coded buzz at the turn
                }
                spoken = spoken + TURN_NOW_SLOT
            }
            if (dtn <= advanceM) {
                stepIndex = idxCur + 1
                spoken = emptySet()
            }
        } else {
            val crow = crowEnd
            // Stationary clause requires crow ≤ 60 (not 120): a red light 45 m along-route short
            // of a just-past-the-intersection destination must not end the session from the
            // stop line (arrival tears the whole nav session + service down).
            val arrivedNow = parkedAtEnd || (idxCur == maneuvers.lastIndex && (
                dtn <= ARRIVE_RADIUS_M ||
                    crow <= ARRIVE_PROX_M ||
                    (remaining <= 50.0 && !moving && crow <= 60.0)
                ))
            if (arrivedNow) {
                events += NavEvent.Arrived
                // ONE line, not two stacked (user 2026-07-15): when the route knows the side, the
                // side IS the arrival ("Your destination is on the right"); "You have arrived"
                // is the fallback for a side we can't resolve.
                val side = route.maneuvers.lastOrNull()?.side
                val line = side?.let { nav().destinationSide(left = it == "left") }
                    ?: nav().arrived().trim().trimEnd(';')
                events += NavEvent.Speak(line, interrupt = true)
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
    /** The lines [update] speaks for the first [count] turns of [route] when they come up at
     *  starting speed (the approach bands are then their 400 m / 150 m floors): each turn's far and
     *  near approach prompts and its turn-now line, in the same wording [update] builds. The voice
     *  prepares them while the route preview is up, so the first prompts of a drive need no
     *  synthesis while the camera flies in. A line this misses (a real distance on a short step, a
     *  higher speed) is simply synthesized when spoken, as before. */
    fun startPrompts(route: Route, imperial: Boolean, count: Int = 2): List<String> =
        upcomingPrompts(route, fromStep = 1, spoken = emptySet(), speedMps = 0.0, imperial = imperial, count = count, nearestFirst = false)

    /**
     * The lines [update] is about to speak, from the turn at [fromStep] on, for the voice to get
     * ready ahead of time during the drive: the first [count] spoken turns' far and near approach
     * prompts at [speedMps]'s band distances and their turn-now lines, in [update]'s own wording.
     * The CURRENT turn's lines lead, its turn-now line first: that is the one with no time to
     * spare, and it names no distance, so it always matches. Bands in [spoken] (already said for
     * the current turn) are left out. A line this misses (a real distance on a short step, a
     * speed that has moved a band) is synthesized when spoken, as before.
     */
    fun upcomingPrompts(route: Route, fromStep: Int, spoken: Set<Int>, speedMps: Double, imperial: Boolean, count: Int = 2, nearestFirst: Boolean = true): List<String> {
        val ms = route.maneuvers
        val out = LinkedHashSet<String>()
        var taken = 0
        val far = maxOf(400.0, round50(speedMps * 35.0))
        val near = maxOf(150.0, round50(speedMps * 10.0))
        for (i in fromStep.coerceAtLeast(1) until ms.size) {
            if (taken >= count) break
            val m = ms[i]
            if (m.type == ManeuverType.ARRIVE) break
            if ((m.type == ManeuverType.CONTINUE || m.type == ManeuverType.STRAIGHT) &&
                !app.vela.core.model.continueHasGenuineFork(m.lanes)
            ) continue
            taken++
            val said = if (i == fromStep) spoken else emptySet()
            val leg = ms[i - 1].distanceMeters
            val lane = app.vela.core.model.laneGuidance(m.lanes)
            val full = if (lane != null) nav().useLanesToDo(lane.side, lane.count, nav().spokenSign(m.spokenInstruction()))
                else nav().spokenSign(m.spokenInstruction())
            val short = nav().repeatShort(m.spokenInstruction())
            val farSpoken = leg >= far * 0.85 && m.type != ManeuverType.MERGE
            val anyApproach = leg >= near * 0.85 || said.isNotEmpty()
            val now = if (anyApproach) short else nav().spokenSign(m.spokenInstruction())
            val lines = ArrayList<String>()
            if (farSpoken && 0 !in said) lines += nav().inThen(spokenDistance(far, imperial), if (lane != null) full else lightLead(m, far, full))
            if (leg >= near * 0.85 && 1 !in said) lines += nav().inThen(
                spokenDistance(near, imperial),
                if (farSpoken || said.isNotEmpty()) lightLead(m, near, short) else if (lane != null) full else lightLead(m, near, full),
            )
            if (TURN_NOW_SLOT !in said) lines += now
            // The turn in hand: nearest line first. A later turn: in the order it will be spoken.
            out += if (i == fromStep && nearestFirst) lines.reversed() else lines
        }
        return out.toList()
    }

    /**
     * [text] with the landmark cue in front when one or two marked traffic lights
     * (`Maneuver.lightsBeforeM`) are still ahead of a car [dtn] meters short of the maneuver:
     * "Pass the traffic light, then turn left onto 5th Avenue". Counted when it is spoken, so a
     * light already behind the car is never named. Three or more say nothing, and a language
     * without the phrase (`NavStrings.passLights`) says nothing.
     */
    internal fun lightLead(m: Maneuver, dtn: Double, text: String): String {
        if (m.lightsBeforeM.isEmpty()) return text
        val ahead = m.lightsBeforeM.count { it <= dtn - LIGHT_AHEAD_MIN_M }
        val lead = if (ahead in 1..2) nav().passLights(ahead) else ""
        return if (lead.isBlank()) text else "$lead, then " + text.replaceFirstChar { it.lowercaseChar() }
    }
    /** A light counts as ahead when the car has at least this far to go to it. */
    private const val LIGHT_AHEAD_MIN_M = 20.0

    private fun nav() = app.vela.core.i18n.NavStringsRegistry.current()

    /** A distance phrased for SPEECH, honoring the imperial/metric preference — now localized via the
     *  active [NavStrings] (English is byte-identical to the old inline logic). */
    private fun spokenDistance(meters: Double, imperial: Boolean): String = nav().spokenDistance(meters, imperial)

    private fun avgSpeed(route: Route): Double {
        val dur = route.durationInTrafficSeconds ?: route.durationSeconds
        return if (dur > 0) (route.distanceMeters / dur).coerceAtLeast(1.0) else 13.4
    }

    /** For each intermediate [stops] waypoint, the meters-along-[route] of its nearest point on the route
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
            // ordering is what the cue logic needs; a meter of positional slack is irrelevant at
            // STOP_ARRIVE tolerances.
            val (m, d) = projectAlong(route.polyline, cum, s, from + 0.5, total)
            if (d <= STOP_ON_ROUTE_M) { from = m.coerceAtLeast(from); from } else null
        }
    }

    /** How many of the stops the drive has passed at [traveledM], counting on from [from] ([marks]
     *  from [stopMarks], [tolM] the arrival tolerance). A stop with no mark is not on this route: it
     *  counts as passed only once a LATER stop with a mark is passed, so it never blocks the cues
     *  after it, and a list with no marks at all (a stops edit or a reroute that could not fit the
     *  stops, before the next route lands) keeps every stop instead of dropping them all. */
    fun stopsPassed(marks: List<Double?>, count: Int, from: Int, traveledM: Double, tolM: Double): Int {
        var i = from
        while (i < count) {
            val mark = marks.getOrNull(i) ?: (i + 1 until count).firstNotNullOfOrNull { marks.getOrNull(it) } ?: break
            if (traveledM >= mark - tolM) i++ else break
        }
        return i
    }

    /** Whether progress JUMPED past the next stop instead of driving to it: from [prevM] to [nowM]
     *  in one fix, more than [jumpM], with the next stop's mark (a later stop's when it has none)
     *  inside the jump. The engine adopts a far re-acquire at once when the car sits right on a
     *  later stretch of the route (a driver who kept going after a stops edit, on the road the
     *  route uses to come back), and every stop in between then counted as reached and was
     *  announced without ever being visited. */
    fun stopSkipped(marks: List<Double?>, count: Int, from: Int, prevM: Double, nowM: Double, tolM: Double, jumpM: Double): Boolean {
        if (nowM - prevM <= jumpM || from >= count) return false
        val mark = marks.getOrNull(from) ?: (from + 1 until count).firstNotNullOfOrNull { marks.getOrNull(it) } ?: return false
        return mark > prevM + tolM && mark <= nowM + tolM
    }

    /** Whether every stop the jump from [prevM] to [nowM] went past is a silent one ([silent] by
     *  stop index): a via a saved route or a camera detour was built through. The driver took
     *  another way round that stretch and is back on the route, so nothing is owed to it. A jump
     *  that also passed a stop the driver asked for is still a skip. */
    fun onlySilentSkipped(marks: List<Double?>, silent: List<Boolean>, from: Int, prevM: Double, nowM: Double, tolM: Double): Boolean {
        val jumped = (from until silent.size).filter { i -> marks.getOrNull(i)?.let { it > prevM + tolM && it <= nowM + tolM } == true }
        return jumped.isNotEmpty() && jumped.all { silent[it] }
    }

    /** The per-route geometry every [update] needs: cumulative meters at each vertex, and each
     *  maneuver's along-route mark. Both depend on the ROUTE alone, yet were rebuilt on every fix:
     *  a full-polyline pass plus a windowed projection of EVERY maneuver over the remaining line,
     *  once a second on the main thread, ~19 ms on a long route (measured 2026-09-03: over a
     *  frame budget by itself, so the puck dropped a frame per fix even with the banner fixed).
     *  One slot keyed by route identity: nav follows a single route and a reroute is a new object. */
    private class RouteGeom(val route: Route, val cum: DoubleArray, val manAlong: DoubleArray)
    @Volatile private var geomCache: RouteGeom? = null

    private fun geomFor(route: Route): RouteGeom {
        geomCache?.let { if (it.route === route) return it }
        val cum = cumulative(route.polyline)
        val total = cum.lastOrNull() ?: 0.0
        val maneuvers = route.maneuvers
        val manAlong = DoubleArray(maneuvers.size)
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
        return RouteGeom(route, cum, manAlong).also { geomCache = it }
    }

    internal fun cumulative(path: List<LatLng>): DoubleArray {
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + path[i - 1].distanceTo(path[i])
        return cum
    }

    /** Nearest projection of [p] onto [path], searched only among segments overlapping the
     *  along-route window `[loM, hiM]`. Returns (meters-along-route, perpendicular-meters).
     *  Windowing is what stops a route that passes near itself from matching a far leg; a
     *  caller passes the window around the last known progress. Returns the clamped window
     *  start with a huge distance if no segment falls in the window. */
    internal fun projectAlong(path: List<LatLng>, cum: DoubleArray, p: LatLng, loM: Double, hiM: Double, anchorM: Double? = null): Pair<Double, Double> {
        val total = cum.lastOrNull() ?: 0.0
        var bestScore = Double.MAX_VALUE
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
            val along = cum[i] + t * (cum[i + 1] - cum[i])
            // With an anchor, a pass farther along has to be that much nearer to win (2 cm per
            // meter, the re-acquire's own rule): the window alone reaches 600 m ahead, and a
            // route that goes round a roundabout and comes BACK down the road it arrived on
            // lies on itself inside it.
            val score = if (anchorM == null) d else d + ANCHOR_PENALTY * kotlin.math.abs(along - anchorM)
            if (score < bestScore) {
                bestScore = score
                bestD = d
                bestAlong = along
            }
        }
        return bestAlong to bestD
    }

    /** Global projection of [p] onto [path], scored by perpendicular distance PLUS a small
     *  penalty (2 cm per meter) of along-route distance from [anchorM] — so where the route
     *  reuses the same asphalt (out-and-back, divided highway) and several passes tie on
     *  perpendicular distance, the pass NEAREST the anchor wins. A far leg must be >20 m
     *  perpendicular-closer per km of along-distance to beat a near match — impossible inside
     *  the ON_ROUTE acceptance band, so re-acquire can no longer teleport progress onto the
     *  return leg. Returns (meters-along-route, ACTUAL perpendicular meters of the winner). */
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
            val score = d + ANCHOR_PENALTY * kotlin.math.abs(along - anchorM)
            if (score < bestScore) {
                bestScore = score
                bestD = d
                bestAlong = along
            }
        }
        return bestAlong to bestD
    }

}
