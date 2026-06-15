package app.carto.core.data

import app.carto.core.model.LatLng
import app.carto.core.model.Place
import app.carto.core.model.Route
import app.carto.core.model.SearchResult
import app.carto.core.model.TravelMode

/**
 * The single seam every screen talks to. Two implementations exist:
 *  - [MockMapDataSource] — canned data, the default, lets the UI run with no
 *    network and no calibration.
 *  - [app.carto.core.data.google.GoogleMapsDataSource] — the real scraper.
 *
 * Which one is live is chosen in [app.carto.core.di.CoreModule] off
 * [app.carto.core.CartoConfig.USE_GOOGLE_SOURCE]. Keeping this interface thin
 * also means a future Overture/OSM source, or a self-hosted backend (the
 * Piped-for-Carto idea), is a drop-in.
 */
interface MapDataSource {
    suspend fun search(query: String, near: LatLng? = null): SearchResult

    suspend fun placeDetails(id: String): Place

    suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode = TravelMode.DRIVE,
    ): List<Route>
}

/**
 * Thrown when a response no longer matches the calibrated shape — i.e. Google
 * reshuffled their positional arrays. The UI catches this and shows a "needs
 * update" state rather than crashing. This is the *expected* periodic failure
 * mode of the whole NewPipe-style approach; treat it as routine.
 */
class CalibrationNeededException(where: String, cause: Throwable? = null) :
    Exception("Google response shape changed or not yet calibrated at: $where", cause)
