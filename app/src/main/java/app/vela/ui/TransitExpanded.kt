package app.vela.ui

import androidx.compose.runtime.mutableStateMapOf
import app.vela.core.model.TransitItinerary

/**
 * Which transit itineraries in the directions panel are expanded (issue #812). A row's own
 * `remember` was lost when the panel dropped to its card, so an expanded route came back
 * collapsed while its line was still on the map. Keyed by what identifies an itinerary across
 * recompositions; [prune] keeps only the current results' keys.
 */
object TransitExpanded {
    val open = mutableStateMapOf<String, Boolean>()

    // The boarding stops are in it: two bike-share trips on one system at the same times differ
    // only by their stations, and shared one key (both rows opened together).
    fun keyOf(t: TransitItinerary): String =
        "${t.departureEpochSec}|${t.arrivalEpochSec}|${t.steps.size}|${t.durationText}|${t.lines.joinToString(",") { it.name }}|" +
            t.steps.joinToString(",") { it.boardStop?.name.orEmpty() }

    fun prune(keep: Collection<String>) {
        val gone = open.keys.filter { it !in keep }
        gone.forEach { open.remove(it) }
    }
}
