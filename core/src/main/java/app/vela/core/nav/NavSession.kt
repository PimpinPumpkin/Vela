package app.vela.core.nav

import android.os.SystemClock
import app.vela.core.data.MapDataSource
import app.vela.core.feedback.Haptics
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of an in-progress navigation. Held as a singleton so the
 * foreground [service][app.vela.core] (which feeds it location with the screen
 * off) and the UI ViewModel (which observes [state]) share exactly one nav loop
 * — no double voice prompts, no divergent state.
 *
 * Beyond turn-by-turn it runs a **live re-check**: every [RECHECK_INTERVAL_MS]
 * while underway it re-queries directions from the current position and, if the
 * fresh traffic-aware ETA beats the remaining time by a real margin, surfaces a
 * faster route the user can accept. That's the "is there a better way right now"
 * behaviour traffic apps live on.
 */
@Singleton
class NavSession @Inject constructor(
    private val dataSource: MapDataSource,
    private val voice: VoiceGuide,
    private val haptics: Haptics,
    private val diag: app.vela.core.diag.DiagLog,
) {
    data class State(
        val navigating: Boolean = false,
        val arrived: Boolean = false,
        val route: Route? = null,
        val nav: NavState = NavState(),
        val maneuverText: String = "",
        val remainingDistance: Double = 0.0,
        val remainingDuration: Double = 0.0,
        val fasterRoute: Route? = null,
        val fasterSavingSeconds: Double = 0.0,
        // Trip summary, populated on arrival (and carried for the arrival card).
        val destinationLabel: String = "",
        val tripDistanceMeters: Double = 0.0,
        val tripElapsedSeconds: Double = 0.0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var destination: LatLng? = null
    private var lastRecheckMs = 0L
    private var tripStartMs = 0L
    private var recheckJob: Job? = null
    // Reroute discipline: SINGLE-FLIGHT (two racing fetches used to swap the route twice, last
    // writer wins), a COOLDOWN between adoptions (no "Rerouting… Rerouting…" every 4 s while GPS
    // is biased toward a parallel road), the voice line rate-limited separately (a silent retry
    // shouldn't re-announce), and a GENERATION stamp so a fetch that completes after stop()/a new
    // start() can't resurrect the previous destination's route into the fresh session.
    private var rerouteJob: Job? = null
    // @Volatile: written on the Default dispatcher (reroute coroutine) / caller thread and read
    // on the location thread — a stale read would defeat the cooldown or the generation guard.
    @Volatile private var lastRerouteAdoptMs = 0L
    @Volatile private var lastRerouteSpokeMs = 0L
    @Volatile private var sessionGen = 0
    // A FAILED reroute must clear the engine's offRoute latch so it retries — but writing nav
    // state from the reroute coroutine races the in-flight onLocation frame (whose route-identity
    // guard can't catch it: a failed reroute doesn't swap the route). Instead the location
    // thread itself consumes this flag at the top of its next frame.
    private val pendingLatchClear = java.util.concurrent.atomic.AtomicBoolean(false)
    // Faster-route offer memory: don't re-offer (and re-speak) the candidate the user just
    // dismissed every recheck; a similar route must beat the dismissed saving by a real margin.
    private var dismissedFasterKey: Long = 0L
    private var dismissedFasterSaving = 0.0
    // Replay hermeticity: a trip REPLAY must be deterministic — no live reroute fetches, no
    // faster-route rechecks (a live fetch mid-replay swapped the route and the recorded fixes
    // were then matched against a route the driver never drove: arrow on another street, the
    // faster-route sheet popping up over a replay). Route swaps that happened in the REAL drive
    // are recorded in the trip and played back via [replaySetRoute].
    @Volatile var replayMode = false
    // Multi-stop: intermediate waypoints (in travel order), each with its along-route "pass mark" so we can
    // announce "you've reached <stop>" as progress passes it, and reroute through the REMAINING ones.
    // The whole plan (stops + marks + counter + the route the marks were measured on) is guarded by
    // [stopLock] and swapped ATOMICALLY with a new route: reroute() runs on Dispatchers.Default while
    // onLocation arrives on the location thread — without the lock (and the planRoute identity check in
    // announceStopsPassed) a fix still measured against the OLD route could be compared to the NEW marks,
    // firing every remaining cue at once and permanently dropping unvisited stops.
    private val stopLock = Any()
    private var mode: TravelMode = TravelMode.DRIVE
    private var stops: List<NavStop> = emptyList()
    private var stopMarks: List<Double?> = emptyList()
    private var passedStops = 0
    private var planRoute: Route? = null // the route [stopMarks] were computed against

    /** An intermediate stop on a multi-stop trip. */
    data class NavStop(val location: LatLng, val label: String)

    fun start(
        route: Route,
        destination: LatLng,
        destinationLabel: String = "",
        voiceEngine: String? = null,
        stops: List<NavStop> = emptyList(),
        mode: TravelMode = TravelMode.DRIVE,
    ) {
        this.destination = destination
        this.mode = mode
        sessionGen += 1               // orphan any in-flight reroute/recheck from a previous session
        rerouteJob?.cancel()
        pendingLatchClear.set(false)  // a stale clear from the previous session must not leak in
        dismissedFasterKey = 0L
        dismissedFasterSaving = 0.0
        synchronized(stopLock) {
            this.stops = stops
            this.stopMarks = NavEngine.stopMarks(route, stops.map { it.location })
            this.passedStops = 0
            this.planRoute = route
        }
        voice.init(voiceEngine)
        lastRecheckMs = SystemClock.elapsedRealtime()
        tripStartMs = SystemClock.elapsedRealtime()
        // Google's markup gives "Head toward F St"; add the cardinal so guidance
        // says "Head east on F St" like Google's own voice.
        val first = Heading.withCardinal(route.maneuvers.firstOrNull()?.instruction.orEmpty(), route.polyline)
        _state.value = State(
            navigating = true,
            route = route,
            // Seed the first turn's approach distance so the banner doesn't read "0 ft" (with
            // every distance gate momentarily open) until the first fix — the DEPART maneuver's
            // after-distance IS the distance to the first real turn.
            nav = NavState(
                distanceToNextManeuver = route.maneuvers.firstOrNull()?.distanceMeters ?: 0.0,
                // Seed the trip totals so the ETA card reads the full route time/distance BEFORE the
                // first fix — the engine overwrites these per-fix, but until then a 0 here rendered
                // "<1 min / 10 ft" (worst while "Searching for GPS" holds off the first fix).
                remainingDistance = route.distanceMeters,
                remainingDuration = route.durationInTrafficSeconds ?: route.durationSeconds,
            ),
            maneuverText = first,
            remainingDistance = route.distanceMeters,
            remainingDuration = route.durationInTrafficSeconds ?: route.durationSeconds,
            destinationLabel = destinationLabel,
            tripDistanceMeters = route.distanceMeters,
        )
        voice.speak(app.vela.core.i18n.NavStringsRegistry.current().startNav(first))
        diag.record(
            "nav",
            "start → ${destinationLabel.ifBlank { "destination" }} " +
                "(${route.distanceMeters?.toInt()} m, ${route.maneuvers.size} steps, " +
                "ETA ${route.durationInTrafficSeconds ?: route.durationSeconds}s)",
        )
    }

    fun stop() {
        sessionGen += 1 // orphan in-flight reroute/recheck — a late completion must not resurrect this session
        recheckJob?.cancel()
        rerouteJob?.cancel()
        pendingLatchClear.set(false)
        voice.stop()
        destination = null
        synchronized(stopLock) { stops = emptyList(); stopMarks = emptyList(); passedStops = 0; planRoute = null }
        _state.value = State()
    }

    fun onLocation(loc: LatLng, imperial: Boolean = false, speedMps: Double? = null) {
        val s = _state.value
        val route = s.route ?: return
        if (!s.navigating || s.arrived) return

        // Consume a failed-reroute latch clear HERE, on the location thread, so the engine
        // computes FROM the cleared state (4 more deviated fixes → natural retry) — clearing it
        // from the reroute coroutine raced this frame's state write and could be silently undone.
        val nav = if (pendingLatchClear.compareAndSet(true, false)) {
            s.nav.copy(offRoute = false, offRouteHits = 0)
        } else {
            s.nav
        }
        // "Stationary" is mode-relative: 2 m/s is parked for a car but faster than most walkers.
        val movingFloor = when (mode) {
            TravelMode.WALK -> 0.6
            TravelMode.BICYCLE -> 1.0
            else -> 2.0
        }
        val (next, events) = NavEngine.update(route, nav, loc, imperial, speedMps, movingFloor)
        val maneuver = route.maneuvers.getOrNull(next.stepIndex)
        // Guard the write on route IDENTITY: a reroute/faster-route can swap route+NavState while
        // this update was computing on the OLD route — writing `next` (old-route traveledM /
        // stepIndex) onto the fresh route corrupted progress and could false-arrive right after
        // a reroute. Same pattern announceStopsPassed already uses; drop the stale frame whole.
        var applied = false
        _state.update {
            if (it.route !== route) it else {
                applied = true
                it.copy(
                    nav = next,
                    maneuverText = maneuver?.instruction.orEmpty(),
                    remainingDistance = next.remainingDistance,
                    remainingDuration = next.remainingDuration,
                )
            }
        }
        if (!applied) return
        events.forEach { ev ->
            when (ev) {
                is NavEvent.Speak -> voice.speak(ev.text, ev.interrupt)
                is NavEvent.Haptic -> haptics.cue(ev.type, ev.approaching, mode)
                NavEvent.Arrived -> {
                    diag.record("nav", "arrived (trip ${((SystemClock.elapsedRealtime() - tripStartMs) / 1000)}s)")
                    _state.update {
                        it.copy(
                            navigating = false,
                            arrived = true,
                            tripElapsedSeconds = (SystemClock.elapsedRealtime() - tripStartMs) / 1000.0,
                        )
                    }
                }
                NavEvent.RerouteNeeded -> {
                    diag.record("nav", "off-route → rerouting from ${loc.lat},${loc.lng}")
                    reroute(loc)
                }
            }
        }
        announceStopsPassed(route, next.traveledM)
        maybeRecheck(loc, next)
    }

    /** Per-stop arrival cue: as along-route progress passes each waypoint's mark, announce it once, in
     *  order ("You've reached <stop>"). A stop with no mark (not locatable on the route) is skipped
     *  silently rather than blocking the rest. [route] must be the route [traveledM] was measured on —
     *  if a reroute swapped the plan mid-fix, the identity check drops the stale frame instead of
     *  comparing old progress to new marks (which would fire every cue at once). */
    private fun announceStopsPassed(route: Route, traveledM: Double) {
        val toSpeak = mutableListOf<String>()
        synchronized(stopLock) {
            if (route !== planRoute) return
            while (passedStops < stops.size) {
                val mark = stopMarks.getOrNull(passedStops)
                if (mark == null) { passedStops++; continue }
                if (traveledM >= mark - STOP_ARRIVE_TOL_M) {
                    toSpeak += stops[passedStops].label
                    passedStops++
                } else break
            }
        }
        toSpeak.forEach { label ->
            voice.speak(app.vela.core.i18n.NavStringsRegistry.current().reachedStop(label))
            diag.record("nav", "reached stop: ${label.ifBlank { "(unnamed)" }}")
        }
    }

    fun acceptFasterRoute() {
        val faster = _state.value.fasterRoute ?: return
        val first = faster.maneuvers.firstOrNull()?.instruction.orEmpty()
        // The faster candidate was routed through the remaining stops (maybeRecheck rejects candidates
        // that don't cover them) → adopt them + recompute marks, atomically with the plan-route swap.
        synchronized(stopLock) {
            val remainingStops = stops.drop(passedStops)
            stops = remainingStops
            stopMarks = NavEngine.stopMarks(faster, remainingStops.map { it.location })
            passedStops = 0
            planRoute = faster
        }
        lastRecheckMs = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                route = faster,
                nav = NavState(
                    distanceToNextManeuver = faster.maneuvers.firstOrNull()?.distanceMeters ?: 0.0,
                    remainingDistance = faster.distanceMeters,
                    remainingDuration = faster.durationInTrafficSeconds ?: faster.durationSeconds,
                ),
                maneuverText = first,
                remainingDistance = faster.distanceMeters,
                remainingDuration = faster.durationInTrafficSeconds ?: faster.durationSeconds,
                fasterRoute = null,
                fasterSavingSeconds = 0.0,
            )
        }
        voice.speak(app.vela.core.i18n.NavStringsRegistry.current().fasterRoute(first), interrupt = true)
    }

    fun dismissFasterRoute() {
        // Remember what was dismissed so the next recheck doesn't re-offer (and re-speak) the
        // same candidate two minutes later — it must beat this saving by a real margin first.
        _state.value.fasterRoute?.let {
            dismissedFasterKey = routeKey(it)
            dismissedFasterSaving = _state.value.fasterSavingSeconds
        }
        _state.update { it.copy(fasterRoute = null, fasterSavingSeconds = 0.0) }
    }

    // --- live re-check ------------------------------------------------------

    private fun maybeRecheck(loc: LatLng, nav: NavState) {
        if (replayMode) return // hermetic replays never fetch live traffic/routes
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecheckMs < RECHECK_INTERVAL_MS) return
        if (nav.offRoute || nav.remainingDistance < MIN_RECHECK_DISTANCE_M) return
        if (recheckJob?.isActive == true) return
        // An offer is already on screen — don't fetch/re-speak over it every interval.
        if (_state.value.fasterRoute != null) return
        val dest = destination ?: return
        lastRecheckMs = now
        // Named remainingStops (not `remaining`) — the launch body below declares `remaining` for the
        // remaining DURATION, which would shadow this and hand a future edit seconds instead of stops.
        val remainingStops = synchronized(stopLock) { stops.drop(passedStops) }
        val gen = sessionGen
        recheckJob = scope.launch {
            val candidate = runCatching { dataSource.directions(loc, dest, mode, remainingStops.map { it.location }).firstOrNull() }.getOrNull()
                ?.takeIf { it.reaches(dest) } ?: return@launch
            if (gen != sessionGen) return@launch // session ended/restarted while fetching
            // The waypointed directions call falls back to a DIRECT origin→dest route when the via
            // routing fails — that route passes reaches(dest) but skips the stops, and it reads minutes
            // "faster" precisely because it drops the detours. Never OFFER a route that doesn't cover
            // every remaining stop (an offer is optional; guiding past a stop is not).
            if (remainingStops.isNotEmpty() &&
                NavEngine.stopMarks(candidate, remainingStops.map { it.location }).any { it == null }
            ) return@launch
            val candidateEta = candidate.durationInTrafficSeconds ?: candidate.durationSeconds
            val remaining = _state.value.remainingDuration
            val saving = remaining - candidateEta
            // A candidate similar to one the user DISMISSED is only re-offered when it beats the
            // dismissed saving by a real margin — not re-spoken verbatim every 2 minutes.
            if (routeKey(candidate) == dismissedFasterKey && saving < dismissedFasterSaving + 60.0) return@launch
            // Offer it only if it saves real time AND isn't implausibly short — a candidate claiming to cut
            // the same trip to a fraction of the time left is a bad route, not a real faster path.
            if (saving > FASTER_THRESHOLD_S && candidateEta in (remaining * MIN_PLAUSIBLE_ETA_FRACTION)..(remaining * 0.9)) {
                _state.update { it.copy(fasterRoute = candidate, fasterSavingSeconds = saving) }
                voice.speak(
                    app.vela.core.i18n.NavStringsRegistry.current()
                        .fasterRouteAvailable((saving / 60).toInt().coerceAtLeast(1)),
                )
            }
        }
    }

    /** Identity for "the same candidate route" across rechecks (dismissal memory). Keyed on the
     *  route's TAIL geometry — every recheck fetches from the CURRENT position, so total length /
     *  point count shrink as you drive and would never match; the destination-approach geometry
     *  survives forward progress. */
    private fun routeKey(r: Route): Long {
        var h = 1125899906842597L
        r.polyline.takeLast(20).forEach { p ->
            h = 31 * h + (p.lat * 1e5).toLong()
            h = 31 * h + (p.lng * 1e5).toLong()
        }
        return h
    }

    /** Adopt a route swap RECORDED in a trip being replayed (silent, no fetch) — the replay
     *  equivalent of the reroute/faster-route adoption that happened during the real drive. */
    fun replaySetRoute(r: Route) {
        if (r.polyline.size < 2) return
        synchronized(stopLock) { stops = emptyList(); stopMarks = emptyList(); passedStops = 0; planRoute = r }
        diag.record("nav", "replay: route swap (${r.maneuvers.size} steps)")
        _state.update {
            it.copy(
                route = r,
                nav = NavState(
                    distanceToNextManeuver = r.maneuvers.firstOrNull()?.distanceMeters ?: 0.0,
                    remainingDistance = r.distanceMeters,
                    remainingDuration = r.durationInTrafficSeconds ?: r.durationSeconds,
                ),
                maneuverText = r.maneuvers.firstOrNull()?.instruction.orEmpty(),
                remainingDistance = r.distanceMeters,
                remainingDuration = r.durationInTrafficSeconds ?: r.durationSeconds,
                fasterRoute = null,
            )
        }
    }

    private fun reroute(loc: LatLng) {
        if (replayMode) {
            diag.record("nav", "replay: live reroute suppressed (recorded swaps play back instead)")
            return
        }
        val dest = destination ?: return
        val now = SystemClock.elapsedRealtime()
        // Single-flight + cooldown: one fetch at a time, and no re-adoption storm while GPS is
        // biased toward a parallel road (the new route lands, the biased fixes are >45 m from IT
        // too, 4 s later another "Rerouting…" — forever). The engine keeps emitting RerouteNeeded
        // while deviated (the latch clears on failure below), so a skipped request here is simply
        // retried by the next qualifying fix after the cooldown.
        if (rerouteJob?.isActive == true || now - lastRerouteAdoptMs < REROUTE_COOLDOWN_MS) return
        // Announce sparsely: the first attempt of a burst speaks, silent retries don't re-announce.
        if (now - lastRerouteSpokeMs > REROUTE_SPEAK_MIN_MS) {
            lastRerouteSpokeMs = now
            voice.speak(app.vela.core.i18n.NavStringsRegistry.current().rerouting(), interrupt = true)
        }
        // Reroute THROUGH the stops you haven't reached yet — not straight to the final destination
        // (that used to silently drop your remaining stops on any off-route wobble).
        val remainingStops = synchronized(stopLock) { stops.drop(passedStops) }
        val gen = sessionGen
        // The route we were following when we went off-route. If the driver returns to THIS line while
        // we're fetching (see the back-on-course check below), we abandon the reroute rather than swap.
        val fromRoute = _state.value.route
        rerouteJob = scope.launch {
            // A reroute that doesn't actually reach the destination is a bad result — keep guiding on the
            // current route rather than swapping to a truncated/wrong one. (Guard unchanged: the route still
            // ends at the same final dest even with waypoints in between.)
            val r = runCatching { dataSource.directions(loc, dest, mode, remainingStops.map { it.location }) }
                .getOrNull()?.firstOrNull()?.takeIf { it.reaches(dest) }
            if (gen != sessionGen) return@launch // session ended / restarted while fetching — drop it
            // BACK ON COURSE: while we were fetching (~1-3 s), did the driver return to the ORIGINAL route?
            // A U-turn (or any wobble) fires RerouteNeeded, but by the time the fetch lands the driver has
            // often completed it and rejoined the planned line. Swapping in a fresh route then yanks a driver
            // who already self-corrected onto a different path — so if the route hasn't otherwise changed and
            // we're solidly back on the line, discard this reroute and carry on (Google's "you're back on
            // course"). SUSTAINED, not one fix: offRoute clears on a SINGLE grazing fix within OFF_ROUTE_M,
            // which a spurious graze on a parallel/overlapping leg trips — so gate on onRouteStreak (N
            // consecutive on-corridor+moving fixes), NOT bare !offRoute, or a real missed-turn reroute could
            // be wrongly abandoned. Still off / only grazed → adopt r as before. Self-healing: a re-deviation
            // re-fires RerouteNeeded on the next rising edge (no cooldown charged — we return before adopt).
            val backNav = _state.value.nav
            if (_state.value.route === fromRoute && !backNav.offRoute && backNav.onRouteStreak >= BACK_ON_COURSE_HITS) {
                diag.record("nav", "reroute discarded — driver solidly back on the original route (streak ${backNav.onRouteStreak})")
                return@launch
            }
            if (r == null) {
                // FAILED (dead spot / OSRM 5xx / truncated result). The old code returned silently
                // and rerouting was DEAD for the rest of the excursion: RerouteNeeded is
                // edge-triggered on the offRoute latch, which never re-fires while still off the
                // old route. Flag the latch clear for the LOCATION THREAD to consume (writing nav
                // state from here raced the in-flight onLocation frame) — 4 more deviated fixes
                // then request again (~4 s natural backoff, OsmAnd-style retry-while-deviated).
                diag.record("nav", "reroute FAILED — will retry while off-route")
                pendingLatchClear.set(true)
                return@launch
            }
            // New route starts here → recompute the marks, reset the counter. Unlike the faster-route
            // OFFER we accept a route that couldn't include the stops (being guided beats staying
            // off-route), but we say so and KEEP the stops in the plan — their marks are null on this
            // route, and the next recheck routes through them again once the via routing recovers.
            val marks = NavEngine.stopMarks(r, remainingStops.map { it.location })
            synchronized(stopLock) {
                stops = remainingStops
                stopMarks = marks
                passedStops = 0
                planRoute = r
            }
            if (remainingStops.isNotEmpty() && marks.any { it == null }) {
                voice.speak(app.vela.core.i18n.NavStringsRegistry.current().stopsNotIncluded())
                diag.record("nav", "reroute missing ${marks.count { it == null }}/${remainingStops.size} stops")
            }
            lastRecheckMs = SystemClock.elapsedRealtime()
            lastRerouteAdoptMs = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    route = r,
                    nav = NavState(
                        distanceToNextManeuver = r.maneuvers.firstOrNull()?.distanceMeters ?: 0.0,
                        remainingDistance = r.distanceMeters,
                        remainingDuration = r.durationInTrafficSeconds ?: r.durationSeconds,
                    ),
                    maneuverText = r.maneuvers.firstOrNull()?.instruction.orEmpty(),
                    remainingDistance = r.distanceMeters,
                    remainingDuration = r.durationInTrafficSeconds ?: r.durationSeconds,
                    fasterRoute = null,
                )
            }
        }
    }

    /** Does this route actually END near [dest]? A route whose last point is far from the destination is
     *  truncated or wrong; swapping to it mid-nav is the "10 min away / wrong final step" bug. */
    private fun Route.reaches(dest: LatLng) =
        polyline.lastOrNull()?.let { it.distanceTo(dest) <= REACH_TOLERANCE_M } ?: false

    private companion object {
        const val RECHECK_INTERVAL_MS = 120_000L   // re-check traffic every ~2 min
        const val MIN_RECHECK_DISTANCE_M = 1_500.0 // don't bother near the destination
        const val FASTER_THRESHOLD_S = 90.0        // only offer if it saves real time
        const val REROUTE_COOLDOWN_MS = 10_000L    // min gap between ADOPTED reroutes (no reroute storms)
        const val REROUTE_SPEAK_MIN_MS = 30_000L   // "Rerouting" spoken at most this often (retries are silent)
        const val BACK_ON_COURSE_HITS = 2          // consecutive on-corridor fixes before an in-flight reroute
                                                   // is abandoned as "back on course" — >1 so a single grazing
                                                   // fix can't kill a legitimate missed-turn reroute (tune from
                                                   // a real u-turn capture; 2 filters grazes, catches rejoins)
        // A reroute/faster candidate must actually END near the destination — a truncated or wrong route
        // (its last point miles from dest) is the "10 min away, wrong final step" bug; never swap to it.
        const val REACH_TOLERANCE_M = 500.0
        // …and it can't be implausibly short: the same trip can't suddenly take <40% of the time left
        // (that's a bad route, not real traffic). Guards the faster-route offer from a bogus short ETA.
        const val MIN_PLAUSIBLE_ETA_FRACTION = 0.4
        // Fire the per-stop cue when along-route progress gets within this of the stop's mark (as you pass).
        const val STOP_ARRIVE_TOL_M = 25.0
    }
}
