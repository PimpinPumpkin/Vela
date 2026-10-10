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
                                       // slots, not meters: the thresholds scale with live speed
    val traveledM: Double = 0.0,       // monotonic meters traveled along the route (forward-progress anchor)
    val reacquireHits: Int = 0,        // consecutive far global re-acquire candidates — a big along-jump
                                       // must persist before it's adopted (single outliers can't teleport)
    val onRouteStreak: Int = 0,        // consecutive on-corridor+moving fixes — the SUSTAINED "back on the
                                       // line" signal (offRoute clears on ONE grazing fix, too weak to
                                       // abandon a reroute on; NavSession's back-on-course discard gates on
                                       // this instead so a single spurious graze can't kill a real reroute)
    val rerouteBlocked: Boolean = false, // an off-route excursion latched INSIDE the destination zone —
                                         // the deferred reroute fires if it leaves the zone (edge-only
                                         // suppression was a permanent silent limbo)
    val stopCuedAtM: Double = -1.0,    // the mark of the stop whose approach has been said ([StopAhead]),
                                       // so it is said once; a new route starts a fresh NavState
    val chainedStep: Int = -1,         // the step already said as the "then ..." of the turn before it
                                       // (NavEngine.chainedNext): its own approach is not said again
)

/**
 * The next stop on a drive with stops, as [NavEngine.update] needs it to say the stop is coming
 * the way it says the destination is: [atM] is its mark along the route, [side] "left" or "right"
 * when its pin sits clearly to one side of the road there ([NavEngine.stopSide]), null for
 * "ahead". [lotTurn] is the index of the maneuver that turns into the stop's parking lot, read
 * from the map's own road data ([ParkingLotTurn], [NavEngine.lotTurnsBefore]), so that turn can
 * be said as "into the parking lot"; -1 when none does or the map has not answered yet.
 */
data class StopAhead(val atM: Double, val label: String, val side: String? = null, val lotTurn: Int = -1)

/** Side-effects the engine asks the UI layer to perform. */
sealed interface NavEvent {
    data class Speak(val text: String, val interrupt: Boolean = false) : NavEvent
    /** Haptic cue for a turn. [approaching] = a light "get ready" tick at the
     *  pre-turn prompt; otherwise the firm at-the-turn buzz (direction-coded). */
    data class Haptic(val type: ManeuverType, val approaching: Boolean = false) : NavEvent
    data object RerouteNeeded : NavEvent
    data object Arrived : NavEvent
}
