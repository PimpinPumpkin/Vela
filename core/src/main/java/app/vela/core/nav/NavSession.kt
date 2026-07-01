package app.vela.core.nav

import android.os.SystemClock
import app.vela.core.data.MapDataSource
import app.vela.core.feedback.Haptics
import app.vela.core.model.LatLng
import app.vela.core.model.Route
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

    fun start(route: Route, destination: LatLng, destinationLabel: String = "", voiceEngine: String? = null) {
        this.destination = destination
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
                is NavEvent.Haptic -> haptics.cue(ev.type, ev.approaching)
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
        maybeRecheck(loc, next)
    }

    fun acceptFasterRoute() {
        val faster = _state.value.fasterRoute ?: return
        val first = faster.maneuvers.firstOrNull()?.instruction.orEmpty()
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
        recheckJob = scope.launch {
            val candidate = runCatching { dataSource.directions(loc, dest).firstOrNull() }.getOrNull()
                ?.takeIf { it.reaches(dest) } ?: return@launch
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
        scope.launch {
            voice.speak("Rerouting", interrupt = true)
            // A reroute that doesn't actually reach the destination is a bad result — keep guiding on the
            // current route rather than swapping to a truncated/wrong one.
            val r = runCatching { dataSource.directions(loc, dest) }.getOrNull()?.firstOrNull()
                ?.takeIf { it.reaches(dest) } ?: return@launch
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
    }
}
