package app.vela.core.model

import kotlinx.serialization.Serializable

/**
 * A route the user saved to drive again (issue #622): the way they go, which no router would
 * pick. Kept as its line (encoded polyline, 1e-5 degrees) plus its ends; when directions are asked
 * for the same trip, the line is turned back into a live route through a few via points where it
 * leaves the router's own answer ([app.vela.core.nav.SavedRoutes]).
 */
@Serializable
data class SavedRoute(
    val id: String,
    val name: String,
    val mode: String,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val destLabel: String,
    val polyline: String,
    val createdAt: Long,
    /** A RUN (the milkman's round): the places it stops at, in order. Empty for a SHAPE, a route
     *  whose line is the point and whose stops, if it had any, only bent it. */
    val stops: List<SavedStop> = emptyList(),
    /** Shown on the search page; every saved route is in the Saved sheet either way. */
    val pinned: Boolean = false,
) {
    val isRun: Boolean get() = stops.isNotEmpty()
    val origin: LatLng get() = LatLng(originLat, originLng)
    val dest: LatLng get() = LatLng(destLat, destLng)
}

@Serializable
data class SavedStop(val name: String, val lat: Double, val lng: Double) {
    val location: LatLng get() = LatLng(lat, lng)
}
