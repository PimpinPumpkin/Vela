package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Offline turn-by-turn auditor. Replays a recorded GPS track through the *exact same* [NavEngine]
 * the live app uses, then diffs what the nav cards + voice claimed against where the maneuvers
 * really are on the plotted route — so a drive's "the app told me to exit 6 miles early, then went
 * silent at the real exit" can be SEEN deterministically from a saved trip, with no memory of where
 * it broke. Pure `:core` logic; runs in unit tests and over saved trips alike.
 *
 * The truth reference is each fix's / maneuver's distance *along the route* (global projection onto
 * the polyline); the engine's own banner distance comes from its windowed monotonic progress. A
 * large gap between the two is the bug — the card lying about how far the turn is. Caveat: during a
 * genuine reroute the engine intentionally holds progress, so a few inflated card errors right after
 * going off-route are expected, not defects.
 */
object NavReplay {

    /** One frame of the audit: what the banner showed + what was spoken at a single fix. */
    data class CardSnapshot(
        val fixIndex: Int,
        val alongM: Double,            // how far along the route this fix actually is (global projection)
        val stepIndex: Int,            // maneuver the banner was pointing at
        val instruction: String,       // the banner's current instruction text
        val distanceToNextM: Double,   // the banner's "distance to next turn"
        val trueDistanceToNextM: Double, // the REAL along-route distance to that maneuver
        val spoke: List<String>,       // voice lines emitted at this fix (empty on most fixes)
    ) {
        /** How far the card's distance was from reality at this fix. */
        val cardErrorM: Double get() = abs(distanceToNextM - trueDistanceToNextM)
    }

    /** Per-maneuver verdict: was it announced, on time, at the right place. */
    data class ManeuverDiff(
        val index: Int,
        val type: ManeuverType,
        val instruction: String,
        val actualAlongM: Double,        // the turn's true position along the route
        val announced: Boolean,          // any pre-turn "In X, …" prompt fired for it
        val turnNowFired: Boolean,       // the at-the-turn callout (or "you have arrived") fired
        val firstAnnounceAheadM: Double?, // true along-route distance to the turn when first announced (null = never)
        val worstCardErrorM: Double,     // worst |card distance − real distance| while it was the target
        val nearestApproachM: Double,    // closest the track actually came to the turn (along-route)
    ) {
        private val isTurn: Boolean get() = type != ManeuverType.ARRIVE && type != ManeuverType.DEPART

        /** Heuristics for "this maneuver's guidance and the blue line disagree" — the things that
         *  made the real-world drive wrong. Tuned to flag the field bugs without crying wolf on a
         *  normal drive (thresholds well outside the prompt distances + fix spacing). */
        val suspect: Boolean get() =
            worstCardErrorM > 300.0 ||                                  // card claimed a wildly wrong distance
            (isTurn && !announced && nearestApproachM <= 60.0) ||       // drove past the turn, never announced (silent exit)
            (isTurn && !turnNowFired && nearestApproachM <= 40.0) ||    // drove through it with no "turn now"
            (firstAnnounceAheadM != null && firstAnnounceAheadM > 700.0) // announced far too early (the "6 miles early" bug)

        /** Why it's suspect, for the report (empty when it isn't). */
        val flags: List<String> get() = buildList {
            if (worstCardErrorM > 300.0) add("card off by ${m(worstCardErrorM)}")
            if (isTurn && !announced && nearestApproachM <= 60.0) add("SILENT (never announced, drove within ${m(nearestApproachM)})")
            if (isTurn && !turnNowFired && nearestApproachM <= 40.0) add("no turn-now cue")
            firstAnnounceAheadM?.let { if (it > 700.0) add("announced ${m(it)} early") }
        }
    }

    /** The full audit of one trip: every card frame + the per-maneuver verdicts. */
    data class Report(
        val cards: List<CardSnapshot>,
        val maneuvers: List<ManeuverDiff>,
    ) {
        val suspects: List<ManeuverDiff> get() = maneuvers.filter { it.suspect }

        /** Worst single card-distance error seen anywhere on the drive. */
        val worstCardErrorM: Double get() = cards.maxOfOrNull { it.cardErrorM } ?: 0.0

        /** A human-readable digest — the thing to eyeball when a real travel log comes in. */
        fun summary(): String = buildString {
            appendLine("NavReplay: ${cards.size} fixes, ${maneuvers.size} maneuvers, ${suspects.size} suspect")
            appendLine("worst card-distance error: ${m(worstCardErrorM)}")
            for (d in maneuvers) {
                val mark = if (d.suspect) "‼" else "·"
                val ann = d.firstAnnounceAheadM?.let { "announced ${m(it)} out" } ?: "not announced"
                appendLine("  $mark [${d.index}] ${d.type} — \"${d.instruction.take(48)}\"")
                appendLine("      $ann; turn-now=${d.turnNowFired}; worst card err ${m(d.worstCardErrorM)}; nearest ${m(d.nearestApproachM)}")
                if (d.flags.isNotEmpty()) appendLine("      → ${d.flags.joinToString("; ")}")
            }
        }
    }

