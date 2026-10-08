package app.vela.core.nav

import app.vela.core.model.ManeuverType

/**
 * How much spoken guidance says (issue #718): a step between everything and the mute button, for
 * a driver who wants music or a book and still does not want to sail past an exit.
 *
 *  - [Mode.FULL] is what Vela has always done: up to three lines a turn (far, near, at the turn),
 *    lanes and signs included.
 *  - [Mode.BRIEF] says each maneuver ONCE, in its short form with no street name: "In 150 meters,
 *    turn left". Exits and forks are said at the far prompt (about 35 s out), turns at the near
 *    one (about 10 s), and nothing is repeated at the turn itself.
 *  - [Mode.EXITS] is BRIEF for the maneuvers that are expensive to miss and silence for the rest:
 *    ramps, forks, keep left or right, U-turns and the arrival. An ordinary turn is also said when
 *    the car is moving at [FAST_MPS] or more, because the route carries no road class and a turn
 *    taken at speed is an exit in everything but name. Roundabouts and merges stay silent.
 *
 * NOTHING ON SCREEN CHANGES, and the buzz at a turn is kept in every mode. Rerouting, camera and
 * speed alerts are their own lines and are not touched.
 *
 * Same `:core` flag seam as [SpokenRoadNames]: the preference lives in `:app`
 * (`app.vela.ui.SpokenDetail`), which pushes the value down here.
 */
object SpokenDetail {
    enum class Mode { FULL, BRIEF, EXITS }

    /** Set from the app at startup and whenever the Settings choice moves. */
    @Volatile var mode: Mode = Mode.FULL

    /** 80 km/h (50 mph): at or above this an ordinary turn is spoken in [Mode.EXITS]. Lower, a
     *  45 mph suburban road counted as a highway. */
    const val FAST_MPS = 22.2

    private val EXIT_LIKE = setOf(
        ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT,
        ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT, ManeuverType.UTURN,
    )
    private val NEVER_IN_EXITS = setOf(ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT, ManeuverType.MERGE)

    /** A maneuver said from far out: missing it costs a detour, and it is taken at speed. */
    fun exitLike(type: ManeuverType): Boolean = type in EXIT_LIKE

    /** Whether [type] is spoken at all in [mode], for a car moving at [speedMps]. */
    fun speaks(mode: Mode, type: ManeuverType, speedMps: Double): Boolean = when (mode) {
        Mode.FULL, Mode.BRIEF -> true
        Mode.EXITS -> type == ManeuverType.ARRIVE || exitLike(type) || (type !in NEVER_IN_EXITS && speedMps >= FAST_MPS)
    }
}
