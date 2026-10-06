package app.vela.ui.map

import app.vela.core.model.TravelMode

/** Stable keys: asynchronous place details and route updates do not replay the entrance. */
internal enum class BottomOverlay(val slide: Boolean = true) {
    NONE(false), ARRIVAL, NAV_STOPS, STEPS(false), NAV_STOP_OFFER, NAV_CONTROLS,
    TRIP_EDITOR, DIRECTIONS, CLASSIC_DIRECTIONS, PLACE, SHAPES(false), RESULTS;

    companion object {
        // The ETA bar and steps list share their own continuous height animation.
        fun animateChange(from: BottomOverlay, to: BottomOverlay): Boolean =
            !((from == STEPS && to == NAV_CONTROLS) || (from == NAV_CONTROLS && to == STEPS))

        fun of(state: MapUiState, searchOpen: Boolean, pickingResults: Boolean, googleStyle: Boolean): BottomOverlay = when {
            state.arrived && !state.replaying -> ARRIVAL
            state.navigating && state.editingStops && !searchOpen -> NAV_STOPS
            state.showSteps -> STEPS
            state.navigating && state.navTapCandidate != null -> NAV_STOP_OFFER
            state.navigating && state.results.isEmpty() -> NAV_CONTROLS
            state.editingStops && state.directionsOpen && !searchOpen && state.pickOnMap == null -> TRIP_EDITOR
            state.directionsOpen && !searchOpen && state.pickOnMap == null && googleStyle && state.travelMode != TravelMode.TRANSIT -> DIRECTIONS
            state.directionsOpen && !searchOpen && state.pickOnMap == null -> CLASSIC_DIRECTIONS
            state.selected != null && !searchOpen && state.pickOnMap == null && state.streetView == null && !state.streetViewLoading -> PLACE
            state.drawing != null || (state.pendingImport != null && state.results.isEmpty() && !searchOpen) -> SHAPES
            state.results.isNotEmpty() && (!searchOpen || pickingResults) && state.pickOnMap == null && state.streetView == null && !state.streetViewLoading -> RESULTS
            else -> NONE
        }
    }
}

internal data class BottomOverlayFrame(val state: MapUiState, val overlay: BottomOverlay)
