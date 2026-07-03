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
            maneuverText = first,
            remainingDistance = route.distanceMeters,
            remainingDuration = route.durationInTrafficSeconds ?: route.durationSeconds,
            destinationLabel = destinationLabel,
            tripDistanceMeters = route.distanceMeters,
        )
        voice.speak("Starting navigation. $first")
        diag.record(
            "nav",
            "start → ${destinationLabel.ifBlank { "destination" }} " +
                "(${route.distanceMeters?.toInt()} m, ${route.maneuvers.size} steps, " +
                "ETA ${route.durationInTrafficSeconds ?: route.durationSeconds}s)",
        )
    }

    fun stop() {
        recheckJob?.cancel()
        voice.stop()
        destination = null
        synchronized(stopLock) { stops = emptyList(); stopMarks = emptyList(); passedStops = 0; planRoute = null }
        _state.value = State()
    }

    fun onLocation(loc: LatLng, imperial: Boolean = false) {
        val s = _state.value
        val route = s.route ?: return
        if (!s.navigating || s.arrived) return

        val (next, events) = NavEngine.update(route, s.nav, loc, imperial)
        val maneuver = route.maneuvers.getOrNull(next.stepIndex)
        _state.update {
            it.copy(
                nav = next,
                maneuverText = maneuver?.instruction.orEmpty(),
                remainingDistance = next.remainingDistance,
                remainingDuration = next.remainingDuration,
            )
        }
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
            voice.speak(if (label.isNotBlank()) "You've reached $label" else "You've reached your stop")
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
                nav = NavState(),
                maneuverText = first,
                remainingDistance = faster.distanceMeters,
                remainingDuration = faster.durationInTrafficSeconds ?: faster.durationSeconds,
                fasterRoute = null,
                fasterSavingSeconds = 0.0,
            )
        }
        voice.speak("Taking the faster route. $first", interrupt = true)
    }

    fun dismissFasterRoute() = _state.update { it.copy(fasterRoute = null, fasterSavingSeconds = 0.0) }

    // --- live re-check ------------------------------------------------------

    private fun maybeRecheck(loc: LatLng, nav: NavState) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecheckMs < RECHECK_INTERVAL_MS) return
        if (nav.offRoute || nav.remainingDistance < MIN_RECHECK_DISTANCE_M) return
        if (recheckJob?.isActive == true) return
        val dest = destination ?: return
        lastRecheckMs = now
        // Named remainingStops (not `remaining`) — the launch body below declares `remaining` for the
        // remaining DURATION, which would shadow this and hand a future edit seconds instead of stops.
        val remainingStops = synchronized(stopLock) { stops.drop(passedStops) }
        recheckJob = scope.launch {
            val candidate = runCatching { dataSource.directions(loc, dest, mode, remainingStops.map { it.location }).firstOrNull() }.getOrNull()
                ?.takeIf { it.reaches(dest) } ?: return@launch
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
            // Offer it only if it saves real time AND isn't implausibly short — a candidate claiming to cut
            // the same trip to a fraction of the time left is a bad route, not a real faster path.
            if (saving > FASTER_THRESHOLD_S && candidateEta in (remaining * MIN_PLAUSIBLE_ETA_FRACTION)..(remaining * 0.9)) {
                _state.update { it.copy(fasterRoute = candidate, fasterSavingSeconds = saving) }
                voice.speak("Faster route available, saving about ${(saving / 60).toInt()} minutes")
            }
        }
    }

    private fun reroute(loc: LatLng) {
        val dest = destination ?: return
        // Reroute THROUGH the stops you haven't reached yet — not straight to the final destination
        // (that used to silently drop your remaining stops on any off-route wobble).
        val remainingStops = synchronized(stopLock) { stops.drop(passedStops) }
        scope.launch {
            voice.speak("Rerouting", interrupt = true)
            // A reroute that doesn't actually reach the destination is a bad result — keep guiding on the
            // current route rather than swapping to a truncated/wrong one. (Guard unchanged: the route still
            // ends at the same final dest even with waypoints in between.)
            val r = runCatching { dataSource.directions(loc, dest, mode, remainingStops.map { it.location }) }
                .getOrNull()?.firstOrNull()?.takeIf { it.reaches(dest) } ?: return@launch
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
                voice.speak("Couldn't include your stops in this route. I'll keep trying.")
                diag.record("nav", "reroute missing ${marks.count { it == null }}/${remainingStops.size} stops")
            }
            lastRecheckMs = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    route = r,
                    nav = NavState(),
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
