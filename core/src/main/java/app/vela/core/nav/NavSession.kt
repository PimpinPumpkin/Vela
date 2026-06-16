package app.vela.core.nav

import android.os.SystemClock
import app.vela.core.data.MapDataSource
import app.vela.core.model.LatLng
import app.vela.core.model.Route
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var destination: LatLng? = null
    private var lastRecheckMs = 0L
    private var recheckJob: Job? = null

    fun start(route: Route, destination: LatLng, voiceEngine: String? = null) {
        this.destination = destination
        voice.init(voiceEngine)
        lastRecheckMs = SystemClock.elapsedRealtime()
        val first = route.maneuvers.firstOrNull()?.instruction.orEmpty()
        _state.value = State(
            navigating = true,
            route = route,
            maneuverText = first,
            remainingDistance = route.distanceMeters,
            remainingDuration = route.durationInTrafficSeconds ?: route.durationSeconds,
        )
        voice.speak("Starting navigation. $first")
    }

    fun stop() {
        recheckJob?.cancel()
        voice.stop()
        destination = null
        _state.value = State()
    }

    fun onLocation(loc: LatLng) {
        val s = _state.value
        val route = s.route ?: return
        if (!s.navigating || s.arrived) return

        val (next, events) = NavEngine.update(route, s.nav, loc)
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
                NavEvent.Arrived -> _state.update { it.copy(navigating = false, arrived = true) }
                NavEvent.RerouteNeeded -> reroute(loc)
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
            val candidate = runCatching { dataSource.directions(loc, dest).firstOrNull() }.getOrNull() ?: return@launch
            val candidateEta = candidate.durationInTrafficSeconds ?: candidate.durationSeconds
            val remaining = _state.value.remainingDuration
            val saving = remaining - candidateEta
            if (saving > FASTER_THRESHOLD_S && candidateEta < remaining * 0.9) {
                _state.update { it.copy(fasterRoute = candidate, fasterSavingSeconds = saving) }
                voice.speak("Faster route available, saving about ${(saving / 60).toInt()} minutes")
            }
        }
    }

    private fun reroute(loc: LatLng) {
        val dest = destination ?: return
        scope.launch {
            voice.speak("Rerouting", interrupt = true)
            val r = runCatching { dataSource.directions(loc, dest) }.getOrNull()?.firstOrNull() ?: return@launch
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

    private companion object {
        const val RECHECK_INTERVAL_MS = 120_000L   // re-check traffic every ~2 min
        const val MIN_RECHECK_DISTANCE_M = 1_500.0 // don't bother near the destination
        const val FASTER_THRESHOLD_S = 90.0        // only offer if it saves real time
    }
}
