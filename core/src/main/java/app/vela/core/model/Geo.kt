package app.vela.core.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A WGS84 coordinate. Vela's own type, deliberately free of any MapLibre
 * dependency so `:core` stays UI-agnostic — convert to/from
 * `org.maplibre.android.geometry.LatLng` only at the view boundary.
 */
data class LatLng(val lat: Double, val lng: Double)

/** south/west/north/east, in degrees. */
data class LatLngBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val center: LatLng get() = LatLng((south + north) / 2.0, (west + east) / 2.0)

    companion object {
        /** Rough square bounds of [radiusMeters] around [c]; good enough for a
         *  "search near here" viewport bias. */
        fun around(c: LatLng, radiusMeters: Double): LatLngBounds {
            val dLat = radiusMeters / 111_320.0
            val dLng = radiusMeters /
                (111_320.0 * cos(Math.toRadians(c.lat)).coerceAtLeast(1e-6))
            return LatLngBounds(c.lat - dLat, c.lng - dLng, c.lat + dLat, c.lng + dLng)
        }
    }
}

/** Great-circle distance in metres (haversine). Used for "near me" ranking and
 *  the nav engine's off-route / approaching-maneuver checks. */
fun LatLng.distanceTo(o: LatLng): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(o.lat - lat)
    val dLng = Math.toRadians(o.lng - lng)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(a)))
}

/** Initial bearing in degrees (0 = N, 90 = E) from this point toward [o]. */
fun LatLng.bearingTo(o: LatLng): Double {
    val dLng = Math.toRadians(o.lng - lng)
    val y = sin(dLng) * cos(Math.toRadians(o.lat))
    val x = cos(Math.toRadians(lat)) * sin(Math.toRadians(o.lat)) -
        sin(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