    /**
     * Replay [fixes] (in order) through [NavEngine] against [route] and return the audit. [imperial]
     * only affects the spoken-prompt text, not the geometry. Pass the trip's recorded fixes as
     * [LatLng]s (drop the timestamps — the engine is per-fix stateless on time).
     */
    fun analyze(route: Route, fixes: List<LatLng>, imperial: Boolean = true): Report {
        val maneuvers = route.maneuvers
        val n = maneuvers.size
        if (n == 0 || fixes.isEmpty()) return Report(emptyList(), emptyList())

        val poly = route.polyline
        val cum = NavEngine.cumulative(poly)
        val total = cum.lastOrNull() ?: 0.0
        val actualAlong = DoubleArray(n) { i ->
            if (poly.size < 2) 0.0 else NavEngine.projectAlong(poly, cum, maneuvers[i].location, 0.0, total).first
        }

        val announced = BooleanArray(n)
        val turnNow = BooleanArray(n)
        val worstErr = DoubleArray(n)
        val nearest = DoubleArray(n) { Double.MAX_VALUE }
        val firstAhead = arrayOfNulls<Double>(n)
        val cards = ArrayList<CardSnapshot>(fixes.size)

        var state = NavState()
        for ((fi, loc) in fixes.withIndex()) {
            val idx = state.stepIndex.coerceIn(0, n - 1) // target BEFORE this update — who the prompt belongs to
            val (next, events) = NavEngine.update(route, state, loc, imperial)
            val fixAlong = if (poly.size < 2) 0.0 else NavEngine.projectAlong(poly, cum, loc, 0.0, total).first

            for (i in 0 until n) {
                val gap = abs(actualAlong[i] - fixAlong)
                if (gap < nearest[i]) nearest[i] = gap
            }

            val tIdx = next.stepIndex.coerceIn(0, n - 1)
            val trueAhead = (actualAlong[tIdx] - fixAlong).coerceAtLeast(0.0)
            val cardErr = abs(next.distanceToNextManeuver - trueAhead)
            if (cardErr > worstErr[tIdx]) worstErr[tIdx] = cardErr

            val spoke = events.filterIsInstance<NavEvent.Speak>()
            for (ev in spoke) {
                if (ev.interrupt) {
                    turnNow[idx] = true // "turn now" / "you have arrived" callout belongs to the target being left
                } else {
                    announced[idx] = true
                    if (firstAhead[idx] == null) firstAhead[idx] = (actualAlong[idx] - fixAlong).coerceAtLeast(0.0)
                }
            }

            cards += CardSnapshot(
                fixIndex = fi,
                alongM = fixAlong,
                stepIndex = next.stepIndex,
                instruction = maneuvers.getOrNull(next.stepIndex)?.instruction.orEmpty(),
                distanceToNextM = next.distanceToNextManeuver,
                trueDistanceToNextM = trueAhead,
                spoke = spoke.map { it.text },
            )
            state = next
            if (next.arrived) break // fixes after arrival add nothing to the audit
        }

        val diffs = (0 until n).map { i ->
            ManeuverDiff(
                index = i,
                type = maneuvers[i].type,
                instruction = maneuvers[i].instruction,
                actualAlongM = actualAlong[i],
                announced = announced[i],
                turnNowFired = turnNow[i],
                firstAnnounceAheadM = firstAhead[i],
                worstCardErrorM = worstErr[i],
                nearestApproachM = nearest[i].takeIf { it != Double.MAX_VALUE } ?: 0.0,
            )
        }
        return Report(cards, diffs)
    }
}

/** Compact metres formatter for the report (km over 1000 m). */
private fun m(v: Double): String = if (v < 1000) "${v.roundToInt()} m" else "${(v / 100).roundToInt() / 10.0} km"
