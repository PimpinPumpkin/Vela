package app.vela.core.nav

import app.vela.core.model.ManeuverType

/** Immutable snapshot of progress along a route. Driven by [NavEngine.update]. */
data class NavState(
    val stepIndex: Int = 0,
    val distanceToNextManeuver: Double = 0.0,
    val remainingDistance: Double = 0.0,
    val remainingDuration: Double = 0.0,
    val offRoute: Boolean = false,
    val offRouteHits: Int = 0,
    val arrived: Boolean = false,
    val spoken: Set<Int> = emptySet(), // prompt thresholds already spoken this step
    val traveledM: Double = 0.0,       // monotonic metres travelled along the route (forward-progress anchor)
)

/** Side-effects the engine asks the UI layer to perform. */
sealed interface NavEvent {
    data class Speak(val text: String, val interrupt: Boolean = false) : NavEvent
    /** Haptic cue for a turn. [approaching] = a light "get ready" tick at the
     *  pre-turn prompt; otherwise the firm at-the-turn buzz (direction-coded). */
    data class Haptic(val type: ManeuverType, val approaching: Boolean = false) : NavEvent
    data object RerouteNeeded : NavEvent
    data object Arrived : NavEvent
}
