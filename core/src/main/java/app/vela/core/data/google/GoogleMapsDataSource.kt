package app.vela.core.data.google

import app.vela.core.VelaConfig
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.model.SearchResult
import app.vela.core.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real extractor, calibrated against maps.google.com (2026-06-15).
 *
 * Search turned out to need NO pb at all — a plain `/search?tbm=map&q=…` returns
 * the full results JSON, with viewport bias achieved by appending "near lat,lng"
 * to the query. Directions needs a pb (built by [DirectionsPb]) but no session
 * token. Both are the same endpoints google.com/maps calls from a browser, so
 * they work without Play Services — good for GrapheneOS.
 */
@Singleton
class GoogleMapsDataSource @Inject constructor(
    private val http: OkHttpClient,
    private val session: GoogleSession,
) : MapDataSource {

    override suspend fun search(query: String, near: LatLng?): SearchResult = io {
        session.ensure()
        // Results are viewport-driven, so a location is required; callers
        // normally pass the user's location, with a fallback for the rare null.
        val viewport = near ?: DEFAULT_VIEWPORT
        val pb = SearchPb.build(query, viewport)
        val url = "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us" +
            "&q=${query.enc()}&pb=${pb.enc()}"
        SearchParser.parse(query, GoogleResponse.parse(get(url)), near)
    }

    override suspend fun placeDetails(id: String): Place = io {
        // CALIBRATE: the dedicated place-detail RPC (reviews, hours, popular
        // times) under /maps/preview/place is not yet mapped. Search already
        // returns name/rating/reviews/address/category, so the UI uses the
        // Place from the search result directly until this is calibrated.
        throw CalibrationNeededException("placeDetails RPC not yet mapped")
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
    ): List<Route> = io {
        session.ensure()
        // CALIBRATE: mode is currently driving (the captured template). Other
        // modes need their travel-mode field flipped in DirectionsPb.
        val pb = DirectionsPb.build(origin, destination)
        val url = "https://www.google.com/maps/preview/directions?authuser=0&hl=en&gl=us&pb=${pb.enc()}"
        val routes = DirectionsParser.parse(GoogleResponse.parse(get(url)))
        // Google's response carries no decodable line; draw it via an open
        // router. Best-effort: keep the approximate line if OSRM is unavailable.
        val geometry = RouteGeometry.fetch(http, origin, destination)
        if (geometry != null && routes.isNotEmpty()) {
            listOf(RouteGeometry.reposition(routes.first(), geometry)) + routes.drop(1)
        } else {
            routes
        }
    }

    // --- plumbing -----------------------------------------------------------

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VelaConfig.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.google.com/maps/")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw CalibrationNeededException("HTTP ${resp.code} from ${req.url.encodedPath}")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        // Fallback viewport when no user location is available — search is
        // viewport-driven and needs one. Callers normally pass the real location.
        val DEFAULT_VIEWPORT = LatLng(37.7749, -122.4194)
    }
}
