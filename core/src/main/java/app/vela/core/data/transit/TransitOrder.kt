package app.vela.core.data.transit

import app.vela.core.model.TransitItinerary

/** Orders planner itineraries by the chooser's route preference (Google's numbering: 2 fewer
 *  transfers, 3 less walking; anything else keeps the planner's order). Stable, so ties keep it. */
object TransitOrder {
    fun byPreference(trips: List<TransitItinerary>, pref: Int): List<TransitItinerary> = when (pref) {
        2 -> trips.sortedBy { t -> t.steps.count { it.line != null } }
        3 -> trips.sortedBy { t -> t.steps.filter { it.line == null }.sumOf { minutesOf(it.durationText) } }
        else -> trips
    }

    /** "1 hr 5 min" / "7 min" / "2 h" -> minutes; 0 when unreadable. */
    internal fun minutesOf(text: String?): Int {
        if (text == null) return 0
        val h = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)\s*min""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }
}
