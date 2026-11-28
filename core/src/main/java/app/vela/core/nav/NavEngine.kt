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
    /** In [NavState.spoken]: the step's turn-now WORDS were already said as the "then" of the line
     *  just before, so its turn-now point buzzes without speaking ([CHAIN_REPEAT_S]). */
    const val TURN_NOW_SAID_SLOT = 3
    /** A chained step whose own turn-now line would come within this many seconds of the "then"
     *  that named it, in the same words, is not said again: at speed two forks 45 m apart said
     *  "keep slight right" twice in two seconds. */
    const val CHAIN_REPEAT_S = 5.0
    /** The full voice's one approach line ([oneApproach]) comes this many seconds before a
     *  maneuver: a quarter mile at 65 mph. */
    const val FAST_APPROACH_S = 15.0
    /** From this pace (15 m/s, 34 mph) an exit, fork or keep gets the one approach line too. */
    const val EXIT_ONE_LINE_MPS = 15.0
    /** A band the car reaches within this many seconds of a prompt is taken as said by it. */
    private const val BAND_MERGE_S = 8.0
    /** The voice's pace in letters a second and the lead before its first sound: how long a line takes. */
    private const val SPEECH_CPS = 13.0
    private const val SPEECH_LEAD_S = 0.6
    /** The pace the two estimates above assume at a crawl, so a standstill divides by something. */
    /** The slowest pace the prompt timing reckons with: a slow walk. A higher floor (5 m/s once)
     *  read a walker's 150 m band as 8 s away and merged it, so walkers and cyclists lost the
     *  near prompt and its haptic. */
    private const val MIN_PACE_MPS = 1.5
    private const val ARRIVE_PROX_M = 40.0    // crow-flies arrival fallback (dest snapped to the road; lots/driveways)
    private const val END_PARK_ALONG_M = 60.0 // stopped this close to the end along the route, with turns still unsaid, is arrived
    private const val PARK_STILL_FIXES = 10    // ... once stopped this many fixes in a row (about 10 s)
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
    /** The heading term needs this speed when driving ([update]'s headingMinMps): a GPS course
     *  under it is mostly noise. A car pulling away from a light between tall buildings was read
     *  crawling backward along its own route at 2.7 to 3.9 m/s for three fixes, right on the
     *  line, and the route was dropped for one that sent it the other way. */
    const val HEADING_MIN_MPS = 5.0
    /** How far ahead or behind a turn the route makes still counts as the way to go for the heading term. */
    private const val HEADING_NEAR_M = 25.0
    /** Within this of the road behind the car counts as still going its way ([update], straight on
     *  where the route turns). A turn from a stop with the fix lagging had swung 17 degrees one fix
     *  past the corner; a car going straight holds its course within a few. */
    private const val STRAIGHT_ON_DEG = 12.0
    /** A fix the phone rates worse than this says nothing about the course (NavSession). */
    const val HEADING_MAX_ACC_M = 30.0

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

    /** A leg boundary ([Route.legTimes]) counts as [atM] within this, or 1.5% of the line. */
    const val LEG_MATCH_M = 250.0
    private const val LEG_MATCH_FRAC = 0.015

    /** The lowest and highest a leg's own time may be, against the pro-rated time of the same
     *  leg ([legScale]): past these the two disagree about the road, not the traffic. */
    private const val LEG_SCALE_MIN = 0.25
    private const val LEG_SCALE_MAX = 4.0

    /**
     * The index of the leg ([Route.legTimes]) that ends at [atM], a stop the trip was asked
     * through, or -1. The boundaries are the legs' distances stretched onto the line's own length,
     * which a stop's mark is measured on; a boundary counts when it lies within [LEG_MATCH_M] (or
     * 1.5% of the line) of [atM]. -1 when the route carries no legs.
     */
    fun legEndingAt(route: Route, atM: Double): Int {
        val legs = route.legTimes
        if (legs.size < 2 || route.polyline.size < 2) return -1
        val sum = legs.sumOf { it.distanceMeters }
        if (sum <= 0.0) return -1
        val total = geomFor(route).cum.last()
        val k = total / sum
        var cum = 0.0
        var best = -1
        var bestOff = Double.MAX_VALUE
        for (j in 0 until legs.lastIndex) {
            cum += legs[j].distanceMeters * k
            val off = kotlin.math.abs(cum - atM)
            if (off < bestOff) { bestOff = off; best = j }
        }
        return if (best < 0 || bestOff > maxOf(LEG_MATCH_M, total * LEG_MATCH_FRAC)) -1 else best
    }

    /** A leg's own time: its live figure when the route has traffic, else its typical. */
    private fun legSeconds(route: Route, leg: app.vela.core.model.LegTime): Double =
        if (route.durationInTrafficSeconds != null) leg.trafficSeconds ?: leg.typicalSeconds else leg.typicalSeconds

    /** Seconds of the trip past [atM] from the route's own leg times: the sum of the legs after
     *  the one ending there ([legEndingAt]), or null. */
    fun legSecondsBeyond(route: Route, atM: Double): Double? {
        val j = legEndingAt(route, atM)
        if (j < 0) return null
        return route.legTimes.drop(j + 1).sumOf { legSeconds(route, it) }
    }

    /** Seconds from the route's start to the stop at [atM] from the route's own leg times: the
     *  legs up to and including the one ending there, or null. The closing-time check's figure
     *  at the start of a drive. */
    fun legSecondsTo(route: Route, atM: Double): Double? {
        val j = legEndingAt(route, atM)
        if (j < 0) return null
        return route.legTimes.take(j + 1).sumOf { legSeconds(route, it) }
    }

    /**
     * How much longer the leg ending at [atM] takes by its own figure than by the steps'
     * pro-rating: the leg's own seconds over [secondsBeyond] at [legStartM] less at [atM]. The
     * engine's time to the mark (its remaining time less [secondsBeyond] at the mark) reaches zero
     * at the mark and shrinks as the trip's does; times this it starts at the leg's own figure and
     * still reaches zero, so a stop's countdown is Google's where the traffic sits unevenly over
     * the legs, and never stalls or runs out early. 1.0 with no matching leg or a leg the steps
     * give no time to, and never past [LEG_SCALE_MIN] or [LEG_SCALE_MAX].
     */
    fun legScale(route: Route, atM: Double, legStartM: Double): Double {
        val j = legEndingAt(route, atM)
        if (j < 0) return 1.0
        val own = legSeconds(route, route.legTimes[j])
        val prorated = secondsBeyond(route, legStartM) - secondsBeyond(route, atM)
        if (own <= 0.0 || prorated <= 0.0) return 1.0
        return (own / prorated).coerceIn(LEG_SCALE_MIN, LEG_SCALE_MAX)
    }

    /**
     * Seconds of the trip still to drive past [atM] meters along [route], reckoned the way
     * [remainingDuration] reckons the whole trip: each maneuver's leg at its own pace, the leg
     * [atM] falls on pro-rated by how much of it lies beyond, scaled by the route's live-traffic
     * ratio; the route's average speed when the steps carry no usable durations. So the time to a
     * point on the route is the engine's remaining time less this, and the two always add up to
     * the trip (the next-stop figures in [NavSession.nextStop], which then scale the time to a
     * stop by its leg's own figure, [legScale]).
     */
    fun secondsBeyond(route: Route, atM: Double): Double {
        val maneuvers = route.maneuvers
        if (route.polyline.size < 2 || maneuvers.isEmpty()) return 0.0
        val geom = geomFor(route)
        val total = geom.cum.last()
        val base = route.durationSeconds
        if (base <= 0.0 || maneuvers.sumOf { it.durationSeconds } < base * 0.7) return (total - atM).coerceAtLeast(0.0) / avgSpeed(route)
        var t = 0.0
        // The last maneuver's own duration is left out, as in [remainingDuration]: it is the arrival.
        for (k in 0 until maneuvers.lastIndex) {
            val a = geom.manAlong[k]
            val b = geom.manAlong[k + 1]
            if (b <= atM) continue
            val len = b - a
            t += if (a >= atM || len <= 0.0) maneuvers[k].durationSeconds else maneuvers[k].durationSeconds * (b - atM) / len
        }
        return t * ((route.durationInTrafficSeconds ?: base) / base)
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
        // The next stop on a drive with stops (NavSession), so the voice says it is coming the
        // way it says the destination is. Null = no stop cues, everything else unchanged.
        stop: StopAhead? = null,
        // The speed the heading term needs, MODE-AWARE (NavSession passes it): [HEADING_MIN_MPS]
        // driving; a walker or cyclist keeps the moving floor.
        headingMinMps: Double = HEADING_MIN_MPS,
        // A GPX track ride (TrackRide): the approach bands and the "then" distance are a
        // cyclist's ([farBand], [nearBand], [TRACK_CHAIN_M]). False keeps every other drive as is.
        trackRide: Boolean = false,
        // A drive (NavSession, DRIVE): a town turn says no line at the corner ([townTurn]). Its
        // near line carries the turn after it, and the line for that turn waits until the car is
        // [CORNER_CLEAR_M] past this one. False keeps the line at the turn (walking, cycling).
        cornerQuiet: Boolean = false,
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
            // A round trip ends where it starts: until the car is [loopGuardM] in, the route's last
            // stretch is no match for a fix. On a loop short enough for the window to reach its end,
            // a fix at the start that drifted 15 m onto the road the trip comes back on read as the
            // end of the trip, and the drive arrived as it set off.
            val guardM = loopGuardM(route.polyline, total)
            val capM = if (guardM > 0.0 && state.traveledM < guardM) total - guardM else Double.POSITIVE_INFINITY
            val (wM, wD) = projectAlong(route.polyline, cum, loc, state.traveledM - 60.0, state.traveledM + 600.0, anchorM = state.traveledM, capM = capM)
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
                val (gM, gD) = projectNearAnchor(route.polyline, cum, loc, state.traveledM, capM)
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
        // The course is held against the road just ahead and just behind too ([HEADING_NEAR_M]):
        // mid-turn it has swung while the projection still sits before the corner, or not swung
        // enough when the fix is already placed past it, and a turn the route makes was read as
        // driving against it (a reroute 3 s after a right turn from a stop, taken as told, real
        // drive 2026-11-17). A wrong turn points away from the road on both sides of the corner.
        fun against(m: Double) = routeBearingAt(route.polyline, cum, m)?.let { rb ->
            kotlin.math.abs(((bearingDeg!! - rb + 540.0) % 360.0) - 180.0) > HEADING_OFF_DEG
        }
        val headingOff = moving && (speedMps ?: 99.0) >= headingMinMps && bearingDeg != null &&
            against(traveled) == true && against(minOf(total, traveled + HEADING_NEAR_M)) != false &&
            against(maxOf(0.0, traveled - HEADING_NEAR_M)) != false
        // STRAIGHT ON WHERE THE ROUTE TURNS: past the corner the fix projects onto the corner
        // itself, the road behind it still runs the car's way, so [headingOff] never fires and the
        // car had to get 40 m off and three fixes on (7 s at 11 m/s on a mock drive past a Davis
        // corner) before anything changed. Going the way of the road behind and against the road
        // ahead, a quarter of the corridor off, is that case.
        val straightPast = moving && (speedMps ?: 99.0) >= headingMinMps && bearingDeg != null &&
            routeBearingAt(route.polyline, cum, maxOf(0.0, traveled - HEADING_NEAR_M))?.let { rb ->
                kotlin.math.abs(((bearingDeg!! - rb + 540.0) % 360.0) - 180.0) <= STRAIGHT_ON_DEG
            } == true && against(minOf(total, traveled + HEADING_NEAR_M)) == true
        val offHits = when {
            offDist <= offRouteM && !headingOff && !(straightPast && offDist > offRouteM * 0.25) -> 0
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
            moving && straightPast && offDist > offRouteM * 0.25 -> state.offRouteHits + 2
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
        // This turn was already said as the "then ..." of the one before it, a block back: its
        // approach lines are taken as said. Its line at the turn itself still comes.
        if (state.chainedStep == idxCur && spoken.isEmpty()) spoken = state.chainedBands
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
                !app.vela.core.model.continueHasGenuineFork(target.lanes) ||
                ringExit(route.maneuvers, idxCur)
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
        val farM = farBand(v, trackRide)    // ~35 s out on the open road
        val nearM = nearBand(v, trackRide)  // ~10 s out
        // At highway pace the full voice gives a maneuver ONE approach line, FAST_APPROACH_S out
        // ("In a quarter mile" from 50 to 80 mph), and its line at the turn (oneApproach). Far,
        // near and at the turn made eight lines of one exit, the fork on its ramp and the merge
        // after it. Both bands are the same distance, so they are said as one.
        val detail = SpokenDetail.mode
        fun bandsFor(i: Int): Pair<Double, Double> =
            if (detail == SpokenDetail.Mode.FULL && oneApproach(maneuvers, i, v)) {
                maxOf(nearM, round50(v * FAST_APPROACH_S)).let { it to it }
            } else farM to nearM
        // The line at the turn, ~2.5 s before it (the fixed 25 m fired ≤1 s before a 75 mph gore
        // point). Needed by the approach prompts too, which must end before it cuts in.
        val turnNowM = (v * 2.5).coerceIn(ARRIVE_RADIUS_M, 90.0)

        // The next stop is said after the last maneuver before it ("Turn left onto Covell
        // Boulevard, then Davis Food Co-op will be on your right"), and "into the parking lot"
        // when the map says that turn enters one. Only on the step's first line and its turn-now
        // line, so a stop is named at most twice for its turn.
        val stopLeft = when (stop?.side) { "left" -> true; "right" -> false; else -> null }
        // The destination's side, said on the approach as at the arrival.
        val arriveLeft = when (target.side) { "left" -> true; "right" -> false; else -> null }
        val thenStopHere = stop != null && !isArrive && !isDepart && idxCur == stopLegManeuver(route, manAlong, stop.atM)
        // The turn into the stop's parking lot (NavSession.lotTurn), which is the turn off the
        // street and may sit a turn or two before the last one: its lines say so, and when it is
        // also the last turn before the stop, the stop follows on the same line.
        val lotTurnLeft = if (stop != null && !isArrive && !isDepart && idxCur == stop.lotTurn && target.road.isNullOrBlank() && target.ref.isNullOrBlank()) turnLeftOf(target.type) else null
        val lotLine = lotTurnLeft?.let { nav().intoLot(it) }
        var stopCuedNow = false
        fun withStop(line: String): String {
            if (!thenStopHere) return line
            stopCuedNow = true
            return if (lotTurnLeft != null) nav().intoLotThen(lotTurnLeft, stop!!.label, stopLeft)
            else nav().thenStop(line, stop!!.label, stopLeft)
        }
        // TWO TURNS TOO CLOSE TO SAY ONE AT A TIME. The turn after this one, when it follows
        // within CHAIN_M, rides on this one's lines: "Turn right onto Elm Street, then turn left
        // onto Oak Avenue". Said apart, the second turn's approach landed on top of the first
        // turn's own line or was cut by it. Never on a line that already carries a stop.
        val nextIdx = if (isArrive || isDepart || thenStopHere) -1 else chainedNext(maneuvers, manAlong, idxCur, if (trackRide) TRACK_CHAIN_M else CHAIN_M)
        // FURTHER ON, BUT DUE AT ONCE. A next step past CHAIN_M whose first prompt would fire as
        // soon as this turn is made rides on the line at this turn only: "Take the ramp, then keep
        // left". Said apart, "Take the exit" and "In a quarter mile, keep slight left" came a
        // second apart. Its bands the car reaches within BAND_MERGE_S of here count as said; a
        // later one is still said. A step with lanes says them in the "then". Not on a track
        // ride: there the next turn is said on its own line with its distance, so a "then" means
        // the two turns are a few meters apart ([TRACK_CHAIN_M]).
        val laterIdx = if (nextIdx >= 0 || isArrive || isDepart || thenStopHere || trackRide) -1 else dueNext(maneuvers, manAlong, idxCur, farM + v.coerceAtLeast(MIN_PACE_MPS) * BAND_MERGE_S)
        // A merge named in the "then" is said: it has one line, and this was it.
        val laterBands = if (laterIdx < 0) emptySet() else if (maneuvers[laterIdx].type == ManeuverType.MERGE) setOf(0, 1) else {
            val gap = manAlong[laterIdx] - manAlong[idxCur]
            val pace = v.coerceAtLeast(MIN_PACE_MPS)
            bandsFor(laterIdx).toList().withIndex().filter { (_, d) -> (gap - d) / pace < BAND_MERGE_S }.map { it.index }.toSet()
        }
        // GOOGLE'S TOWN RHYTHM: two lines, the far one with its distance and the near one, and
        // nothing at the corner, where the driver is turning. The near line names the turn after.
        val quietCorner = cornerQuiet && detail == SpokenDetail.Mode.FULL && !trackRide && townTurn(maneuvers, idxCur, v)
        val chainIdx = if (nextIdx >= 0) nextIdx else laterIdx
        // Just round a quiet corner, the next turn's first line waits until the turn is made.
        val prevCornerHold = cornerQuiet && !trackRide && idxCur > 0 && townTurn(maneuvers, idxCur - 1, v) &&
            traveled < manAlong[idxCur - 1] + CORNER_CLEAR_M
        var chainedOnNear = false
        var chainedNow = false
        var chainedIdx = -1
        // The words the "then" gave the chained step, and whether they were said at this turn.
        var thenSaid: String? = null
        var thenAtTurn = false
        fun withNext(line: String, idx: Int = nextIdx): String {
            if (idx < 0) return line
            val (both, plain) = joinNext(target, maneuvers[idx], line, dtn + manAlong[idx] - manAlong[idxCur], detail)
            if (both != line) { chainedNow = true; chainedIdx = idx; thenSaid = plain; thenAtTurn = false }
            return both
        }
        // The line at the turn, with the next turn on it when the two are chained ([joinAtTurn]).
        fun turnNowLine(plain: String): String {
            val idx = if (nextIdx >= 0) nextIdx else laterIdx
            if (idx < 0) return plain
            val gap = manAlong[idx] - manAlong[idxCur]
            val j = joinAtTurn(target, maneuvers[idx], plain, dtn + gap, dtn + gap - turnNowM, v, detail, lotLine == null && !thenStopHere)
            if (j.chained) { chainedNow = true; chainedIdx = idx; thenSaid = j.thenSaid; thenAtTurn = j.atTurn }
            return j.line
        }
        if (!voiceSilent && detail != SpokenDetail.Mode.FULL) {
            // One short line a maneuver (see SpokenDetail): exits from far out, turns from near.
            val fromFar = !isArrive && (SpokenDetail.exitLike(target.type) || v >= SpokenDetail.FAST_MPS)
            val band = if (fromFar) farM else nearM
            if (spoken.isEmpty() && dtn <= band && SpokenDetail.speaks(detail, target.type, v) &&
                (!isArrive || dtn > ARRIVE_RADIUS_M * 2)
            ) {
                spoken = setOf(0, 1)
                val sayM = (if (dtn >= band * 0.85) band else round10(dtn)).coerceAtLeast(10.0)
                val line = when {
                    isArrive -> nav().destinationWillBe(arriveLeft)
                    // An exit keeps its number and road: that is what the sign overhead says.
                    SpokenDetail.exitLike(target.type) -> nav().repeatShort(target.spokenInstruction())
                    else -> nav().repeatShort(lotLine ?: target.instructionNoRoad ?: target.spokenInstruction())
                }
                events += NavEvent.Speak(nav().inThen(spokenDistance(sayM, imperial), withNext(withStop(line))))
                if (!isArrive) events += NavEvent.Haptic(target.type, approaching = true)
            }
        }
        if (!voiceSilent && detail == SpokenDetail.Mode.FULL && !prevCornerHold) {
            val bands = bandsFor(idxCur).toList()
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
                    isArrive -> nav().destinationWillBe(arriveLeft)
                    // spokenSign drops the secondary sign destinations for SPEECH (the banner keeps
                    // the full sign) - speaking the whole sign took long enough that the next
                    // prompt interrupted it mid-sentence.
                    firstForStep && lane != null -> nav().useLanesToDo(lane.side, lane.count, nav().spokenSign(lotLine ?: target.spokenInstruction()))
                    firstForStep -> nav().spokenSign(lotLine ?: target.spokenInstruction())
                    // The step's SECOND prompt drops the sign-destination tail ("toward X"):
                    // the far band already named it, and repeating the whole thing at every
                    // band read as "the same shit in declining feet counts" off an exit
                    // (real-drive report, 2026-07-17; Google shortens repeats the same way).
                    else -> nav().repeatShort(lotLine ?: target.spokenInstruction())
                }
                val pace = v.coerceAtLeast(MIN_PACE_MPS)
                // The light or stop-sign cue (lightCue) rides on the plain form only: a lane line
                // already says where to be, and "go past this light, then use the left 2 lanes" has
                // the order backward. A line that names the stop after it takes none either: two
                // "then"s. One that names the turn after it takes "At the light" or "At the stop
                // sign" alone, which add no "then". The step's near line is the one said at the
                // near band, or close enough before it that the near band counts as said by it.
                val nearLine = due.last().index == 1 || bands.size > 1 && bands[1] < sayM && (sayM - bands[1]) / pace < BAND_MERGE_S
                val cue = if (isArrive) null else lightCue(target, dtn, if (nearLine) CueLine.NEAR else CueLine.FAR)
                fun wrap(instr: String) = when {
                    firstForStep && thenStopHere -> withStop(instr)
                    quietCorner && nearLine && !thenStopHere && !isArrive && chainIdx >= 0 -> {
                        val own = if (firstForStep && lane != null) instr else withLight(cue.takeIf { it == LightCue.AT_LIGHT || it == LightCue.AT_STOP }, instr)
                        withNext(own, chainIdx).also { if (it != own) chainedOnNear = true }
                    }
                    isArrive || (firstForStep && lane != null) -> instr
                    firstForStep && nextIdx >= 0 -> withNext(withLight(cue.takeIf { it == LightCue.AT_LIGHT || it == LightCue.AT_STOP }, instr))
                    else -> withLight(cue.takeIf { it != LightCue.NEXT_STOP }, instr)
                }
                // "At the next stop sign, turn right onto ..." stands in for the distance, on a
                // plain line only.
                val nextStop = cue == LightCue.NEXT_STOP && !(firstForStep && (thenStopHere || lane != null || nextIdx >= 0)) &&
                    !(quietCorner && nearLine && chainIdx >= 0)
                val distancePhrase = spokenDistance(sayM, imperial)
                // A quiet town turn's near line has no distance, as Google's: "At the light, turn
                // left onto Elm Street". Said about 10 s out, the number was stale by its end.
                fun say(instr: String) = when {
                    nextStop -> withLight(LightCue.NEXT_STOP, instr)
                    quietCorner && nearLine -> wrap(instr)
                    else -> nav().inThen(distancePhrase, wrap(instr))
                }
                var line = say(instruction)
                // Entering a short step at speed, the whole line was still playing when the line
                // at the turn cut in (it interrupts), and the sign's tail was what got cut. When
                // the car reaches the turn-now point before the line could end, the first prompt
                // keeps its lanes and drops the tail, as a repeat does.
                if (firstForStep && !isArrive && (dtn - turnNowM) / pace < line.length / SPEECH_CPS + SPEECH_LEAD_S) {
                    val brief = nav().repeatShort(lotLine ?: target.spokenInstruction())
                    line = say(if (lane != null) nav().useLanesToDo(lane.side, lane.count, brief) else brief)
                }
                events += NavEvent.Speak(line)
                // A band the car reaches within BAND_MERGE_S of this line is said by it: entering
                // a step 170 m from its turn said the line, and the 150 m band said it again
                // three seconds later.
                spoken = spoken + bands.withIndex().filter { (slot, d) -> slot !in spoken && d < sayM && (sayM - d) / pace < BAND_MERGE_S }.map { it.index }
                // A light "get ready" tick once the NEAR band is reached, so bikers/walkers feel
                // the turn coming without looking or hearing.
                if (due.last().index == 1 && !isArrive) events += NavEvent.Haptic(target.type, approaching = true)
            }
        }

        // Turn-now / step advance, also speed-scaled (turnNowM above). Final ARRIVAL adds
        // proximity fallbacks: OSRM snaps the destination to the road, and parking 30-50 m
        // short/off used to never arrive, and then "Rerouting" fired in the parking lot.
        // THE STEP MOVES ON WHEN THE CAR IS AT THE TURN, which at low speed is later than the
        // voice says it. Both used to happen 25 m out, so a car waiting at a stop line 20 m short
        // of its left turn was shown the turn after it, 0.9 miles on, and the road name under the
        // car was the next road's (a real drive, 2026-10-07). At speed the two still coincide.
        val advanceM = (v * 2.5).coerceIn(ADVANCE_MIN_M, 90.0)
        // Parked just short of the last turn (the lot's entrance, a driveway round the corner):
        // the step stays on that turn until the car is at it, so without this the arrival test
        // below, which runs on the last step, is never reached and the drive never ends.
        val crowEnd = loc.distanceTo(maneuvers.last().location)
        // Parked, not waiting to turn in: a car stopped to yield at the lot's entrance 3 s after
        // "Turn right" was told it had arrived. Stopped for PARK_STILL_FIXES fixes in a row.
        val stillFixes = if (moving) 0 else state.stillFixes + 1
        val parkedAtEnd = stillFixes >= PARK_STILL_FIXES && remaining <= END_PARK_ALONG_M && crowEnd <= ARRIVE_PROX_M
        if (idxCur < maneuvers.lastIndex && !parkedAtEnd) {
            // Just round a quiet corner the line at a turn close after it waits too: said in the
            // hold, it played inside the corner and the near line said it again after.
            if (dtn <= turnNowM && TURN_NOW_SLOT !in spoken && !prevCornerHold) {
                if (!voiceSilent) {
                    // Turn-now repeats short once any approach band already spoke the full
                    // instruction — "Take the ramp", not the whole sign again (see repeatShort).
                    val plainTurn = if (spoken.isEmpty()) nav().spokenSign(lotLine ?: target.spokenInstruction()) else lotLine?.let { nav().repeatShort(it) } ?: short(target, target.spokenInstruction())
                    // "At the light" or "At the stop sign" again at the turn. Never "Go past" this late.
                    val turnText = withLight(lightCue(target, dtn, CueLine.NOW), plainTurn)
                    // A roundabout said at its near band by its own approach is not said again at
                    // its entry, where the driver is yielding and the words would be the same. The
                    // line still comes when it carries the step after it (roundabouts in a row).
                    val ringSaid = target.type == ManeuverType.ROUNDABOUT && 1 in spoken &&
                        state.chainedStep != idxCur && nextIdx < 0 && laterIdx < 0
                    // A merge says one line, the road it joins, on its approach or as the "then" of
                    // the step before. At the merge itself there is nothing to choose.
                    val mergeSaid = target.type == ManeuverType.MERGE && 1 in spoken && nextIdx < 0 && laterIdx < 0
                    // The shorter modes said their one line on the approach; the buzz below stays.
                    // A town turn whose near line was said has nothing more to say at the corner.
                    val cornerSaid = quietCorner && 1 in spoken
                    if (detail == SpokenDetail.Mode.FULL && !ringSaid && !mergeSaid && !cornerSaid && TURN_NOW_SAID_SLOT !in spoken) {
                        events += NavEvent.Speak(turnNowLine(withStop(turnText)), interrupt = true)
                    }
                    events += NavEvent.Haptic(target.type) // firm, direction-coded buzz at the turn
                }
                // The line at the turn said the turn: no approach line after it.
                spoken = spoken + TURN_NOW_SLOT + setOf(0, 1)
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
        // The stop's own approach cue, when no maneuver line named it: "In 500 feet, Davis Food
        // Co-op will be on your right", at the near distance, like the destination's. Said once
        // per stop, in every voice setting (the stop is an arrival), never off route, and never
        // on top of another line in the same fix (the next fix still has it in range).
        var stopCuedAt = if (stopCuedNow && stop != null) stop.atM else state.stopCuedAtM
        if (stop != null && stopCuedAt != stop.atM && !offRoute && events.none { it is NavEvent.Speak }) {
            val toStop = stop.atM - traveled
            if (toStop <= nearM && toStop > ARRIVE_RADIUS_M * 2) {
                val sayM = (if (toStop >= nearM * 0.85) nearM else round10(toStop)).coerceAtLeast(10.0)
                events += NavEvent.Speak(nav().inThen(spokenDistance(sayM, imperial), nav().stopAhead(stop.label, stopLeft)))
                stopCuedAt = stop.atM
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
            stopCuedAtM = stopCuedAt,
            stillFixes = stillFixes,
            chainedStep = if (chainedNow) chainedIdx else state.chainedStep,
            // Named on a near line, the turn after keeps its own near line, said after this corner.
            // A town turn that will say nothing at its corner keeps its near line however it was named.
            chainedBands = if (!chainedNow) state.chainedBands
            else if (chainedOnNear || (cornerQuiet && !trackRide && townTurn(maneuvers, chainedIdx, v))) (if (chainedIdx == laterIdx) laterBands - 1 else setOf(0))
            else (if (chainedIdx == laterIdx) laterBands else setOf(0, 1)) +
                (if (thenAtTurn && chainRepeatsSoon(maneuvers, manAlong, idxCur, chainedIdx, dtn, turnNowM, v, thenSaid)) setOf(TURN_NOW_SAID_SLOT) else emptySet()),
        )
        return newState to events
    }

    /** How far past the last maneuver before a stop the stop may sit for that maneuver to say it
     *  ("..., then <stop> will be on your right"): "then" means soon. */
    const val STOP_THEN_M = 300.0
    /** How far before a stop, or the end of a trip, its turn into the parking lot may be, and how
     *  many bare turns back from there are looked at for it. */
    const val LOT_DEST_BACK_M = 400.0
    const val LOT_DEST_TURNS = 3
    /** A stop's pin this close to the route line is on the road: "ahead", not a side. */
    private const val SIDE_MIN_OFF_M = 8.0
    /** The line is read this far either side of the stop's mark for the side test. */
    private const val SIDE_SPAN_M = 25.0
    /** More bend than this through the mark and the side is not said (a corner, a U-turn). */
    private const val SIDE_MAX_BEND_DEG = 45.0

    /** How far past a town turn the car is before the next turn's first line is said
     *  ([update]'s cornerQuiet): the line comes once the turn is made, not inside it. */
    const val CORNER_CLEAR_M = 15.0

    private val TOWN_TURN_TYPES = setOf(
        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT,
        ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
    )

    /** Step [i] is a town turn at [speedMps]: a plain left or right (slight and sharp too) that is
     *  not given the highway's one approach line ([oneApproach]) and is not the turn onto an
     *  on-ramp ([onRampTurn]). With [update]'s cornerQuiet it says two lines, as Google's does:
     *  far with its distance, near, and none at the corner. */
    internal fun townTurn(ms: List<Maneuver>, i: Int, speedMps: Double): Boolean =
        i in ms.indices && ms[i].type in TOWN_TURN_TYPES && !oneApproach(ms, i, speedMps) && !onRampTurn(ms, i)

    /** Whether step [i], approached at [speedMps], gets one approach line instead of two in the full
     *  voice: anything at [SpokenDetail.FAST_MPS] or more, and an exit, fork, keep, U-turn or the
     *  turn onto an on-ramp ([onRampTurn]) from [EXIT_ONE_LINE_MPS], so a car near 50 mph on the
     *  way to an exit is not given far, near and at the turn by turns. */
    internal fun oneApproach(ms: List<Maneuver>, i: Int, speedMps: Double): Boolean =
        speedMps >= SpokenDetail.FAST_MPS ||
            speedMps >= EXIT_ONE_LINE_MPS && (SpokenDetail.exitLike(ms[i].type) || onRampTurn(ms, i))

    /** A turn onto a nameless road whose next spoken step, within 600 m, is a merge: the turn onto
     *  an on-ramp (`RouteGeometry.rampTurns` words it "Take the ramp"). */
    internal fun onRampTurn(ms: List<Maneuver>, i: Int): Boolean {
        val m = ms[i]
        if (turnLeftOf(m.type) == null || !m.road.isNullOrBlank() || !m.ref.isNullOrBlank() || m.distanceMeters > 600.0) return false
        var j = i + 1
        while (j < ms.size && quiet(ms, j)) j++
        return j < ms.size && ms[j].type == ManeuverType.MERGE
    }

    /** The voice says nothing for maneuver [i] in any setting: a continue or a straight-on with no
     *  real fork in its lanes, or a roundabout's own exit ([ringExit]). */
    private fun quiet(ms: List<Maneuver>, i: Int): Boolean {
        val m = ms[i]
        return (m.type == ManeuverType.CONTINUE || m.type == ManeuverType.STRAIGHT) &&
            !app.vela.core.model.continueHasGenuineFork(m.lanes) || ringExit(ms, i)
    }

    /**
     * The exit step of the roundabout just entered. Its entry already said which exit to take,
     * and both carry the same words, so the exit step speaking was the roundabout said twice and
     * a long line cut off inside the ring; as the "then" of the entry it named the same
     * roundabout again. The turn card's "then" row passes over it too. An exit step whose entry
     * is not the maneuver before it (a route that starts inside the ring) still speaks.
     */
    internal fun ringExit(ms: List<Maneuver>, i: Int): Boolean =
        i > 0 && ms[i].type == ManeuverType.EXIT_ROUNDABOUT && ms[i - 1].type == ManeuverType.ROUNDABOUT

    /** The maneuver whose leg the stop at [atM] sits on, among those the voice speaks (a silent
     *  rename on the way is passed over), when the stop is within [STOP_THEN_M] of it; else -1. */
    internal fun stopLegManeuver(route: Route, manAlong: DoubleArray, atM: Double): Int {
        val ms = route.maneuvers
        var k = -1
        for (i in ms.indices) { if (manAlong[i] <= atM) k = i else break }
        while (k > 0 && quiet(ms, k)) k--
        if (k <= 0 || ms[k].type == ManeuverType.ARRIVE || ms[k].type == ManeuverType.DEPART) return -1
        return if (atM - manAlong[k] <= STOP_THEN_M) k else -1
    }

    /** Two turns this close along the road are said on one line ([chainedNext]): a town block. */
    const val CHAIN_M = 130.0

    /** The next maneuver the voice speaks after [k], when it is within [CHAIN_M] of it and is not
     *  the arrival (which has lines of its own); else -1. */
    internal fun chainedNext(ms: List<Maneuver>, manAlong: DoubleArray, k: Int, chainM: Double = CHAIN_M): Int {
        var j = k + 1
        while (j < ms.size && quiet(ms, j)) j++
        if (j >= ms.size || ms[j].type == ManeuverType.ARRIVE || ms[j].type == ManeuverType.DEPART) return -1
        return if (manAlong[j] - manAlong[k] <= chainM) j else -1
    }

    /** True when step [j], named as "then [said]" on the turn-now line of step [k], would say the
     *  same words at its own turn-now point within [CHAIN_REPEAT_S] of now ([dtn] meters short of
     *  [k], at [speedMps]). */
    internal fun chainRepeatsSoon(
        ms: List<Maneuver>, manAlong: DoubleArray, k: Int, j: Int, dtn: Double, turnNowM: Double,
        speedMps: Double, said: String?,
    ): Boolean {
        if (said == null || j !in ms.indices) return false
        val own = short(ms[j], ms[j].spokenInstruction())
        // A "then" that left out the route the line before it had just said counts as the same words.
        if (!own.equals(said, ignoreCase = true) && !nav().repeatShort(ms[j].spokenInstruction()).equals(said, ignoreCase = true)) return false
        val toOwnNow = dtn + (manAlong[j] - manAlong[k]) - turnNowM
        return toOwnNow / speedMps.coerceAtLeast(MIN_PACE_MPS) < CHAIN_REPEAT_S
    }

    /** A track ride's "then" distance: a cyclist's town block, where [CHAIN_M] is a car's. */
    const val TRACK_CHAIN_M = 60.0
    /** The approach bands' floors on a track ride: at riding speed the 35 s and 10 s bands are
     *  well under a car's 400 m and 150 m floors, and those floors made nearly every turn's first
     *  line due the moment the turn before it was made, so it was chained ("Turn left, then turn
     *  right" with the right 300 m on). */
    const val TRACK_FAR_MIN_M = 150.0
    const val TRACK_NEAR_MIN_M = 50.0

    /** The far approach band at [v] m/s: about 35 s out, at least 400 m (a track ride: [TRACK_FAR_MIN_M]). */
    internal fun farBand(v: Double, trackRide: Boolean = false) = maxOf(if (trackRide) TRACK_FAR_MIN_M else 400.0, round50(v * 35.0))
    /** The approach bands (0 far, 1 near) a turn [toTurnM] ahead is already inside at [v] m/s:
     *  the ones the engine counts as said when it says that turn's line there. */
    internal fun bandsWithin(toTurnM: Double, v: Double, trackRide: Boolean = false): Set<Int> =
        setOfNotNull(0.takeIf { toTurnM <= farBand(v, trackRide) }, 1.takeIf { toTurnM <= nearBand(v, trackRide) })

    /** The near approach band at [v] m/s: about 10 s out, at least 150 m (a track ride: [TRACK_NEAR_MIN_M]). */
    internal fun nearBand(v: Double, trackRide: Boolean = false) = maxOf(if (trackRide) TRACK_NEAR_MIN_M else 150.0, round50(v * 10.0))

    /** A car's speed through a town turn, m/s: [upcomingPrompts] also places the next step's first
     *  line as if the turn before it were taken at this speed. */
    private const val TURN_PACE_MPS = 5.0

    /** The top of town speeds, m/s (54 km/h): the fastest a town turn's line is expected at. */
    private const val TOWN_PACE_MPS = 15.0

    /** A "then" past [CHAIN_M] means soon: never for a step further than this. */
    const val THEN_SOON_M = 500.0

    /** The next maneuver after [k] whose first prompt is due the moment [k] is made: within
     *  [dueM] (the far band plus what the car covers in BAND_MERGE_S) and [THEN_SOON_M], not the
     *  arrival or a roundabout; else -1. */
    internal fun dueNext(ms: List<Maneuver>, manAlong: DoubleArray, k: Int, dueM: Double): Int {
        var j = k + 1
        while (j < ms.size && quiet(ms, j)) j++
        if (j >= ms.size || ms[j].type == ManeuverType.ARRIVE || ms[j].type == ManeuverType.DEPART) return -1
        // A roundabout's entry and exit lines count exits and stay its own.
        val rings = setOf(ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT)
        if (ms[k].type in rings || ms[j].type in rings) return -1
        val gap = manAlong[j] - manAlong[k]
        return if (gap <= dueM && gap <= THEN_SOON_M) j else -1
    }

    /** "left" for a left turn of any sharpness, "right" for a right one, null otherwise. */
    private fun turnLeftOf(type: ManeuverType): Boolean? = when (type) {
        ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> true
        ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT -> false
        else -> null
    }

    /**
     * The turns that close the way to [endM] along [route] (a stop's mark, or the end of the
     * line) and might be its way into a parking lot, for the map lookup that decides it
     * ([ParkingLotTurn]), in the order driven: each one's index among the maneuvers, its meters
     * along the line, and whether it is a left. A way into a lot usually ends with the turn off
     * the street and then a turn or two between the aisles, all of them bare, so the candidates
     * are the lefts and rights onto a road with no name and no number that run unbroken up to
     * [endM]: at most [LOT_DEST_TURNS] of them, none more than [LOT_DEST_BACK_M] before it. The
     * first whose way on enters a lot is the turn off the street.
     */
    fun lotTurnsBefore(route: Route, endM: Double): List<Triple<Int, Double, Boolean>> {
        val ms = route.maneuvers
        if (route.polyline.size < 2 || ms.size < 3) return emptyList()
        val man = geomFor(route).manAlong
        var k = (ms.lastIndex - 1 downTo 1).firstOrNull { man[it] <= endM } ?: return emptyList()
        val out = ArrayList<Triple<Int, Double, Boolean>>()
        while (k > 0 && out.size < LOT_DEST_TURNS) {
            if (endM - man[k] > LOT_DEST_BACK_M) break
            val m = ms[k]
            if (!quiet(ms, k)) {
                val left = turnLeftOf(m.type) ?: break
                if (!m.road.isNullOrBlank() || !m.ref.isNullOrBlank()) break
                out += Triple(k, man[k], left)
            }
            k--
        }
        return out.asReversed()
    }

    /** [lotTurnsBefore] the end of the line: the way into the destination's own lot. */
    fun destinationLotTurns(route: Route): List<Triple<Int, Double, Boolean>> {
        if (route.polyline.size < 2 || route.maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE) return emptyList()
        return lotTurnsBefore(route, geomFor(route).cum.last())
    }

    /**
     * Which side of the road the stop at [stop] is on for a car driving [route] past its mark
     * [atM]: "left" or "right", from where its pin sits against the line's direction there (the
     * test the open router uses for the destination's own side), or null when that cannot be told
     * honestly: the pin is on the line (under [SIDE_MIN_OFF_M]), or the line bends more than
     * [SIDE_MAX_BEND_DEG] through the mark (a corner, a turn into the lot at the stop itself).
     */
    /**
     * [route] with a side on its arrival when the router left it out. Only OSRM and Valhalla put
     * one on their arrive step; the on-phone router and routes named from Google's line ended in a
     * bare arrival, so "Your destination is on the right" came on some drives and "You have
     * arrived" on others. The side is the destination pin [dest] against the line's last
     * [SIDE_SPAN_M], as [stopSide] reads a stop, and the step's words say it too. A route with a
     * side, or a pin on the line, comes back as it was.
     */
    fun withArrivalSide(route: Route, dest: LatLng?): Route {
        val last = route.maneuvers.lastOrNull() ?: return route
        if (dest == null || last.type != ManeuverType.ARRIVE || last.side != null || route.legs.isEmpty()) return route
        val total = geomFor(route).cum.lastOrNull() ?: return route
        val side = stopSide(route, dest, total) ?: return route
        val text = app.vela.core.data.RouteGeometry.osrmPhrase("arrive", side, null, null, null, null)
        val arrive = last.copy(side = side, instruction = text, instructionNoRoad = text)
        val leg = route.legs.last()
        return route.copy(legs = route.legs.dropLast(1) + leg.copy(maneuvers = leg.maneuvers.dropLast(1) + arrive))
    }

    fun stopSide(route: Route, stop: LatLng, atM: Double): String? {
        val poly = route.polyline
        if (poly.size < 2) return null
        val cum = geomFor(route).cum
        val total = cum.last()
        val p = RouteProjection.pointAt(poly, cum, atM)
        if (p.distanceTo(stop) < SIDE_MIN_OFF_M) return null
        val a = RouteProjection.pointAt(poly, cum, (atM - SIDE_SPAN_M).coerceAtLeast(0.0))
        val b = RouteProjection.pointAt(poly, cum, (atM + SIDE_SPAN_M).coerceAtMost(total))
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        fun dx(u: LatLng, w: LatLng) = (w.lng - u.lng) * kx
        fun dy(u: LatLng, w: LatLng) = (w.lat - u.lat) * 111_320.0
        val inLen = hypot(dx(a, p), dy(a, p))
        val outLen = hypot(dx(p, b), dy(p, b))
        if (inLen < 3.0 && outLen < 3.0) return null
        if (inLen >= 3.0 && outLen >= 3.0) {
            val turn = Math.toDegrees(kotlin.math.atan2(dx(a, p) * dy(p, b) - dy(a, p) * dx(p, b), dx(a, p) * dx(p, b) + dy(a, p) * dy(p, b)))
            if (kotlin.math.abs(turn) > SIDE_MAX_BEND_DEG) return null
        }
        // The direction of travel through the mark, and the pin against it: positive is to the left.
        val cross = dx(a, b) * dy(p, stop) - dy(a, b) * dx(p, stop)
        return if (cross > 0) "left" else "right"
    }

    /** The active language's nav strings (spoken frame + distance + arrival), English by default. */
    /** The lines [update] speaks for the first [count] turns of [route] when they come up at
     *  starting speed (the approach bands are then their 400 m / 150 m floors): each turn's far and
     *  near approach prompts and its turn-now line, in the same wording [update] builds. The voice
     *  prepares them while the route preview is up, so the first prompts of a drive need no
     *  synthesis while the camera flies in. A line this misses (a real distance on a short step, a
     *  higher speed) is simply synthesized when spoken, as before. */
    fun startPrompts(route: Route, imperial: Boolean, count: Int = 2, trackRide: Boolean = false, cornerQuiet: Boolean = false): List<String> =
        upcomingPrompts(route, fromStep = 1, spoken = emptySet(), speedMps = 0.0, imperial = imperial, count = count, nearestFirst = false, trackRide = trackRide, cornerQuiet = cornerQuiet)

    /**
     * The lines [update] is about to speak, from the turn at [fromStep] on, for the voice to get
     * ready ahead of time during the drive: the first [count] spoken turns' far and near approach
     * prompts at [speedMps]'s band distances and their turn-now lines, in [update]'s own wording.
     * The CURRENT turn's lines lead, its turn-now line first: that is the one with no time to
     * spare, and it names no distance, so it always matches. Bands in [spoken] (already said for
     * the current turn) are left out. A turn said on the line before it ("then") has that line
     * prepared, and its own lines are the ones update still says. A line this misses (a real
     * distance on a short step, a speed that has moved a band) is synthesized when spoken.
     */
    fun upcomingPrompts(
        route: Route, fromStep: Int, spoken: Set<Int>, speedMps: Double, imperial: Boolean, count: Int = 2,
        nearestFirst: Boolean = true, trackRide: Boolean = false, toTurnM: Double = Double.NaN,
        chainedStep: Int = -1, chainedStepBands: Set<Int> = emptySet(), cornerQuiet: Boolean = false,
    ): List<String> {
        val ms = route.maneuvers
        val manAlong = geomFor(route).manAlong
        val out = LinkedHashSet<String>()
        var taken = 0
        val far = farBand(speedMps, trackRide)
        val near = nearBand(speedMps, trackRide)
        val pace = speedMps.coerceAtLeast(MIN_PACE_MPS)
        val turnNowM = (speedMps * 2.5).coerceIn(ARRIVE_RADIUS_M, 90.0)
        val detail = SpokenDetail.mode
        fun bandsOf(k: Int): List<Double> =
            if (detail == SpokenDetail.Mode.FULL && oneApproach(ms, k, speedMps)) maxOf(near, round50(speedMps * FAST_APPROACH_S)).let { listOf(it, it) }
            else listOf(far, near)
        // A turn said as the "then" of the one before: the bands that line counts as said for it.
        // The drive's own ([NavState.chainedStep]) to begin with, then the ones predicted here.
        var chainedInto = chainedStep
        var chainedBands = chainedStepBands
        for (i in fromStep.coerceAtLeast(1) until ms.size) {
            if (taken >= count) break
            val m = ms[i]
            val said = when {
                // As update takes it: a step said as the "then" of the one before starts with
                // that line's bands as said, from the first fix that looks at it.
                i == fromStep -> if (spoken.isEmpty() && chainedInto == i) chainedBands else spoken
                i == chainedInto -> chainedBands
                else -> emptySet()
            }
            val chainedHere = i == chainedInto
            // How far from turn i the car is when update first looks at it: the step in hand a
            // second on from [toTurnM]; a later step just past the turn before it, which moves the
            // step on its advance distance short of that turn, a fix (about a second) later, at
            // this speed or, through a left or right turn, slowed to [TURN_PACE_MPS]. A short
            // step's first line says that distance, so both ends of the second are tried. A step
            // in hand whose lines have started: its next band, wherever the car is now.
            val legM = manAlong[i] - manAlong[i - 1]
            val entries = when {
                i != fromStep -> {
                    val v = if (turnLeftOf(ms[i - 1].type) != null) minOf(pace, TURN_PACE_MPS) else pace
                    val adv = (v * 2.5).coerceIn(ADVANCE_MIN_M, 90.0)
                    // Past a quiet corner the first line waits until the car is clear of it.
                    if (cornerQuiet && !trackRide && townTurn(ms, i - 1, speedMps)) listOf(legM - CORNER_CLEAR_M, legM - CORNER_CLEAR_M - v)
                    else listOf(legM + adv - v, legM + adv - 2 * v)
                }
                said.isNotEmpty() -> listOf(Double.POSITIVE_INFINITY)
                toTurnM.isNaN() -> listOf(legM)
                else -> listOf(toTurnM - 0.5 * pace, toTurnM - 1.5 * pace)
            }.map { it.coerceAtLeast(0.0) }.distinct()
            // The distance an approach line at [d] says: its band crossed, or where the car is.
            fun sayAt(d: Double, band: Double) = (if (d >= band * 0.85) band else round10(d)).coerceAtLeast(10.0)
            if (m.type == ManeuverType.ARRIVE) {
                // The destination's one approach line at the near distance and the line on
                // arriving, as update says them. A stop on the way has lines of its own.
                val arriveNear = if (detail == SpokenDetail.Mode.FULL) bandsOf(i)[1] else near
                val sayAhead = if (detail == SpokenDetail.Mode.FULL) 1 !in said else said.isEmpty()
                if (sayAhead && SpokenDetail.speaks(detail, m.type, speedMps)) for (e in entries) {
                    val d = minOf(e, arriveNear)
                    if (d > ARRIVE_RADIUS_M * 2) {
                        out += nav().inThen(spokenDistance(sayAt(d, arriveNear), imperial), nav().destinationWillBe(when (m.side) { "left" -> true; "right" -> false; else -> null }))
                    }
                }
                if (i == ms.lastIndex) out += m.side?.let { nav().destinationSide(left = it == "left") } ?: nav().arrived().trim().trimEnd(';')
                break
            }
            if (quiet(ms, i)) continue
            taken++
            // The turns update says on this one's lines (see update: TWO TURNS TOO CLOSE, FURTHER ON).
            val nextIdx = if (m.type == ManeuverType.DEPART) -1 else chainedNext(ms, manAlong, i, if (trackRide) TRACK_CHAIN_M else CHAIN_M)
            val laterIdx = if (nextIdx >= 0 || trackRide || m.type == ManeuverType.DEPART) -1 else dueNext(ms, manAlong, i, far + pace * BAND_MERGE_S)
            // Whether the step after is due at once depends on the speed at the line at the turn,
            // anywhere from a car slowed for it ([TURN_PACE_MPS]) to town speed ([TOWN_PACE_MPS])
            // or this speed if faster: both answers are prepared when they differ.
            fun dueAt(v: Double) = if (nextIdx >= 0 || trackRide || m.type == ManeuverType.DEPART) -1
                else dueNext(ms, manAlong, i, farBand(v, trackRide) + v * BAND_MERGE_S)
            val laterSlow = listOf(dueAt(TURN_PACE_MPS), dueAt(maxOf(pace, TOWN_PACE_MPS))).firstOrNull { it != laterIdx } ?: laterIdx
            fun gapTo(k: Int) = manAlong[k] - manAlong[i]
            if (detail != SpokenDetail.Mode.FULL) {
                // The one line the shorter modes say (see update); nothing else is worth preparing.
                if (said.isEmpty() && SpokenDetail.speaks(detail, m.type, speedMps)) {
                    val band = if (SpokenDetail.exitLike(m.type) || speedMps >= SpokenDetail.FAST_MPS) far else near
                    val line = if (SpokenDetail.exitLike(m.type)) nav().repeatShort(m.spokenInstruction())
                        else nav().repeatShort(m.instructionNoRoad ?: m.spokenInstruction())
                    for (e in entries) {
                        val d = minOf(e, band)
                        val joined = if (nextIdx >= 0) joinNext(m, ms[nextIdx], line, d + gapTo(nextIdx), detail).first else line
                        out += nav().inThen(spokenDistance(sayAt(d, band), imperial), joined)
                        if (joined != line) { chainedInto = nextIdx; chainedBands = setOf(0, 1) }
                    }
                }
                continue
            }
            val lane = app.vela.core.model.laneGuidance(m.lanes)
            // update's cornerQuiet: a town turn's near line names the turn after, and it says
            // nothing at the corner.
            val quiet = cornerQuiet && !trackRide && townTurn(ms, i, speedMps)
            val quietNext = if (nextIdx >= 0) nextIdx else laterIdx
            var joinedNear = false
            val full = if (lane != null) nav().useLanesToDo(lane.side, lane.count, nav().spokenSign(m.spokenInstruction()))
                else nav().spokenSign(m.spokenInstruction())
            val brief = nav().repeatShort(m.spokenInstruction())
            val bands = bandsOf(i)
            // An approach line [d] out saying [sayM], as update's say() builds it: the step's first
            // line takes its lanes or the turn after it.
            var joinedFirst = false
            fun say(d: Double, sayM: Double, cueLine: CueLine, first: Boolean, instr: String): String {
                val cue = lightCue(m, d, cueLine)
                val wrapped = when {
                    quiet && cueLine == CueLine.NEAR && quietNext >= 0 -> {
                        val own = if (first && lane != null) instr else withLight(cue.takeIf { it == LightCue.AT_LIGHT || it == LightCue.AT_STOP }, instr)
                        joinNext(m, ms[quietNext], own, d + gapTo(quietNext), detail).first.also { if (it != own) joinedNear = true }
                    }
                    first && lane != null -> instr
                    first && nextIdx >= 0 -> {
                        val own = withLight(cue.takeIf { it == LightCue.AT_LIGHT || it == LightCue.AT_STOP }, instr)
                        joinNext(m, ms[nextIdx], own, d + gapTo(nextIdx), detail).first.also { if (it != own) joinedFirst = true }
                    }
                    else -> withLight(cue.takeIf { it != LightCue.NEXT_STOP }, instr)
                }
                val nextStop = cue == LightCue.NEXT_STOP && !(first && (lane != null || nextIdx >= 0)) &&
                    !(quiet && cueLine == CueLine.NEAR && quietNext >= 0)
                return when {
                    nextStop -> withLight(LightCue.NEXT_STOP, instr)
                    quiet && cueLine == CueLine.NEAR -> wrapped
                    else -> nav().inThen(spokenDistance(sayM, imperial), wrapped)
                }
            }
            // Update's approach lines replayed from the car's first look at the step ([entries])
            // down to the turn: a line where a band is first due, at the real distance when the car
            // is already well inside it, and the bands the line counts as said.
            val lines = ArrayList<String>()
            var nearAlways = true // the near band said before the turn, wherever the car came in
            var saidSome = false
            var saidNone = false
            for (e in entries) {
                val sp = said.toMutableSet()
                fun at(d: Double) {
                    val due = bands.withIndex().filter { (slot, b) -> d <= b && slot !in sp && (m.type != ManeuverType.MERGE || slot == 1) }
                    if (due.isEmpty()) return
                    val first = sp.isEmpty()
                    sp += due.map { it.index }
                    val sayM = sayAt(d, due.last().value)
                    val nearLine = due.last().index == 1 || bands[1] < sayM && (sayM - bands[1]) / pace < BAND_MERGE_S
                    val cueLine = if (nearLine) CueLine.NEAR else CueLine.FAR
                    var line = say(d, sayM, cueLine, first, if (first) full else brief)
                    // Still playing at the line at the turn: the short form, as update cuts it.
                    if (first && (d - turnNowM) / pace < line.length / SPEECH_CPS + SPEECH_LEAD_S) {
                        line = say(d, sayM, cueLine, true, if (lane != null) nav().useLanesToDo(lane.side, lane.count, brief) else brief)
                    }
                    lines += line
                    sp += bands.withIndex().filter { (slot, b) -> slot !in sp && b < sayM && (sayM - b) / pace < BAND_MERGE_S }.map { it.index }
                }
                if (e.isFinite()) at(e)
                bands.distinct().sortedDescending().forEach { b -> if (b < e) at(b) }
                if (1 !in sp) nearAlways = false
                if (sp.isEmpty()) saidNone = true else saidSome = true
            }
            if (joinedFirst) { chainedInto = nextIdx; chainedBands = setOf(0, 1) }
            if (joinedNear) {
                chainedInto = quietNext
                chainedBands = if (quietNext == nextIdx) setOf(0)
                    else bandsOf(quietNext).withIndex().filter { (_, d) -> (gapTo(quietNext) - d) / pace < BAND_MERGE_S }.map { it.index }.toSet() - 1
            }
            // Its near line said wherever the car came in, a quiet town turn says nothing more.
            if (quiet && nearAlways) {
                out += (if (i == fromStep && nearestFirst) lines.reversed() else lines).distinct()
                continue
            }
            // The line at the turn repeats short once an approach line said the whole instruction.
            val nows = listOfNotNull(
                short(m, m.spokenInstruction()).takeIf { saidSome },
                nav().spokenSign(m.spokenInstruction()).takeIf { saidNone },
            ).map { withLight(lightCue(m, 0.0, CueLine.NOW), it) }.distinct()
            val idxs = if (nextIdx >= 0) listOf(nextIdx) else listOf(laterIdx, laterSlow).distinct()
            val ringSaid = m.type == ManeuverType.ROUNDABOUT && nearAlways && !chainedHere && idxs.all { it < 0 }
            val mergeSaid = m.type == ManeuverType.MERGE && nearAlways && idxs.all { it < 0 }
            if (TURN_NOW_SLOT !in said && TURN_NOW_SAID_SLOT !in said && !ringSaid && !mergeSaid) {
                var alwaysChained = true
                for (k in idxs) {
                    if (k < 0) { lines += nows; alwaysChained = false; continue }
                    // Whether the pair fits depends on where the fix lands, up to a fix's travel
                    // inside the turn-now point: both ends are prepared when they differ.
                    val gap = gapTo(k)
                    val joins = nows.flatMap { now ->
                        listOf(turnNowM, (turnNowM - pace).coerceAtLeast(0.0)).map { dtn ->
                            dtn to joinAtTurn(m, ms[k], now, dtn + gap, dtn + gap - turnNowM, speedMps, detail, true)
                        }
                    }
                    joins.forEach { (_, j) -> lines += j.line }
                    if (joins.none { it.second.chained }) { alwaysChained = false; continue }
                    // The step after counts its lines as said only when every form here names it.
                    if (!alwaysChained) continue
                    chainedInto = k
                    val repeats = joins.all { (dtn, j) -> j.atTurn && chainRepeatsSoon(ms, manAlong, i, k, dtn, turnNowM, speedMps, j.thenSaid) }
                    chainedBands = if (cornerQuiet && !trackRide && townTurn(ms, k, speedMps)) {
                        if (k == nextIdx) setOf(0) else bandsOf(k).withIndex().filter { (_, d) -> (gap - d) / pace < BAND_MERGE_S }.map { it.index }.toSet() - 1
                    } else (
                        if (k == nextIdx || ms[k].type == ManeuverType.MERGE) setOf(0, 1)
                        else bandsOf(k).withIndex().filter { (_, d) -> (gap - d) / pace < BAND_MERGE_S }.map { it.index }.toSet()
                    ) + (if (repeats) setOf(TURN_NOW_SAID_SLOT) else emptySet())
                }
                if (!alwaysChained && chainedInto in idxs) chainedInto = -1
            }
            // The turn in hand: nearest line first. A later turn: in the order it will be spoken.
            out += (if (i == fromStep && nearestFirst) lines.reversed() else lines).distinct()
        }
        return out.toList()
    }

    /** The landmark cue a line gets ([lightCue]). */
    internal enum class LightCue { GO_PAST, AT_LIGHT, AT_STOP, NEXT_STOP }

    /** Which of a step's lines a cue is asked for: an approach line said before the near one, the
     *  near approach line, the line at the turn, or the "then" of the line before. */
    internal enum class CueLine { FAR, NEAR, NOW, THEN }

    /**
     * The traffic-light or stop-sign cue for [line] of turn [m], spoken [dtn] meters short of it
     * (SPEC 4.6, Guidance). A light counts as ahead when the car has at least
     * [LIGHT_AHEAD_MIN_M] to go to it, so one already passed, or the one the car is at, never
     * counts. Nothing on a line further out than [LIGHT_SPEAK_MAX_M], as far back as lights and
     * signs are marked.
     * - A turn at a light of its own (`Maneuver.lightAtTurn`): [LightCue.AT_LIGHT], "At the light,
     *   turn left", on the near line, the line at the turn and a "then". Not on a far line.
     * - A turn at a stop sign (`Maneuver.stopAtTurn`): [LightCue.AT_STOP] on those same lines, and
     *   on a far line [LightCue.NEXT_STOP], "At the next stop sign, turn left", when no other stop
     *   sign comes first.
     * - Otherwise, exactly one light between the car and the turn, at most [GO_PAST_MAX_M] before
     *   the turn: [LightCue.GO_PAST], "Go past this light, then turn left", on the near line.
     * A language without a phrase gets no cue of that kind; one with no "at the light" says "go
     * past" where it can.
     */
    internal fun lightCue(m: Maneuver, dtn: Double, line: CueLine): LightCue? {
        if (m.lightsBeforeM.isEmpty() && !m.lightAtTurn && m.stopsBeforeM.isEmpty() && !m.stopAtTurn) return null
        if (dtn > LIGHT_SPEAK_MAX_M) return null
        val n = nav()
        if (m.lightAtTurn && n.atLight("") != null) return if (line == CueLine.FAR) null else LightCue.AT_LIGHT
        if (m.stopAtTurn && !m.lightAtTurn) {
            if (line != CueLine.FAR) return if (n.atStop("") != null) LightCue.AT_STOP else null
            val stopFirst = m.stopsBeforeM.any { it <= dtn - LIGHT_AHEAD_MIN_M }
            return if (!stopFirst && n.atNextStop("") != null) LightCue.NEXT_STOP else null
        }
        if (line != CueLine.NEAR) return null
        val ahead = m.lightsBeforeM.filter { it <= dtn - LIGHT_AHEAD_MIN_M }
        return if (ahead.size == 1 && ahead[0] <= GO_PAST_MAX_M && n.passLights(1).isNotBlank()) LightCue.GO_PAST else null
    }

    /** [text] with [cue]'s words in front ([lightCue]). */
    internal fun withLight(cue: LightCue?, text: String): String = when (cue) {
        LightCue.GO_PAST -> nav().lightThen(nav().passLights(1), text)
        LightCue.AT_LIGHT -> nav().atLight(text) ?: text
        LightCue.AT_STOP -> nav().atStop(text) ?: text
        LightCue.NEXT_STOP -> nav().atNextStop(text) ?: text
        null -> text
    }

    /** [text] with the cue [line] of [m] gets [dtn] meters short of it. */
    internal fun lightLead(m: Maneuver, dtn: Double, text: String, line: CueLine = CueLine.NEAR): String = withLight(lightCue(m, dtn, line), text)

    /** A light counts as ahead when the car has at least this far to go to it. */
    private const val LIGHT_AHEAD_MIN_M = 20.0
    /** "Go past this light" only for a light this close before the turn. */
    const val GO_PAST_MAX_M = 200.0
    /** No light cue on a line spoken further out than this, how far back lights are marked
     *  (`RouteGeometry.LIGHT_APPROACH_M`). */
    const val LIGHT_SPEAK_MAX_M = 800.0

    private fun nav() = app.vela.core.i18n.NavStringsRegistry.current()

    /** Ramps, forks and keeps: their short lines keep the first route number on the sign. */
    private val SIGN_STEPS = setOf(
        ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT,
        ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
    )

    /** The short form of [text], step [m]'s instruction, for its line at the turn and for a "then"
     *  that names it: a ramp, fork or keep keeps the route it heads for ("Keep slight right toward
     *  US 50 East"), so the voice names the road the banner shows at the moment it matters; anything
     *  else drops its sign ([NavStrings.repeatShort]). The approach repeats stay without it. */
    private fun short(m: Maneuver, text: String): String =
        if (m.type in SIGN_STEPS) nav().repeatWithRoute(text) else nav().repeatShort(text)

    /** [part], step [next]'s words in a "then", with the lanes it needs in front: "then use the left
     *  lane to keep slight left". A chained step's own approach lines, which would say them, are
     *  taken as said. */
    private fun withLanes(next: Maneuver, part: String): String =
        app.vela.core.model.laneGuidance(next.lanes)?.let { nav().useLanesToDo(it.side, it.count, part) } ?: part

    /** [short] for step [next] as the "then" of [cur]'s line, without a route [cur] already named:
     *  "Keep slight left toward I 80 West, then keep slight left", not the route twice. */
    private fun thenShort(cur: Maneuver, next: Maneuver, text: String): String {
        fun route(m: Maneuver) = nav().signParts(m.spokenInstruction()).firstNotNullOfOrNull { routeKey(it) }
        val r = route(next)
        return if (r != null && r == route(cur)) nav().repeatShort(text) else short(next, text)
    }

    /**
     * [line], turn [cur]'s words, with turn [next] after it as a "then", [nextDtn] meters short of
     * [next], and the words the "then" gave [next] without its lanes or cue, which
     * [chainRepeatsSoon] compares with [next]'s own line at its turn. In the shorter settings
     * [next] loses its street name too, as its own line would. "then at the light, turn left" is
     * the full voice's only, and not with lanes to say. The joined line is [line] unchanged when
     * the language has no "then".
     */
    private fun joinNext(cur: Maneuver, next: Maneuver, line: String, nextDtn: Double, detail: SpokenDetail.Mode): Pair<String, String> {
        val nextText = if (detail == SpokenDetail.Mode.FULL || SpokenDetail.exitLike(next.type)) next.spokenInstruction()
        else next.instructionNoRoad ?: next.spokenInstruction()
        val plain = thenShort(cur, next, nextText)
        val cued = if (detail == SpokenDetail.Mode.FULL && app.vela.core.model.laneGuidance(next.lanes) == null)
            withLight(lightCue(next, nextDtn, CueLine.THEN), plain) else plain
        return nav().thenNext(line, withLanes(next, cued)) to plain
    }

    /** What [joinAtTurn] said: the [line], whether it named the next turn ([chained]), the words it
     *  gave that turn ([thenSaid]), and whether they were said in full at the turn ([atTurn]). */
    private class TurnJoin(val line: String, val chained: Boolean, val thenSaid: String?, val atTurn: Boolean)

    /**
     * Turn [cur]'s line at the turn ([plain]) with turn [next] on it, [nextDtn] meters short of
     * [next] and [toNextNow] meters before [next]'s own line at its turn, at [v] m/s. [next] says
     * that line interrupting, so the pair has to be over by then: when the whole pair will not fit,
     * [cur] drops its street name ([bareOk] when it may), then [next] does, keeping its light or
     * stop sign while that fits ("Turn right, then at the light, turn left onto West Covell
     * Boulevard"). The shortest form is said even when it will not quite fit, since it still says
     * what comes next. A [cur] with no form without its street is said alone.
     */
    private fun joinAtTurn(
        cur: Maneuver, next: Maneuver, plain: String, nextDtn: Double, toNextNow: Double, v: Double,
        detail: SpokenDetail.Mode, bareOk: Boolean,
    ): TurnJoin {
        val (full, fullThen) = joinNext(cur, next, plain, nextDtn, detail)
        if (full == plain) return TurnJoin(plain, false, null, false)
        val pace = v.coerceAtLeast(MIN_PACE_MPS)
        fun fits(line: String) = toNextNow / pace >= line.length / SPEECH_CPS + SPEECH_LEAD_S
        if (fits(full)) return TurnJoin(full, true, fullThen, true)
        val bare = cur.instructionNoRoad?.takeIf { bareOk } ?: return TurnJoin(plain, true, fullThen, false)
        val nextNamed = thenShort(cur, next, next.spokenInstruction())
        // An exit keeps its sign: "take exit 12" without it says too little.
        val nextBare = if (SpokenDetail.exitLike(next.type)) nextNamed else nav().repeatShort(next.instructionNoRoad ?: next.spokenInstruction())
        if (detail == SpokenDetail.Mode.FULL && app.vela.core.model.laneGuidance(next.lanes) == null) {
            val cued = withLight(lightCue(next, nextDtn, CueLine.THEN), nextNamed)
            if (cued != nextNamed) {
                val withCue = nav().thenNext(bare, cued)
                if (fits(withCue)) return TurnJoin(withCue, true, nextNamed, true)
            }
        }
        val named = nav().thenNext(bare, withLanes(next, nextNamed))
        if (fits(named)) return TurnJoin(named, true, nextNamed, true)
        return TurnJoin(nav().thenNext(bare, withLanes(next, nextBare)), true, nextBare, true)
    }

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
        return RouteGeom(route, cum, placeSteps(route, cum)).also { geomCache = it }
    }

    /** Where each step of [route] sits along its line, meters ([cum] its running length): its
     *  location projected onto the line, in order. What the engine counts turns and distances
     *  from, and what [StepFit] checks a turn against. */
    internal fun placeSteps(route: Route, cum: DoubleArray): DoubleArray {
        val total = cum.lastOrNull() ?: 0.0
        val maneuvers = route.maneuvers
        val manAlong = DoubleArray(maneuvers.size)
        var fromM = 0.0
        for (k in maneuvers.indices) {
            // +0.5 nudge past the previous position: a segment ENDING exactly at fromM still
            // overlaps the window and its (earlier) projection would win the strict-less tie.
            // The first step is anchored at the start: a round trip passes its start again at the
            // end, and a depart point a meter nearer the way back put every step at the end.
            val (wm, wd) = projectAlong(route.polyline, cum, maneuvers[k].location, fromM + 0.5, total, anchorM = if (k == 0) 0.0 else null)
            manAlong[k] = (
                if (wd <= ON_ROUTE_M) wm
                else projectNearAnchor(route.polyline, cum, maneuvers[k].location, fromM).first
                ).coerceAtLeast(fromM)
            fromM = manAlong[k]
        }
        return manAlong
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
     *  start with a huge distance if no segment falls in the window. Nothing past [capM] is
     *  matched, not even the rest of a segment that crosses it. */
    internal fun projectAlong(path: List<LatLng>, cum: DoubleArray, p: LatLng, loM: Double, hiM: Double, anchorM: Double? = null, capM: Double = Double.POSITIVE_INFINITY): Pair<Double, Double> {
        val total = cum.lastOrNull() ?: 0.0
        var bestScore = Double.MAX_VALUE
        var bestD = Double.MAX_VALUE
        var bestAlong = loM.coerceIn(0.0, total)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        for (i in 0 until path.size - 1) {
            if (cum[i + 1] < loM || cum[i] > hiM) continue // segment entirely outside the window
            if (cum[i] >= capM) continue
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.lng - p.lng) * mPerDegLng
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, tMax(cum, i, capM))
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
     *  return leg. Returns (meters-along-route, ACTUAL perpendicular meters of the winner).
     *  Nothing past [capM] is matched. */
    internal fun projectNearAnchor(path: List<LatLng>, cum: DoubleArray, p: LatLng, anchorM: Double, capM: Double = Double.POSITIVE_INFINITY): Pair<Double, Double> {
        val total = cum.lastOrNull() ?: 0.0
        var bestScore = Double.MAX_VALUE
        var bestD = Double.MAX_VALUE
        var bestAlong = anchorM.coerceIn(0.0, total)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        for (i in 0 until path.size - 1) {
            if (cum[i] >= capM) continue
            val a = path[i]
            val b = path[i + 1]
            val ax = (a.lng - p.lng) * mPerDegLng
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lng - p.lng) * mPerDegLng
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, tMax(cum, i, capM))
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

    /** The farthest a projection onto segment [i] may run before it passes [capM]. */
    private fun tMax(cum: DoubleArray, i: Int, capM: Double): Double =
        if (cum[i + 1] <= capM || cum[i + 1] <= cum[i]) 1.0 else ((capM - cum[i]) / (cum[i + 1] - cum[i])).coerceIn(0.0, 1.0)

    /** A route that ends where it starts (a round trip: its start within [ARRIVE_PROX_M] of its
     *  end, and the two under a third of its length apart): how far in [update] keeps progress off
     *  its last stretch, a third of the route at most [DEST_ZONE_M]. 0 for any other route, so a
     *  reroute that starts farther from the destination is matched as before. */
    internal fun loopGuardM(poly: List<LatLng>, total: Double): Double {
        if (poly.size < 2) return 0.0
        val gap = poly.first().distanceTo(poly.last())
        return if (gap <= ARRIVE_PROX_M && gap < total / 3) minOf(DEST_ZONE_M, total / 3) else 0.0
    }

}
