package app.vela.ui.map

import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.TransitItinerary

/**
 * The bike-share trips on the Bike tab (SPEC 8, Bike share in directions), filled by the view
 * model's `searchBikeShare` and read by the directions sheet and the map. A holder, not map
 * state: MapScreen is at its size limits and takes no new parameter. [preview] is the expanded
 * trip, which the map draws in place of a transit preview. The view model empties it all when
 * the Bike tab leaves the screen.
 */
object BikeShareTripsShown {
    /** Null until a search has answered; empty when it found none. */
    val trips = mutableStateOf<List<TransitItinerary>?>(null)
    val loading = mutableStateOf(false)
    /** Why nothing was asked for, as a string id, shown in place of the trips: the trip has stops,
     *  which bike-share trips do not take yet, or it does not leave now. Null otherwise. */
    val notice = mutableStateOf<Int?>(null)
    val preview = mutableStateOf<TransitItinerary?>(null)

    /** A row was expanded or collapsed. Collapsing clears the preview only if that row owns it. */
    fun onExpanded(t: TransitItinerary, expanded: Boolean) {
        if (expanded) preview.value = t else if (preview.value === t) preview.value = null
    }

    /** A compact row was tapped: it becomes the drawn trip, or stops being it. */
    fun select(t: TransitItinerary) {
        preview.value = if (preview.value === t) null else t
    }

    fun clear() {
        if (trips.value != null) trips.value = null
        if (loading.value) loading.value = false
        if (notice.value != null) notice.value = null
        if (preview.value != null) preview.value = null
        BikeShareCounts.replace(emptyMap())
    }
}
