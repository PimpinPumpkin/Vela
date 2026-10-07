package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode

/**
 * A source of routes. Implementations:
 *  - **online** ([RouteGeometry] / OSRM, plus Google's traffic overlay) - the default when connected;
 *  - **on-device** ([ObfRouteEngine]) - used offline, routing from the obf region files downloaded
 *    per region; [ObfBmsspRouteEngine] is a second on-device engine over the same files (see
 *    FORK.md), gated by the `sortingBarrierRouter` calibration flag behind [SortingBarrierEngine].
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
     *  avoid_highway / avoid_ferries); an engine that cannot honor them returns empty for an avoid request so
     *  the caller can fall through, never a silent route-through-the-toll. */
    fun route(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
        avoidFerries: Boolean = false,
        // The heading the car is actually traveling, for a mid-drive reroute (same rule as the
        // open router's `bearings=`): the route should start the way the car is pointing instead
        // of answering "turn around". Null for planning.
        departBearingDeg: Double? = null,
        // How long the caller will wait. Past it the search is stopped and the answer is empty:
        // a caller that gives up at 6 s used to leave the search running for as long as it took,
        // on a core the map needed, holding the engine's lock against the next request.
        maxMs: Long? = null,
    ): List<Route>

    /** True when the engine's installed data covers BOTH ends of a trip, so a route from it is a
     *  real answer rather than an empty one. Cheap (a box test over the region index, no file
     *  opened); the phone-first reroute asks it before spending a native compute. Online engines
     *  answer false. */
    fun covers(origin: LatLng, destination: LatLng, mode: TravelMode): Boolean = false

    /** The posted speed limit (km/h) of the road nearest ([lat],[lng]), or null if unknown. Only the
     *  on-device engine can answer (from the OSM `maxspeed` in the graph); online engines have no offline
     *  limit data, so the default is null. Call off the main thread. Convert to mph at the UI boundary. */
    fun currentRoadLimit(lat: Double, lng: Double): Double? = null

    /** Drop cached region readers and derived state. Call when the obf files under the engine
     *  change (region install/delete, storage move, cell change) so the next route re-opens them.
     *  Engines that cache nothing implement nothing. */
    fun shutdown() {}
}
