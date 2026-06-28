package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.SearchResult
import app.vela.core.model.TravelMode

/**
 * The single seam every screen talks to. Two implementations exist:
 *  - [MockMapDataSource] — canned data, the default, lets the UI run with no
 *    network and no calibration.
 *  - [app.vela.core.data.google.GoogleMapsDataSource] — the real scraper.
 *
 * Which one is live is chosen in [app.vela.core.di.CoreModule] off
 * [app.vela.core.VelaConfig.USE_GOOGLE_SOURCE]. Keeping this interface thin
 * also means a future Overture/OSM source, or a self-hosted backend (the
 * Piped-for-Vela idea), is a drop-in.
 */
interface MapDataSource {
    suspend fun search(query: String, near: LatLng? = null): SearchResult

    /** Prominent places in the viewport, for the ambient map-POI overlay. [spanMeters] is the
     *  viewport's height — a SMALLER span (zoomed in) returns DENSER, more local results than the
     *  wide default search, so a strip mall fills with its own businesses. Default falls back to a
     *  normal "places" search. */
    suspend fun nearbyPlaces(center: LatLng, spanMeters: Double): List<Place> =
        search("places", center).places

    suspend fun placeDetails(id: String): Place

    /** Reverse-geocode a tapped point to an address (drop-a-pin / tap-a-building).
     *  Best-effort — returns null if nothing is found. */
    suspend fun reverseGeocode(location: LatLng): Place? = null

    /** Full user reviews for a place, by Google feature id ("0x..:0x..").
     *  Best-effort — returns empty if unavailable. */
    suspend fun reviews(featureId: String): List<Review> = emptyList()

    /** The full place photo gallery (~40+), by Google feature id. The search
     *  response only carries a ~10-photo preview; this pulls the rest via the
     *  keyless `hspqX` RPC. Best-effort — empty (→ keep the preview) on failure. */
    suspend fun placePhotos(featureId: String): List<String> = emptyList()

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
