package app.vela.core.data.transit

import app.vela.core.model.TransitItinerary
import app.vela.core.model.TransitMode

/** Orders planner itineraries by the chooser's route preference (Google's numbering: 2 fewer
 *  transfers, 3 less walking; anything else keeps the planner's order). Stable, so ties keep it. */
object TransitOrder {
    fun byPreference(trips: List<TransitItinerary>, pref: Int): List<TransitItinerary> = when (pref) {
        2 -> trips.sortedBy { t -> t.steps.count { it.line != null } }
        3 -> trips.sortedBy { t -> t.steps.filter { it.line == null }.sumOf { minutesOf(it.durationText) } }
        else -> trips
    }

    /** Google answered with walking only: no line in its feed serves the trip. The open planner
     *  may still have one (Niš's suburban lines are in its feed and not in Google's, #809). */
    fun walkOnly(trips: List<TransitItinerary>): Boolean = trips.isNotEmpty() && trips.all { !rides(it) }

    /** The open planner's itineraries that ride something, ordered by [pref], ahead of Google's
     *  [walk]; [walk] alone when the planner has none. */
    fun ridesBeforeWalk(walk: List<TransitItinerary>, planned: List<TransitItinerary>, pref: Int): List<TransitItinerary> {
        val rides = planned.filter(::rides)
        return if (rides.isEmpty()) walk else byPreference(rides, pref) + walk
    }

    private fun rides(t: TransitItinerary) = t.lines.isNotEmpty() || t.steps.any { it.mode != TransitMode.WALK }

    /** "1 hr 5 min" / "7 min" / "2 h" -> minutes; 0 when unreadable. */
    internal fun minutesOf(text: String?): Int {
        if (text == null) return 0
        val h = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)\s*min""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }
}
