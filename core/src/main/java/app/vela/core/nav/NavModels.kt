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
    val spoken: Set<Int> = emptySet(), // prompt band SLOTS (0=far, 1=near) already spoken this step —
                                       // slots, not metres: the thresholds scale with live speed
    val traveledM: Double = 0.0,       // monotonic metres travelled along the route (forward-progress anchor)
    val reacquireHits: Int = 0,        // consecutive far global re-acquire candidates — a big along-jump
                                       // must persist before it's adopted (single outliers can't teleport)
    val onRouteStreak: Int = 0,        // consecutive on-corridor+moving fixes — the SUSTAINED "back on the
                                       // line" signal (offRoute clears on ONE grazing fix, too weak to
                                       // abandon a reroute on; NavSession's back-on-course discard gates on
                                       // this instead so a single spurious graze can't kill a real reroute)
    val rerouteBlocked: Boolean = false, // an off-route excursion latched INSIDE the destination zone —
                                         // the deferred reroute fires if it leaves the zone (edge-only
                                         // suppression was a permanent silent limbo)
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
