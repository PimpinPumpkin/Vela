package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode

/**
 * A source of routes. Two implementations:
 *  - **online** ([RouteGeometry] / OSRM, plus Google's traffic overlay) — the default when connected;
 *  - **on-device** ([GraphHopperRouteEngine]) — used offline (or, later, to map-match Google's path),
 *    routing from a prebuilt graph downloaded per region.
 *
 * The seam lets `GoogleMapsDataSource.directions()` pick by connectivity + graph availability without
 * knowing which engine answered. See ROADMAP "On-device map-matching (GraphHopper)".
 */
interface RouteEngine {
    /** True if this engine can route [mode] *right now* (e.g. the region's graph is present on disk).
     *  Cheap — must not trigger an expensive load. */
    fun isReady(mode: TravelMode): Boolean

    /** Best-first routes for origin→destination, or empty if unavailable/failed. Never throws. */
    fun route(origin: LatLng, destination: LatLng, mode: TravelMode): List<Route>

    /** The posted speed limit (km/h) of the road nearest ([lat],[lng]), or null if unknown. Only the
     *  on-device engine can answer (from the OSM `maxspeed` in the graph); online engines have no offline
     *  limit data, so the default is null. Call off the main thread. Convert to mph at the UI boundary. */
    fun currentRoadLimit(lat: Double, lng: Double): Double? = null
}
