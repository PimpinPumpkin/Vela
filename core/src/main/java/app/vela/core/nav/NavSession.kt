package app.vela.core.nav

import android.os.SystemClock
import app.vela.core.data.MapDataSource
import app.vela.core.data.RoutingPrefs
import app.vela.core.feedback.Haptics
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
 * behavior traffic apps live on.
 */
/** Outcome of [NavSession.rerouteGate] - whether a reroute request may proceed. */
enum class RerouteGate { START, SKIP_IN_FLIGHT, SKIP_COOLDOWN, ABANDON_STUCK_AND_START }

@Singleton
class NavSession @Inject constructor(
    private val dataSource: MapDataSource,
    private val voice: VoiceGuide,
    private val haptics: Haptics,
    private val diag: app.vela.core.diag.DiagLog,
) {
    data class State(
        val navigating: Boolean = false,
        /**
         * The drive is HELD: the route, the stops and the remaining figures stay exactly as they
         * are, and every per-fix behavior stops - no engine update, no off-route detection, no
         * reroute, no voice, no stop cues, no live-traffic recheck or faster-route offer. The puck
         * still follows you (the map draws it from the raw fix), so pulling into a fuel station
         * does not make the app argue with you about it (user 2026-09-18).
         */
        val paused: Boolean = false,
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
        // The destination's address line, when it adds anything beyond [destinationLabel]
        // (see [destinationDisplay]) — shown on the ARRIVE step in the banner + step list.
        val destinationAddress: String = "",
        val tripDistanceMeters: Double = 0.0,
        val tripElapsedSeconds: Double = 0.0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var destination: LatLng? = null
    private var lastRecheckMs = 0L
    private var recheckSpread = app.vela.core.util.Jitter.factor()
    // Fast-heal pacing for a DEGRADED route (abbreviated steps / no live traffic): reference of
    // the route the counter was armed for + how many short-interval rechecks it has spent.
    private var degradedRouteRef: Route? = null
    private var degradedFastRechecks = 0
    private var tripStartMs = 0L
    private var recheckJob: Job? = null
    // Reroute discipline: SINGLE-FLIGHT (two racing fetches used to swap the route twice, last
    // writer wins), a COOLDOWN between adoptions (no "Rerouting… Rerouting…" every 4 s while GPS
    // is biased toward a parallel road), the voice line rate-limited separately (a silent retry
    // shouldn't re-announce), and a GENERATION stamp so a fetch that completes after stop()/a new
    // start() can't resurrect the previous destination's route into the fresh session.
    /** The last fix we saw, paused or not: resume decides from it whether we are still on the
     *  route, and auto-resume needs somewhere to measure from. */
    @Volatile private var lastLoc: LatLng? = null
    @Volatile private var lastBearing: Double? = null
    /** Consecutive paused fixes that are moving AND back on the route (see [maybeAutoResume]). */
    private var backOnRouteHits = 0
    /** Auto-resume is ARMED only once the stop has actually happened - a fix that is stationary or
     *  off the route. Without it, pausing while still rolling along the route resumed itself three
     *  fixes later, which is a pause button that does not pause (device, 2026-09-18). */
    private var autoResumeArmed = false

    private var rerouteJob: Job? = null
    private var rerouteStartedMs = 0L
    /** Deadline the in-flight reroute is running under (see [rerouteAttempt]). */
    @Volatile private var rerouteDeadlineMs = REROUTE_FETCH_TIMEOUT_MS
    /** Consecutive reroute attempts that came back with nothing; reset on any adopted route. */
    @Volatile private var rerouteFailStreak = 0
    // @Volatile: written on the Default dispatcher (reroute coroutine) / caller thread and read
    // on the location thread — a stale read would defeat the cooldown or the generation guard.
    @Volatile private var lastRerouteAdoptMs = 0L
    /** WHY the current route was adopted - stamped at every swap site, read by the trip recorder
     *  so the file distinguishes a wrong-turn reroute from a chosen faster route or a silent
     *  heal: "start", "reroute", "faster", "heal", "stop-added". */
    @Volatile var lastSwapReason: String = "start"
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
    // Live ETA calibration for the CURRENT course. Each recheck fetches a fresh traffic-aware
    // route from the live position anyway; when that candidate follows the road we're already
    // driving, its ETA is the freshest read of the traffic ahead - so instead of discarding it
    // (the shown ETA used to ride the traffic ratio captured at the LAST route fetch for the
    // whole drive), the session keeps a multiplicative correction applied to every published
    // remaining duration. Reset to 1.0 whenever the route itself is swapped (start / reroute /
    // faster-route / replay) - a fresh route carries fresh traffic of its own. Volatile: written
    // by the recheck coroutine, read on the location thread's publish path.
    @Volatile private var etaScale = 1.0
    // Replay hermeticity: a trip REPLAY must be deterministic — no live reroute fetches, no
    // faster-route rechecks (a live fetch mid-replay swapped the route and the recorded fixes
    // were then matched against a route the driver never drove: arrow on another street, the
    // faster-route sheet popping up over a replay). Route swaps that happened in the REAL drive
    // are recorded in the trip and played back via [replaySetRoute].
    @Volatile var replayMode = false
    // Settings -> Data & privacy -> "Live traffic re-checks". Each recheck sends the CURRENT
    // position to Google (that's what makes a from-here candidate possible), which is a periodic
    // in-drive location beacon on top of the origin already sent at start + on reroutes. Off =
    // no periodic requests: no faster-route offers, no live ETA recalibration, no
    // abbreviated-steps self-heal; reroutes still fire when off course (they're what nav IS).
    @Volatile var liveRechecks = true
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
    /** A stop on the drive. [silent] marks a side-street detour point the camera pass added (issue
     *  #600): routed through like a stop, so a reroute or recheck keeps the detour, but never
     *  spoken, never listed, never a leg divider. A mid-drive stops EDIT hands back the VISIBLE
     *  list; [withSilentVias] puts the silent points still ahead back into it in route order. */
    data class NavStop(val location: LatLng, val label: String, val silent: Boolean = false)

    /** Fold a light-ENRICHED copy of the current route in after nav has already started, so
     *  START never waits on the Overpass traffic-signal fetch (that blocked nav start for up to
     *  ~25 s, the server timeout, once light guidance became standard - user 2026-07-16). The
     *  enriched route's polyline and maneuver POSITIONS are identical; only turn instruction TEXT
     *  gains "Pass the light, then ...". So swapping planRoute (what the engine reads each fix) and
     *  re-emitting the current step's text is safe and needs no re-anchor. No-op if we've stopped
     *  or rerouted since (a different polyline = the clauses are for a route we're no longer on).
     *  Deliberately does NOT touch _state.route, so the trip recorder doesn't log a phantom swap. */
    fun applyEnrichedRoute(r: Route) {
        synchronized(stopLock) {
            val cur = planRoute ?: return
            if (!_state.value.navigating || cur.polyline.size != r.polyline.size) return
            planRoute = r
        }
        val idx = _state.value.nav.stepIndex
        r.maneuvers.getOrNull(idx)?.instruction?.let { txt ->
            _state.update { it.copy(maneuverText = txt) }
        }
    }

    fun start(
        route: Route,
        destination: LatLng,
        destinationLabel: String = "",
        voiceEngine: String? = null,
        stops: List<NavStop> = emptyList(),
        mode: TravelMode = TravelMode.DRIVE,
        destinationAddress: String = "",
    ) {
        this.destination = destination
        this.mode = mode
        sessionGen += 1               // orphan any in-flight reroute/recheck from a previous session
        rerouteJob?.cancel()
        pendingLatchClear.set(false)  // a stale clear from the previous session must not leak in
        // A new drive starts lean: the previous drive's bad patch of coverage says nothing about
        // this one, and inheriting its streak would send the first reroute straight to the ladder.
        rerouteFailStreak = 0
        rerouteDeadlineMs = REROUTE_FETCH_TIMEOUT_MS
        dismissedFasterKey = 0L
        dismissedFasterSaving = 0.0
        etaScale = 1.0
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
        // The opener is the first thing the voice says on every drive, so it honors the
        // spoken-street-names switch too (issue #596). The BANNER keeps `first`: the switch is
        // about what is read aloud, never about what is shown.
        val firstSpoken =
            Heading.withCardinal(route.maneuvers.firstOrNull()?.spokenInstruction().orEmpty(), route.polyline)
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
            destinationAddress = destinationAddress,
            tripDistanceMeters = route.distanceMeters,
        )
        // speakOpener (not speak): briefly hold the opener until the first road's real romanized name
        // has loaded from the map tiles, so a foreign street isn't read as an ICU skeleton at T=0 while
        // the nav-zoom tiles are still loading (issue #184). Falls through to speaking after a short cap.
        voice.speakOpener(app.vela.core.i18n.NavStringsRegistry.current().startNav(firstSpoken))
        diag.record(
            "nav",
            "start → ${destinationLabel.ifBlank { "destination" }} " +
                "(${route.distanceMeters?.toInt()} m, ${route.maneuvers.size} steps, " +
                "ETA ${route.durationInTrafficSeconds ?: route.durationSeconds}s)",
        )
    }

    fun stop() {
        // A reroute canceled here ends without its own FAILED/adopted line, which made an export
        // read as one attempt hanging for minutes (issue #557). Say so.
        if (rerouteJob?.isActive == true) {
            diag.record("nav", "nav ended with a reroute in flight for ${SystemClock.elapsedRealtime() - rerouteStartedMs} ms")
        } else if (_state.value.navigating) {
            diag.record("nav", "nav ended")
        }
        sessionGen += 1 // orphan in-flight reroute/recheck — a late completion must not resurrect this session
        recheckJob?.cancel()
        rerouteJob?.cancel()
        pendingLatchClear.set(false)
        rerouteFailStreak = 0
        rerouteDeadlineMs = REROUTE_FETCH_TIMEOUT_MS
        voice.stop()
        destination = null
        synchronized(stopLock) { stops = emptyList(); stopMarks = emptyList(); passedStops = 0; planRoute = null }
        _state.value = State()
    }

    /** In-nav "search along route" pick: [stop] becomes the NEXT stop and the drive replans
     *  through it from [loc]. Same fetch shape as [reroute] minus the deviation bookkeeping -
     *  this reroute is USER-ORDERED, so no back-on-course discard, no cooldown, and it cancels
     *  any in-flight deviation reroute (the user's plan supersedes it). The stop joins the plan
     *  IMMEDIATELY (marks null until the new route lands), so even a failed fetch keeps it -
     *  the next reroute/recheck routes through it once the network recovers. */
    fun addStop(stop: NavStop, loc: LatLng) {
        // VISIBLE stops only: setStops puts the silent detour vias back itself (withSilentVias), so
        // passing them here routed through every detour point twice.
        val remaining = synchronized(stopLock) { stops.drop(passedStops).filter { !it.silent } }
        setStops(listOf(stop) + remaining, loc, "add stop mid-nav → ${stop.label}", "stop-added")
    }

    /** [visible] (an edited stop list from the UI, which never sees silent stops) with the silent
     *  detour vias still ahead put back in, each where it falls along the current plan route
     *  relative to the visible stops (a visible stop not on that route keeps its list position).
     *  Without this the first stops edit of a drive silently threw the camera detour away. */
    private fun withSilentVias(visible: List<NavStop>): List<NavStop> {
        val (silent, plan) = synchronized(stopLock) { stops.drop(passedStops).filter { it.silent } to planRoute }
        if (silent.isEmpty()) return visible
        if (plan == null) return visible + silent
        val vMarks = NavEngine.stopMarks(plan, visible.map { it.location })
        val sMarks = NavEngine.stopMarks(plan, silent.map { it.location })
        val vias = silent.indices.mapNotNull { i -> sMarks[i]?.let { it to silent[i] } }
        return CameraDetour.mergeOrdered(visible.indices.map { i -> vMarks[i] to visible[i] }, vias)
    }

    /** The stops still ahead on the drive, in order (the ones already passed are dropped). The
     *  VISIBLE ones: a silent detour via is routed through but is not a stop to anyone. */
    fun remainingStops(): List<NavStop> = synchronized(stopLock) { stops.drop(passedStops).filter { !it.silent } }

    /** Replace the stops still ahead with [newRemaining] (the stops editor's Done during nav,
     *  issue #402: reorder, remove, add, then ONE replan from [loc]) and replan the drive through
     *  them. The same user-ordered reroute as [addStop]: no cooldown, no back-on-course discard,
     *  and the new list is the plan at once, so even a failed fetch keeps it for the next
     *  reroute/recheck. */
    fun setStops(newVisible: List<NavStop>, loc: LatLng, reason: String, swapReason: String = "stops-edited") {
        val dest = destination ?: return
        val newRemaining = withSilentVias(newVisible)
        synchronized(stopLock) {
            stops = newRemaining
            stopMarks = List(newRemaining.size) { null } // measured against no route yet: cues hold
            passedStops = 0
        }
        voice.speak(app.vela.core.i18n.NavStringsRegistry.current().rerouting(), interrupt = true)
        note(reason)
        val gen = sessionGen
        rerouteJob?.cancel()
        rerouteJob = scope.launch {
            val r = runCatching { dataSource.directions(loc, dest, mode, newRemaining.map { it.location }, avoidTolls = RoutingPrefs.avoidTolls, avoidHighways = RoutingPrefs.avoidHighways, avoidFerries = RoutingPrefs.avoidFerries) }
                .getOrNull()?.let { driveable(it, loc, dest) }?.takeIf { it.reaches(dest) }
            if (gen != sessionGen) return@launch
            if (r == null) {
                note("stops reroute FAILED, list kept, next reroute/recheck retries")
                // This job replaced any deviation reroute that was in flight, so if the driver is
                // off the line, only a cleared latch lets the next fix ask again.
                pendingLatchClear.set(true)
                return@launch
            }
            val marks = NavEngine.stopMarks(r, newRemaining.map { it.location })
            synchronized(stopLock) {
                stops = newRemaining
                stopMarks = marks
                passedStops = 0
                planRoute = r
            }
            lastSwapReason = swapReason
            lastRecheckMs = SystemClock.elapsedRealtime()
            lastRerouteAdoptMs = SystemClock.elapsedRealtime()
            etaScale = 1.0 // the fresh route carries fresh traffic
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

    fun onLocation(loc: LatLng, imperial: Boolean = false, speedMps: Double? = null, accuracyM: Double? = null, bearingDeg: Double? = null) {
        val s = _state.value
        val route = s.route ?: return
        if (!s.navigating || s.arrived) return
        // PAUSED: remember where we are (resume needs it) and do nothing else. Returning before
        // the engine is what makes the hold total - events are what speak, reroute, count stops
        // and arrive, and they are all downstream of this call.
        lastLoc = loc
        lastBearing = bearingDeg
        if (s.paused) {
            maybeAutoResume(loc, route, speedMps, accuracyM)
            return
        }

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
        // Off-route corridor is accuracy-scaled AND mode-relative: it widens with the GPS fix's own
        // reported accuracy (tight when clean, wide when noisy - like OsmAnd), and foot/bike ride
        // tighter than driving because the path is narrow. See NavEngine.offRouteCorridor.
        val offRoute = NavEngine.offRouteCorridor(mode, accuracyM)
        val farOff = NavEngine.farOffDistance(mode, offRoute)
        val (next, events) = NavEngine.update(route, nav, loc, imperial, speedMps, movingFloor, offRoute, farOff, bearingDeg)
        val maneuver = route.maneuvers.getOrNull(next.stepIndex)
        // Guard the write on route IDENTITY: a reroute/faster-route can swap route+NavState while
        // this update was computing on the OLD route — writing `next` (old-route traveledM /
        // stepIndex) onto the fresh route corrupted progress and could false-arrive right after
        // a reroute. Same pattern announceStopsPassed already uses; drop the stale frame whole.
        var applied = false
        _state.update {
            if (it.route !== route) it else {
                applied = true
                // The live-traffic ETA calibration (etaScale, set by the recheck) applies to the
                // PUBLISHED remaining time only - the engine's own value stays pristine (it never
                // reads it back; it recomputes from the route's step durations each fix).
                val scaledNav = if (etaScale == 1.0) next else next.copy(remainingDuration = next.remainingDuration * etaScale)
                it.copy(
                    nav = scaledNav,
                    maneuverText = maneuver?.instruction.orEmpty(),
                    remainingDistance = next.remainingDistance,
                    remainingDuration = scaledNav.remainingDuration,
                )
            }
        }
        if (!applied) return
        events.forEach { ev ->
            when (ev) {
                is NavEvent.Speak -> voice.speak(ev.text, ev.interrupt)
                is NavEvent.Haptic -> haptics.cue(ev.type, ev.approaching, mode)
                NavEvent.Arrived -> {
                    note("arrived (trip ${((SystemClock.elapsedRealtime() - tripStartMs) / 1000)}s)")
                    _state.update {
                        it.copy(
                            navigating = false,
                            arrived = true,
                            tripElapsedSeconds = (SystemClock.elapsedRealtime() - tripStartMs) / 1000.0,
                        )
                    }
                }
                NavEvent.RerouteNeeded -> {
                    diag.record("nav", "off-route → rerouting from ${loc.lat},${loc.lng} heading ${bearingDeg?.toInt()}"); onNote?.invoke("off-route -> rerouting, heading ${bearingDeg?.toInt()}")
                    reroute(loc, bearingDeg)
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
                    if (!stops[passedStops].silent) toSpeak += stops[passedStops].label
                    passedStops++
                } else break
            }
        }
        toSpeak.forEach { label ->
            voice.speak(app.vela.core.i18n.NavStringsRegistry.current().reachedStop(label))
            note("reached stop: ${label.ifBlank { "(unnamed)" }}")
        }
    }

    /** Every nav decision the session makes, for the diag ring AND the trip file (`K` lines,
     *  2026-09-13): rechecks offered and rejected, reroute attempts, swaps. The trip used to hold
     *  only the spoken lines and the route blocks, so a bad decision was invisible until the
     *  maneuver lines were read by hand. Never pass a coordinate through here. */
    var onNote: ((String) -> Unit)? = null
    private fun note(msg: String) { diag.record("nav", msg); onNote?.invoke(msg) }

    /**
     * Hold the drive, or let it go again.
     *
     * Pausing keeps the plan and freezes the figures. Resuming does the one thing a driver
     * actually wants after a stop: if we have wandered off the route (a fuel station across the
     * junction, a car park round the back), reroute once from where we are; if we are still on
     * it, just carry on, and say the current instruction so the drive picks back up out loud.
     */
    fun setPaused(on: Boolean) {
        val s = _state.value
        if (!s.navigating || s.paused == on) return
        backOnRouteHits = 0
        autoResumeArmed = false
        _state.update { it.copy(paused = on) }
        note(if (on) "paused" else "resumed")
        if (on) return
        val route = s.route ?: return
        val loc = lastLoc ?: return
        val off = perpendicularToRouteM(route, loc) > NavEngine.offRouteCorridor(mode, null)
        if (off) {
            note("resumed off the route -> rerouting")
            reroute(loc, lastBearing)
        } else {
            voice.speak(_state.value.maneuverText, interrupt = false)
        }
    }

    /**
     * Rolling away from the stop resumes by itself, because forgetting to un-pause is the obvious
     * way this feature bites: driving on with a frozen banner is worse than never having paused.
     * It takes [AUTO_RESUME_HITS] consecutive fixes that are both moving and inside the route
     * corridor, so creeping across a forecourt that happens to touch the route does not count.
     */
    private fun maybeAutoResume(loc: LatLng, route: Route, speedMps: Double?, accuracyM: Double?) {
        val movingFloor = when (mode) {
            TravelMode.WALK -> 0.6
            TravelMode.BICYCLE -> 1.0
            else -> 2.0
        }
        val moving = (speedMps ?: 0.0) >= movingFloor
        val onRoute = perpendicularToRouteM(route, loc) <= NavEngine.offRouteCorridor(mode, accuracyM)
        // The stop itself arms it: standing still, or leaving the route. Until one of those
        // happens, a pause holds no matter how long you keep driving down the same road.
        if (!moving || !onRoute) autoResumeArmed = true
        if (!autoResumeArmed) return
        backOnRouteHits = if (moving && onRoute) backOnRouteHits + 1 else 0
        if (backOnRouteHits >= AUTO_RESUME_HITS) {
            backOnRouteHits = 0
            note("auto-resumed: moving and back on the route")
            _state.update { it.copy(paused = false) }
            voice.speak(_state.value.maneuverText, interrupt = false)
        }
    }

    /** How far [loc] sits off the route line, in meters, measured the way the engine measures it
     *  (the anchor is our current progress, so an out-and-back route does not match the wrong leg). */
    private fun perpendicularToRouteM(route: Route, loc: LatLng): Double {
        val path = route.polyline
        if (path.size < 2) return Double.MAX_VALUE
        val cum = RouteProjection.cumulative(path)
        return NavEngine.projectNearAnchor(path, cum, loc, _state.value.nav.traveledM).second
    }

    fun acceptFasterRoute() {
        val faster = _state.value.fasterRoute ?: return
        lastSwapReason = "faster"
        val first = faster.maneuvers.firstOrNull()?.instruction.orEmpty()
        // Spoken half of the same split as the opener: the card keeps the road name, the voice
        // drops it when the user asked for that (issue #596).
        val firstSpoken = faster.maneuvers.firstOrNull()?.spokenInstruction().orEmpty()
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
        etaScale = 1.0 // the accepted route carries fresh traffic
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
        voice.speak(app.vela.core.i18n.NavStringsRegistry.current().fasterRoute(firstSpoken), interrupt = true)
        note("accepted faster route (${faster.maneuvers.size} steps)")
    }

    fun dismissFasterRoute() {
        // Remember what was dismissed so the next recheck doesn't re-offer (and re-speak) the
        // same candidate two minutes later — it must beat this saving by a real margin first.
        _state.value.fasterRoute?.let {
            dismissedFasterKey = routeKey(it)
            dismissedFasterSaving = _state.value.fasterSavingSeconds
        }
        _state.update { it.copy(fasterRoute = null, fasterSavingSeconds = 0.0) }
        note("dismissed faster route")
    }

    // --- live re-check ------------------------------------------------------

    private fun maybeRecheck(loc: LatLng, nav: NavState) {
        if (replayMode) return // hermetic replays never fetch live traffic/routes
        if (!liveRechecks) return // privacy opt-out: no periodic current-position requests
        val now = SystemClock.elapsedRealtime()
        // A DEGRADED adopted route (abbreviated steps from the Google fallback, or no live
        // traffic) already has a silent heal below - but on the ~2 min cadence the driver sat
        // with a nameless banner disagreeing with the blue line for minutes after a reroute
        // (issue #237). While degraded, recheck on a short interval so the heal lands within
        // seconds of the open router recovering; bounded to a few tries per route (then back to
        // the normal cadence) so a genuinely offline/trafficless drive doesn't poll forever.
        val currentRoute = _state.value.route
        if (currentRoute !== degradedRouteRef) {
            degradedRouteRef = currentRoute
            degradedFastRechecks = 0
        }
        val degraded = currentRoute != null && (!currentRoute.hasRealSteps || !currentRoute.hasLiveTraffic)
        val fastHeal = degraded && degradedFastRechecks < DEGRADED_FAST_TRIES
        // Spread by +/-25%, redrawn after every recheck: an exact 120 s beat is a rhythm every
        // install shares (see Jitter).
        val interval = ((if (fastHeal) DEGRADED_RECHECK_INTERVAL_MS else RECHECK_INTERVAL_MS) * recheckSpread).toLong()
        if (now - lastRecheckMs < interval) return
        if (nav.offRoute || nav.remainingDistance < MIN_RECHECK_DISTANCE_M) return
        if (recheckJob?.isActive == true) return
        // An offer is already on screen — don't fetch/re-speak over it every interval.
        if (_state.value.fasterRoute != null) return
        val dest = destination ?: return
        lastRecheckMs = now
        recheckSpread = app.vela.core.util.Jitter.factor()
        if (fastHeal) degradedFastRechecks++
        // Named remainingStops (not `remaining`) — the launch body below declares `remaining` for the
        // remaining DURATION, which would shadow this and hand a future edit seconds instead of stops.
        val remainingStops = synchronized(stopLock) { stops.drop(passedStops) }
        val gen = sessionGen
        recheckJob = scope.launch {
            val candidate = runCatching { dataSource.directions(loc, dest, mode, remainingStops.map { it.location }, avoidTolls = RoutingPrefs.avoidTolls, avoidHighways = RoutingPrefs.avoidHighways, avoidFerries = RoutingPrefs.avoidFerries) }.getOrNull()
                ?.let { driveable(it, loc, dest) }?.takeIf { it.reaches(dest) }
                ?: run { note("recheck: no usable candidate"); return@launch }
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
            // A TRAFFICLESS candidate (Google fetch failed -> free-flow ETA) must never drive the
            // ETA calibration or a faster-route offer: free-flow is systematically optimiztic, so
            // against a traffic-aware baseline it always "wins" - the real-drive 2026-07-15 report
            // (white suspiciously-fast ETA after accepting, syncing back to reality a recheck
            // later) was exactly this. Trafficless can still silently heal abbreviated steps
            // below (same course, so its GEOMETRY is fine even when its ETA is not comparable).
            val trafficAware = candidate.hasLiveTraffic
            // Even with NO course change the traffic ahead keeps evolving, and this candidate IS
            // a fresh traffic-aware ETA from the live position. When it follows the route we're
            // already driving (every sampled point within SAME_COURSE_M of the current line -
            // tighter than the 700 m jam-detour test, which can't tell a parallel alternate from
            // "same road"), recalibrate the published ETA to it instead of throwing it away: the
            // engine's remaining time otherwise rides the traffic ratio captured at the LAST
            // route fetch for the entire drive (user 2026-07-14). The multiplicative form makes
            // the new scale independent of the old one (remaining already carries etaScale), and
            // the offer logic below then compares candidates against a LIVE baseline too.
            val current = _state.value.route
            val sameCourse = current != null && candidateEta > 0.0 &&
                !app.vela.core.data.RouteGeometry.divergent(current, candidate, SAME_COURSE_M)
            if (sameCourse && remaining > 120.0 && trafficAware) {
                etaScale = (etaScale * candidateEta / remaining).coerceIn(0.5, 2.5)
            }
            // ABBREVIATED-STEPS SELF-HEAL: an OSRM blip mid-drive makes a reroute fall back to
            // Google's abbreviated steps (complete polyline, a fraction of the turns - the banner
            // and voice disagree with the blue line, user real-drive report 2026-07-14), and an
            // adopted one used to stay degraded for the REST of the drive because this recheck
            // only cared about faster routes. When the open router has recovered, the same-course
            // candidate carries the full step list - adopt it silently: same path, fresh traffic,
            // real turns. Tagged at the source (Route.abbreviatedSteps), so a healthy route can
            // never be churned by this. Same self-heal for a TRAFFICLESS current route (white ETA,
            // real-drive 2026-07-15): once a same-course candidate carries live traffic again,
            // adopt it so the ETA turns traffic-colored and honest instead of staying white for
            // the rest of the drive. Either upgrade qualifies; neither quality may downgrade.
            val stepsUpgrade = !current!!.hasRealSteps && candidate.hasRealSteps
            val trafficUpgrade = !current.hasLiveTraffic && candidate.hasLiveTraffic
            val noDowngrade = (!current.hasRealSteps || candidate.hasRealSteps) &&
                (!current.hasLiveTraffic || candidate.hasLiveTraffic)
            if (sameCourse && candidate.drivable && (stepsUpgrade || trafficUpgrade) && noDowngrade) {
                lastSwapReason = "heal"
                val marks = NavEngine.stopMarks(candidate, remainingStops.map { it.location })
                synchronized(stopLock) {
                    stops = remainingStops
                    stopMarks = marks
                    passedStops = 0
                    planRoute = candidate
                }
                lastRerouteAdoptMs = SystemClock.elapsedRealtime()
                etaScale = 1.0
                _state.update {
                    it.copy(
                        route = candidate,
                        nav = NavState(
                            distanceToNextManeuver = candidate.maneuvers.firstOrNull()?.distanceMeters ?: 0.0,
                            remainingDistance = candidate.distanceMeters,
                            remainingDuration = candidateEta,
                        ),
                        maneuverText = candidate.maneuvers.firstOrNull()?.instruction.orEmpty(),
                        remainingDistance = candidate.distanceMeters,
                        remainingDuration = candidateEta,
                        fasterRoute = null,
                    )
                }
                diag.record(
                    "nav",
                    "recheck upgraded route (steps ${current.maneuvers.size} -> ${candidate.maneuvers.size}, " +
                        "traffic ${current.hasLiveTraffic} -> ${candidate.hasLiveTraffic})",
                )
                return@launch
            }
            val saving = remaining - candidateEta
            // A candidate similar to one the user DISMISSED is only re-offered when it beats the
            // dismissed saving by a real margin — not re-spoken verbatim every 2 minutes.
            if (routeKey(candidate) == dismissedFasterKey && saving < dismissedFasterSaving + 60.0) return@launch
            // Offer it only if it saves real time AND isn't implausibly short — a candidate claiming to cut
            // the same trip to a fraction of the time left is a bad route, not a real faster path. And only
            // when its ETA is traffic-aware and its steps are real (never trade a healthy route for an
            // abbreviated one on the strength of an incomparable ETA).
            val plausible = candidateEta in (remaining * MIN_PLAUSIBLE_ETA_FRACTION)..(remaining * 0.9)
            if (trafficAware && candidate.hasRealSteps && saving > FASTER_THRESHOLD_S && plausible) {
                note("recheck: offering faster route, saves ${saving.toInt()} s (${candidate.maneuvers.size} steps)")
                _state.update { it.copy(fasterRoute = candidate, fasterSavingSeconds = saving) }
                voice.fasterRouteChime()
                kotlinx.coroutines.delay(FASTER_CHIME_LEAD_MS) // let the chime finish before the voice
                voice.speak(
                    app.vela.core.i18n.NavStringsRegistry.current()
                        .fasterRouteAvailable((saving / 60).toInt().coerceAtLeast(1)),
                )
            } else {
                note(
                    "recheck: kept current route (candidate saves ${saving.toInt()} s, traffic=$trafficAware, " +
                        "abbreviated=${candidate.abbreviatedSteps}, plausible=$plausible, sameCourse=$sameCourse)",
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
    fun replaySetRoute(r: Route, chime: Boolean = true) {
        if (r.polyline.size < 2) return
        synchronized(stopLock) { stops = emptyList(); stopMarks = emptyList(); passedStops = 0; planRoute = r }
        etaScale = 1.0
        // The reroute earcon plays at recorded swap points too (user 2026-07-16: "didn't hear
        // the rerouting sound in the replay") - replay is the nav test bench, and the chime is
        // the audible marker the route changed here. Trips record WHY each swap happened now
        // (the RD line's reason field), so the caller passes chime=false for swaps that were
        // quiet live (faster/heal/stop-added); reason-less old recordings chime for every swap.
        if (chime) voice.reroutingChime()
        note("replay: route swap (${r.maneuvers.size} steps)")
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

    private fun reroute(loc: LatLng, headingDeg: Double? = null) {
        if (replayMode) {
            note("replay: live reroute suppressed (recorded swaps play back instead)")
            return
        }
        val dest = destination ?: return
        val now = SystemClock.elapsedRealtime()
        // Single-flight + cooldown: one fetch at a time, and no re-adoption storm while GPS is
        // biased toward a parallel road (the new route lands, the biased fixes are >45 m from IT
        // too, 4 s later another "Rerouting…" — forever). The engine keeps emitting RerouteNeeded
        // while deviated (the latch clears on failure below), so a skipped request here is simply
        // retried by the next qualifying fix after the cooldown.
        val gate = rerouteGate(rerouteJob?.isActive == true, rerouteStartedMs, lastRerouteAdoptMs, now, rerouteDeadlineMs)
        // A skipped request must leave a way back in (issue #258, several reroutes close together):
        // RerouteNeeded fires on the RISING EDGE of the off-route latch, so a request dropped by the
        // cooldown - a route adopted seconds ago that the driver is already off, often because it
        // was computed from where the car was when the fetch started - left the latch set and
        // nothing ever asked again until the driver happened back onto the line. Clear it on the
        // location thread like a failure does, so a few more deviated fixes ask again and the
        // first one past the cooldown starts. An in-flight job needs no help: it adopts (fresh
        // state), fails (clears the latch itself) or is abandoned past its deadline.
        if (rerouteSkipRetries(gate)) pendingLatchClear.set(true)
        when (gate) {
            RerouteGate.SKIP_IN_FLIGHT, RerouteGate.SKIP_COOLDOWN -> return
            RerouteGate.ABANDON_STUCK_AND_START -> {
                note("previous reroute wedged past its deadline - abandoning it and retrying")
                rerouteJob?.cancel()
            }
            RerouteGate.START -> Unit
        }
        // Announce sparsely: the first attempt of a burst speaks, silent retries don't re-announce.
        if (now - lastRerouteSpokeMs > REROUTE_SPEAK_MIN_MS) {
            lastRerouteSpokeMs = now
            // Google's earcon first (a soft two-note chime), then the spoken word - the chime
            // registers even when a prompt is mid-sentence or the ear expects music (user 2026-07-16).
            voice.reroutingChime()
            voice.speak(app.vela.core.i18n.NavStringsRegistry.current().rerouting(), interrupt = true)
            // A buzz too (its own pattern, see Haptics.reroute) — the voice is useless muted or on
            // a windy ride, and the banner's "rerouting…" needs eyes on the screen.
            haptics.reroute(mode)
        }
        // Reroute THROUGH the stops you haven't reached yet — not straight to the final destination
        // (that used to silently drop your remaining stops on any off-route wobble).
        val remainingStops = synchronized(stopLock) { stops.drop(passedStops) }
        val gen = sessionGen
        // The route we were following when we went off-route. If the driver returns to THIS line while
        // we're fetching (see the back-on-course check below), we abandon the reroute rather than swap.
        val fromRoute = _state.value.route
        rerouteStartedMs = now
        val attempt = rerouteAttempt(rerouteFailStreak)
        rerouteDeadlineMs = attempt.timeoutMs
        if (!attempt.urgent) {
            note("reroute escalating to the full ladder after $rerouteFailStreak failed attempts")
        }
        rerouteJob = scope.launch {
            // A reroute that doesn't actually reach the destination is a bad result — keep guiding on the
            // current route rather than swapping to a truncated/wrong one. (Guard unchanged: the route still
            // ends at the same final dest even with waypoints in between.)
            // HARD DEADLINE on the fetch (2026-07-21, real-drive hang): a slow fetch could hold
            // this job for a minute or more — and the single-flight guard
            // above drops every new RerouteNeeded while it runs, which read as "the second reroute
            // hung" (the driver had to kill nav). A reroute computed from a position that old is
            // stale anyway; past the deadline, fail into the same retry-while-deviated path below
            // so the next qualifying fix fires a FRESH request from where the car actually is.
            // urgent = single-shot fetches, no divergence snap (issues #185/#236): the full
            // planning ladder regularly outlived this deadline on a weak link, so the timeout
            // canceled work that was about to succeed and the driver sat unrerouted through
            // repeated attempts. A lean route lands in seconds; the recheck loop restores
            // traffic/steps quality afterwards.
            // The fetch runs as an UNSTRUCTURED async so the deadline can actually ABANDON it
            // (issue #258). withTimeoutOrNull only interrupts at suspension points: a structured
            // child that blocks in non-cancellable work - a socket read that never returns, or the
            // offline engine's native compute - keeps this coroutine alive long past the deadline,
            // and `rerouteJob?.isActive` above then rejects EVERY later reroute for the rest of the
            // drive. That is the "Re-routing" spinner that never exits and only a restart clears
            // (reported on a real drive; the same trap the avoid path hit, see
            // AVOID_ONDEVICE_TIMEOUT_MS). The orphan finishes into the void and is discarded.
            val fetch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                runCatching {
                    dataSource.directions(
                        loc, dest, mode, remainingStops.map { it.location },
                        avoidTolls = RoutingPrefs.avoidTolls,
                        avoidHighways = RoutingPrefs.avoidHighways,
                        avoidFerries = RoutingPrefs.avoidFerries,
                        urgent = attempt.urgent,
                        // Pin the departure to where the car is pointing, so the answer is "given
                        // that you are going this way, what now" instead of "turn around".
                        departBearingDeg = headingDeg,
                        // The fetch's own share of the deadline (issue #557): the open router, Google
                        // and the downloaded region each get a slice instead of the first eating it all.
                        budgetMs = attempt.budgetMs,
                    )
                }
                    .getOrNull()?.let { routes ->
                        val left = attempt.timeoutMs - (SystemClock.elapsedRealtime() - now) - REROUTE_NAME_SLACK_MS
                        driveable(routes, loc, dest, nameWithinMs = left.coerceAtLeast(0L))
                    }?.takeIf { it.reaches(dest) }
            }
            val r = kotlinx.coroutines.withTimeoutOrNull(attempt.timeoutMs) { fetch.await() }
            val timedOut = r == null && !fetch.isCompleted
            if (r == null) fetch.cancel() // best effort; a wedged blocking read ignores this and is orphaned
            val tookMs = SystemClock.elapsedRealtime() - now
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
                note("reroute discarded — driver solidly back on the original route (streak ${backNav.onRouteStreak})")
                return@launch
            }
            if (r == null) {
                // FAILED (dead spot / OSRM 5xx / truncated result). The old code returned silently
                // and rerouting was DEAD for the rest of the excursion: RerouteNeeded is
                // edge-triggered on the offRoute latch, which never re-fires while still off the
                // old route. Flag the latch clear for the LOCATION THREAD to consume (writing nav
                // state from here raced the in-flight onLocation frame) — 4 more deviated fixes
                // then request again (~4 s natural backoff, OsmAnd-style retry-while-deviated).
                rerouteFailStreak++
                val why = if (timedOut) "deadline ${attempt.timeoutMs / 1000} s" else "nothing usable"
                note("reroute FAILED (streak $rerouteFailStreak, $why after $tookMs ms), will retry while off-route")
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
                note("reroute missing ${marks.count { it == null }}/${remainingStops.size} stops")
            }
            note(
                "reroute adopted: ${r.source.name.lowercase()} in $tookMs ms" +
                    (if (attempt.urgent) "" else " (full ladder)") +
                    (if (r.offline) ", offline" else "") +
                    (if (r.abbreviatedSteps) ", abbreviated steps" else "") +
                    (if (r.hasLiveTraffic) "" else ", no traffic"),
            )
            rerouteFailStreak = 0 // a route landed: back to lean, fast attempts
            lastSwapReason = "reroute"
            lastRecheckMs = SystemClock.elapsedRealtime()
            lastRerouteAdoptMs = SystemClock.elapsedRealtime()
            etaScale = 1.0 // the fresh route carries fresh traffic
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

    /**
     * The route this session may DRIVE out of a directions() reply. The reply is sorted by ETA
     * and one of Google's alternates can lead it, and those are PROVISIONAL: Google's polyline and
     * ETA with Google's abbreviated steps, whose positions are only guessed along the line. Driven
     * as-is, a 17.9 km faster route arrived with a single "Take exit 176" maneuver sitting at the
     * on-ramp the car was on, was announced at 30 feet, and the reroute that followed did the same
     * (real drive 2026-09-13, replayed with `probeTripSegmentRoute`). The picker names a provisional
     * route the moment it is picked; every fetch the session makes for itself has to do the same.
     * Naming that fails comes back tagged abbreviatedSteps, which the recheck heals and the
     * faster-route fence rejects; when the reply also carries a full-stepped open-router route,
     * that one is better guidance than Google's guessed steps, even a little slower.
     */
    private suspend fun driveable(routes: List<Route>, from: LatLng, dest: LatLng, nameWithinMs: Long? = null): Route? {
        val top = routes.firstOrNull() ?: return null
        if (top.drivable) return top
        suspend fun name(): Route? = runCatching { dataSource.nameRoute(top, from, dest, mode, RoutingPrefs.avoidTolls, RoutingPrefs.avoidHighways, RoutingPrefs.avoidFerries) }.getOrNull()
        // A reroute names inside what is left of its deadline (issue #557): naming is an open-router
        // snap, the very call that may be hanging. Past the budget the full-stepped open-router
        // route from the same reply below is used instead. Unstructured for the usual reason: a
        // blocking HTTP read ignores cancellation.
        val named = if (nameWithinMs == null) name() else {
            val d = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { name() }
            kotlinx.coroutines.withTimeoutOrNull(nameWithinMs) { d.await() } ?: run {
                d.cancel()
                diag.record("nav", "naming a provisional reroute ran past ${nameWithinMs} ms, using the reply's own route")
                null
            }
        }
        diag.record(
            "nav",
            "named a provisional route: ${top.maneuvers.size} -> ${named?.maneuvers?.size} steps, " +
                "abbreviated=${named?.abbreviatedSteps}, provisional=${named?.provisional}",
        )
        if (named != null && named.drivable && named.hasRealSteps) return named
        return routes.firstOrNull { it.drivable && it.hasRealSteps } ?: named?.takeIf { it.drivable }
            ?: routes.firstOrNull { it.drivable }
    }

    /** Does this route actually END near [dest]? A route whose last point is far from the destination is
     *  truncated or wrong; swapping to it mid-nav is the "10 min away / wrong final step" bug. */
    private fun Route.reaches(dest: LatLng) =
        polyline.lastOrNull()?.let { it.distanceTo(dest) <= REACH_TOLERANCE_M } ?: false

    // Public for destinationDisplay (callers build the arrive-step lines before start());
    // the tuning constants stay implementation detail by convention.
    companion object {
        /** Consecutive moving, on-route fixes before a paused drive resumes itself. Three fixes is
         *  a few seconds of actually driving, not a crawl across a forecourt that clips the route. */
        const val AUTO_RESUME_HITS = 3
        const val RECHECK_INTERVAL_MS = 120_000L   // re-check traffic every ~2 min
        const val DEGRADED_RECHECK_INTERVAL_MS = 20_000L // fast heal cadence while the route is degraded
        const val DEGRADED_FAST_TRIES = 6          // ~2 min of fast heal attempts per degraded route
        // A recheck candidate whose sampled points all sit within this of the current route line
        // counts as the SAME course -> its fresh ETA recalibrates the shown arrival time. Tighter
        // than the 700 m divergence default: a parallel arterial can sit inside 700 m of a highway
        // for miles, and calibrating our ETA from a route we are not driving would lie.
        const val SAME_COURSE_M = 250.0
        const val MIN_RECHECK_DISTANCE_M = 1_500.0 // don't bother near the destination
        const val FASTER_THRESHOLD_S = 90.0        // only offer if it saves real time
        const val FASTER_CHIME_LEAD_MS = 450L       // the faster-route chime, then the spoken offer
        const val REROUTE_COOLDOWN_MS = 10_000L    // min gap between ADOPTED reroutes (no reroute storms)
        // Deadline on one reroute FETCH: generous next to Google's 1-3 s but far under the retry
        // ladders' worst case; past it the position the request was computed from is stale anyway.
        const val REROUTE_FETCH_TIMEOUT_MS = 20_000L
        // Slack past the deadline before a still-running reroute job is declared wedged.
        const val REROUTE_STUCK_GRACE_MS = 5_000L
        // After this many reroute attempts in a row have come back with nothing, stop being lean
        // and use the FULL planning ladder - see [rerouteAttempt].
        const val REROUTE_ESCALATE_AFTER = 2
        // Deadline for an escalated attempt: the ladder makes several tries, so the lean deadline
        // would cut it off mid-way and we would never see the retry pay off.
        const val REROUTE_LADDER_TIMEOUT_MS = 40_000L

        /**
         * May a reroute start right now? Pure so the rule that broke in issue #258 is pinned by a
         * test instead of living inside a coroutine that needs a device to exercise.
         *
         * Single-flight is right, but it must never be PERMANENT: a fetch wedged in
         * non-cancellable I/O outlives its own deadline, and treating that job as "in flight"
         * forever rejected every later reroute for the rest of the drive - the spinner that only
         * a nav restart cleared. Past the deadline plus a grace window the job is declared dead.
         */
        fun rerouteGate(
            jobActive: Boolean,
            jobStartedMs: Long,
            lastAdoptMs: Long,
            now: Long,
            // The deadline THIS attempt is running under - an escalated attempt is allowed longer,
            // and judging it by the lean deadline would declare a healthy fetch wedged and kill it.
            deadlineMs: Long = REROUTE_FETCH_TIMEOUT_MS,
        ): RerouteGate = when {
            jobActive && now - jobStartedMs >= deadlineMs + REROUTE_STUCK_GRACE_MS ->
                RerouteGate.ABANDON_STUCK_AND_START
            jobActive -> RerouteGate.SKIP_IN_FLIGHT
            now - lastAdoptMs < REROUTE_COOLDOWN_MS -> RerouteGate.SKIP_COOLDOWN
            else -> RerouteGate.START
        }
        /** Does a request the gate turned away need the off-route latch cleared so a later fix can
         *  ask again? Only the cooldown: an in-flight job ends by adopting (fresh state), failing
         *  (clears the latch itself) or being abandoned past its deadline. */
        fun rerouteSkipRetries(gate: RerouteGate): Boolean = gate == RerouteGate.SKIP_COOLDOWN

        // Time an attempt keeps for itself after the directions fetch: naming a provisional top
        // and the bookkeeping. The fetch's budget is the deadline minus this.
        const val REROUTE_FINISH_RESERVE_MS = 4_000L
        // Naming gives up this long before the attempt's deadline, so a slow snap cannot turn a
        // good reply into a timeout.
        const val REROUTE_NAME_SLACK_MS = 500L

        /** How the next reroute attempt should be made, given how many have just failed. [budgetMs]
         *  is what the directions fetch itself may spend (issue #557). */
        data class RerouteAttempt(val urgent: Boolean, val timeoutMs: Long) {
            val budgetMs: Long get() = timeoutMs - REROUTE_FINISH_RESERVE_MS
        }

        /**
         * Reroute attempts start LEAN and ESCALATE (issue #258, second cause).
         *
         * A mid-drive reroute is `urgent`, which means a SINGLE-SHOT fetch with no retry ladder -
         * deliberately, because the full ladder used to outlive the deadline on a weak link and got
         * canceled just as it was about to succeed (issues #185/#236). But a single shot on a
         * genuinely flaky link can fail over and over, and nothing ever escalated: the driver sat
         * on "Re-routing" through attempt after attempt, while ENDING NAV AND STARTING AGAIN worked
         * first time - because a fresh plan is not urgent and gets the 3-try ladder. That is
         * exactly the reported workaround, and it is a different fault from the wedged job the
         * gate above handles.
         *
         * So: the first couple of attempts stay lean and fast, and after that we spend the time and
         * use the full ladder. Fast when the network is merely blipping, thorough when it is bad.
         */
        fun rerouteAttempt(failStreak: Int): RerouteAttempt =
            if (failStreak >= REROUTE_ESCALATE_AFTER) RerouteAttempt(urgent = false, timeoutMs = REROUTE_LADDER_TIMEOUT_MS)
            else RerouteAttempt(urgent = true, timeoutMs = REROUTE_FETCH_TIMEOUT_MS)

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

        /** Primary + secondary display lines for a destination, robust to partial data. Offline
         *  routing often has no business name — just "123 Main St" from the offline geocoder, a
         *  bare street from the street-fallback tier, or nothing but the tapped point. Primary =
         *  the name, else the address, else the raw coordinates (something always shows).
         *  Secondary = the address only when it adds something the primary line doesn't already
         *  say (an address search's "name" IS its address — don't print it twice). */
        fun destinationDisplay(name: String?, address: String?, dest: LatLng?): Pair<String, String?> {
            val n = name?.trim().orEmpty()
            val a = address?.trim().orEmpty()
            val primary = n.ifBlank {
                a.ifBlank {
                    dest?.let { String.format(java.util.Locale.US, "%.5f, %.5f", it.lat, it.lng) }.orEmpty()
                }
            }
            val secondary = a.takeIf { it.isNotBlank() && !it.equals(primary, ignoreCase = true) }
            return primary to secondary
        }
    }
}
