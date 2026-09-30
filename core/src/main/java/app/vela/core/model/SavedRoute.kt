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
) {
    val origin: LatLng get() = LatLng(originLat, originLng)
    val dest: LatLng get() = LatLng(destLat, destLng)
}
