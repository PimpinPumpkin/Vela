package app.vela.core.model

import kotlinx.serialization.Serializable

/** One saved parking spot (current or historical). [savedAtMillis] is wall-clock. */
@Serializable
data class ParkedSpot(
    val lat: Double,
    val lng: Double,
    val savedAtMillis: Long,
) {
    val location: LatLng get() = LatLng(lat, lng)
}
