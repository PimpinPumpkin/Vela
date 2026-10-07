package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode

/**
 * Calibration-gated composite: the sorting-barrier engine ([ObfBmsspRouteEngine]) is tried
 * first only when the `sortingBarrierRouter` calibration flag is on, the mode is DRIVE, and
 * no avoid flags are set; anything it declines (empty result, exception, unsupported request)
 * falls through to the shipping [ObfRouteEngine]. Turning the experiment off is a config
 * flip, not a release.
 *
 * `isReady`/`covers` answer for either engine (the picker only needs SOME engine that can
 * serve the pair); the road-limit probe always belongs to the shipping engine.
 */
class SortingBarrierEngine(
    private val bmssp: RouteEngine,
    private val osmAnd: RouteEngine,
    private val enabled: () -> Boolean,
) : RouteEngine {

    override fun isReady(mode: TravelMode): Boolean = osmAnd.isReady(mode) || bmssp.isReady(mode)

    override fun covers(origin: LatLng, destination: LatLng, mode: TravelMode): Boolean =
        osmAnd.covers(origin, destination, mode) || bmssp.covers(origin, destination, mode)

    override fun route(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
        avoidFerries: Boolean,
        departBearingDeg: Double?,
        maxMs: Long?,
    ): List<Route> {
        if (mode == TravelMode.DRIVE && !avoidTolls && !avoidHighways && !avoidFerries &&
            runCatching { enabled() }.getOrDefault(false)
        ) {
            val candidate = runCatching {
                bmssp.route(origin, destination, mode, avoidTolls, avoidHighways, avoidFerries, departBearingDeg, maxMs)
            }.getOrDefault(emptyList())
            if (candidate.isNotEmpty()) return candidate
        }
        return osmAnd.route(origin, destination, mode, avoidTolls, avoidHighways, avoidFerries, departBearingDeg, maxMs)
    }

    override fun currentRoadLimit(lat: Double, lng: Double): Double? = osmAnd.currentRoadLimit(lat, lng)

    /** Closes the file handles both engines hold. */
    override fun shutdown() {
        bmssp.shutdown()
        osmAnd.shutdown()
    }
}
