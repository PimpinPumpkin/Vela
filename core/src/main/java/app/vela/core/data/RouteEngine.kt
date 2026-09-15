package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode

/**
 * A source of routes. Two implementations:
 *  - **online** ([RouteGeometry] / OSRM, plus Google's traffic overlay) — the default when connected;
 *  - **on-device** ([ObfRouteEngine]) - used offline, routing from the obf region files downloaded
 *    per region.
 *
 * The seam lets `GoogleMapsDataSource.directions()` pick by connectivity + region availability
 * without knowing which engine answered.
 */
interface RouteEngine {
    /** True if this engine can route [mode] *right now* (e.g. a region file is present on disk).
     *  Cheap: must not trigger an expensive load. */
    fun isReady(mode: TravelMode): Boolean

    /** Best-first routes for origin to destination, or empty if unavailable/failed. Never throws.
     *  The avoid flags are dynamic routing parameters on the obf engine (avoid_toll /
     *  avoid_highway); an engine that cannot honor them returns empty for an avoid request so
     *  the caller can fall through, never a silent route-through-the-toll. */
    fun route(origin: LatLng, destination: LatLng, mode: TravelMode, avoidTolls: Boolean = false, avoidHighways: Boolean = false): List<Route>

    /** The posted speed limit (km/h) of the road nearest ([lat],[lng]), or null if unknown. Only the
     *  on-device engine can answer (from the OSM `maxspeed` in the graph); online engines have no offline
     *  limit data, so the default is null. Call off the main thread. Convert to mph at the UI boundary. */
    fun currentRoadLimit(lat: Double, lng: Double): Double? = null
}
