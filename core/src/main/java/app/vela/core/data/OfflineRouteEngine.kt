package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode

/**
 * The one offline [RouteEngine] the app talks to: obf first, GraphHopper second.
 *
 * Obf is the target format (issue #214 - ~4x smaller per region, dynamic avoids, three travel
 * modes); GraphHopper stays as the fallback so every graph installed before the cutover keeps
 * routing until its owner re-downloads the region as an obf. Once the fleet has moved, the GH
 * engine and its loader go.
 */
class OfflineRouteEngine(
    private val obf: ObfRouteEngine,
    private val graphHopper: GraphHopperRouteEngine,
) : RouteEngine {

    override fun isReady(mode: TravelMode): Boolean =
        obf.isReady(mode) || graphHopper.isReady(mode)

    override fun route(origin: LatLng, destination: LatLng, mode: TravelMode, avoidTolls: Boolean, avoidHighways: Boolean): List<Route> =
        obf.route(origin, destination, mode, avoidTolls, avoidHighways).ifEmpty {
            graphHopper.route(origin, destination, mode, avoidTolls, avoidHighways)
        }

    /** Only the GraphHopper side answers the speed-limit badge for now; the obf equivalent
     *  (nearest-road maxspeed off the routing section) is a follow-up. */
    override fun currentRoadLimit(lat: Double, lng: Double): Double? =
        graphHopper.currentRoadLimit(lat, lng)

    fun shutdown() {
        obf.shutdown()
        graphHopper.shutdown()
    }
}
