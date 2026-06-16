package app.vela.core.nav

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
)

/** Side-effects the engine asks the UI layer to perform. */
sealed interface NavEvent {
    data class Speak(val text: String, val interrupt: Boolean = false) : NavEvent
    data object RerouteNeeded : NavEvent
    data object Arrived : NavEvent
}
