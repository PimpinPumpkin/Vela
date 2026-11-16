package app.vela.core.nav

/**
 * Where a rider stands on a GPX track ride (TrackRide), for the voice and the turn card
 * (SPEC 4.3, Riding a GPX track). [next] is fed whether each fix is on the track's line (inside
 * the off-route corridor) and returns the new state and the line to say, if any:
 *
 * - the first fix on the line starts the route ([Say.STARTED_HERE] when the drive began on it,
 *   [Say.STARTED] when the rider rode to it); a first fix off it says to go to the start;
 * - [LEAVE_FIXES] fixes in a row off the line leave the route, [REJOIN_FIXES] in a row back on
 *   it follow it again, so one stray fix says nothing.
 */
object TrackGuide {
    enum class Phase {
        /** No fix yet since the drive started. */
        WAITING,
        /** Not yet on the line: the rider is riding to the track's start. */
        TO_START,
        ON,
        /** Left the line after being on it. */
        OFF,
    }

    enum class Say { GO_TO_START, STARTED_HERE, STARTED, LEFT, FOLLOWING }

    data class State(val phase: Phase = Phase.WAITING, val hits: Int = 0)

    /** Fixes in a row off the line before the route is left. */
    const val LEAVE_FIXES = 3
    /** Fixes in a row on the line before a left route is followed again. */
    const val REJOIN_FIXES = 2

    fun next(s: State, onLine: Boolean): Pair<State, Say?> = when (s.phase) {
        Phase.WAITING -> if (onLine) State(Phase.ON) to Say.STARTED_HERE else State(Phase.TO_START) to Say.GO_TO_START
        Phase.TO_START -> if (onLine) State(Phase.ON) to Say.STARTED else s to null
        Phase.ON -> when {
            onLine -> State(Phase.ON) to null
            s.hits + 1 >= LEAVE_FIXES -> State(Phase.OFF) to Say.LEFT
            else -> State(Phase.ON, s.hits + 1) to null
        }
        Phase.OFF -> when {
            !onLine -> State(Phase.OFF) to null
            s.hits + 1 >= REJOIN_FIXES -> State(Phase.ON) to Say.FOLLOWING
            else -> State(Phase.OFF, s.hits + 1) to null
        }
    }

    /** The rider is away from the track as far as guidance goes: the turn card shows the way to
     *  it and the engine's prompts wait. */
    fun away(s: State): Boolean = s.phase == Phase.TO_START || s.phase == Phase.OFF
}
