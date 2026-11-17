package app.vela.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.TransitItinerary

/**
 * Which transit itineraries in the directions panel are expanded (issue #812). A row's own
 * `remember` was lost when the panel dropped to its card, so an expanded route came back
 * collapsed while its line was still on the map. Keyed by what identifies an itinerary across
 * recompositions; [prune] keeps only the current results' keys.
 */
object TransitExpanded {
    val open = mutableStateMapOf<String, Boolean>()

    /** Whether the transit navigation sheet is slid down to its compact bottom bar (full-screen map). */
    val navCollapsed = mutableStateOf(false)

    /** Bumped when the user pans the map during transit navigation to smoothly collapse the sheet. */
    val navPanTick = mutableIntStateOf(0)

    /** Optional leg index tapped by the user in the transit sheet; null frames the entire trip. */
    val navFocusedLeg = mutableStateOf<Int?>(null)

    fun keyOf(t: TransitItinerary): String =
        "${t.departureEpochSec}|${t.arrivalEpochSec}|${t.steps.size}|${t.durationText}|${t.lines.joinToString(",") { it.name }}"

    fun prune(keep: Collection<String>) {
        val gone = open.keys.filter { it !in keep }
        gone.forEach { open.remove(it) }
    }
}
