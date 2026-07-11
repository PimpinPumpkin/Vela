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
    /** [spanMeters]: the caller's visible viewport height — widens Google's result window to
     *  match how far out the map is zoomed (the pb template's baked span is ~25 km). */
    suspend fun search(query: String, near: LatLng? = null, spanMeters: Double? = null): SearchResult

    /** Prominent places in the viewport, for the ambient map-POI overlay. [spanMeters] is the
     *  viewport's height — a SMALLER span (zoomed in) returns DENSER, more local results than the
     *  wide default search, so a strip mall fills with its own businesses. Default falls back to a
     *  normal "places" search. */
    /** [onPartial] (optional) streams the accumulated, ranked pool as category terms land, so
     *  the map paints its first dots ~1 s in instead of waiting for the slowest of the fan-out
     *  (the perceived "POIs take forever" - most of the wait was the tail). The final return is
     *  always the complete set. */
    suspend fun nearbyPlaces(center: LatLng, spanMeters: Double, onPartial: ((List<Place>) -> Unit)? = null): List<Place> =
        search("places", center).places

    suspend fun placeDetails(id: String): Place

    /** Reverse-geocode a tapped point to an address (drop-a-pin / tap-a-building).
     *  Best-effort — returns null if nothing is found. */
    suspend fun reverseGeocode(location: LatLng): Place? = null

    /** Full user reviews for a place, by Google feature id ("0x..:0x..").
     *  Best-effort — returns empty if unavailable. */
    suspend fun reviews(featureId: String): List<Review> = emptyList()

    /** Imports a Google Maps SHARED LIST from its share link (maps.app.goo.gl/…):
     *  title, description and every place with the owner's note (issue #1).
     *  Best-effort — null when the link isn't a list or the fetch/parse fails. */
    suspend fun importList(shareUrl: String): app.vela.core.model.ImportedList? = null

    /** The full place photo gallery (~40+), by Google feature id, each with its
     *  posted date when the response carried one. The search response only holds a
     *  ~10-photo preview; this pulls the rest via the keyless `hspqX` RPC. The app's
     *  primary gallery source is the WebView page walk (which has category tags but
     *  NO dates), so this doubles as the DATE side of that join. Best-effort —
     *  empty (→ keep the preview) on failure. */
    suspend fun placePhotos(featureId: String): List<app.vela.core.model.Photo> = emptyList()

    suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode = TravelMode.DRIVE,
        // Intermediate stops the route must pass through, in order (multi-stop trips). Empty = direct
        // origin→destination. A waypointed route is a single path through the stops (no alternates).
        waypoints: List<LatLng> = emptyList(),
        // Route preference toggles (drive only). Honoured by the OSRM paths via `exclude=`;
        // Google-fallback routes and offline graphs baked before the avoid profiles cannot
        // honour them (the fallback still routes rather than failing).
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
    ): List<Route>

    /** Name a PROVISIONAL alternate ([Route.provisional]) — the user picked it to drive, so turn its
     *  polyline into real named turn-by-turn (map-matched on-device where the region's downloaded, else
     *  snapped through OSRM). Returns the named route, or the input unchanged if it's already named /
     *  can't be named. Default: no-op (Mock). */
    suspend fun nameRoute(
        route: Route,
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode = TravelMode.DRIVE,
        avoidTolls: Boolean = false,
        avoidHighways: Boolean = false,
    ): Route = route
}

/**
 * Thrown when a response no longer matches the calibrated shape — i.e. Google
 * reshuffled their positional arrays. The UI catches this and shows a "needs
 * update" state rather than crashing. This is the *expected* periodic failure
 * mode of the whole NewPipe-style approach; treat it as routine.
 */
class CalibrationNeededException(where: String, cause: Throwable? = null) :
    Exception("Google response shape changed or not yet calibrated at: $where", cause)
