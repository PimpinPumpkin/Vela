package app.vela.ui.map

import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.core.nav.CameraDetour

internal class CameraRoutePlan {
    var epoch = 0
        private set
    var detourRequestsLeft = CameraDetour.MAX_REQUESTS
        private set

    fun resetDetourBudget() {
        detourRequestsLeft = CameraDetour.MAX_REQUESTS
    }

    fun invalidate() {
        epoch++
    }

    fun takeDetourRequest(): Boolean {
        if (detourRequestsLeft == 0) return false
        detourRequestsLeft--
        return true
    }

    fun invalidateForResnap(original: Route, named: Route): Boolean {
        if (original.polyline == named.polyline) return false
        invalidate()
        return true
    }

    fun isCurrent(requestEpoch: Int, mode: TravelMode, state: MapUiState): Boolean =
        requestEpoch == epoch && state.directionsOpen && state.travelMode == mode &&
            !state.navigating && !state.replaying && state.routes.isNotEmpty()

    // Saved names and toll labels can copy the row while keeping its original line.
    fun indexOfRoute(match: Route, routes: List<Route>): Int =
        routes.indexOfFirst { it === match }.takeIf { it >= 0 }
            ?: routes.indexOfFirst { it.polyline === match.polyline }
}
