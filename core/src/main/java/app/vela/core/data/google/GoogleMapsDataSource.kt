package app.vela.core.data.google

import app.vela.core.VelaConfig
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.ReviewsParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.SearchResult
import app.vela.core.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

    override suspend fun reverseGeocode(location: LatLng): Place? = io {
        // OpenStreetMap's Nominatim — keyless, on-ethos (open data), and a stable
        // documented API, so unlike the Google endpoints it needs no recalibration.
        // Best-effort: any failure (network, rate-limit, no match) → null.
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18" +
                "&lat=${location.lat}&lon=${location.lng}"
            val root = Json.parseToJsonElement(getNominatim(url)).jsonObject
            val addr = root["address"]?.jsonObject ?: return@runCatching null
            fun str(k: String): String? = (addr[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            val street = listOfNotNull(str("house_number"), str("road")).joinToString(" ").ifBlank { null }
            val city = str("city") ?: str("town") ?: str("village") ?: str("hamlet") ?: str("suburb")
            val regionPost = listOfNotNull(str("state"), str("postcode")).joinToString(" ").ifBlank { null }
            val addressLine = listOfNotNull(street, city, regionPost).joinToString(", ")
                .ifBlank { (root["display_name"] as? JsonPrimitive)?.content }
            Place(
                id = "pin:${location.lat},${location.lng}",
                name = street ?: city ?: "Dropped pin",
                location = location,
                address = addressLine,
            )
        }.getOrNull()
    }

    override suspend fun reviews(featureId: String): List<Review> = io {
        // /maps/preview/review/listentitiesreviews — a keyless GET. The feature id
        // "0xHIGH:0xLOW" splits into two unsigned-64 decimals (1y/2y); 2i/3i page,
        // 3e1 sorts by most-relevant. The 1s session token can be any string.
        // (Calibrated live 2026-06-16.)
        val parts = featureId.split(":")
        if (parts.size != 2) return@io emptyList()
        val high = runCatching { java.math.BigInteger(parts[0].removePrefix("0x"), 16) }.getOrNull() ?: return@io emptyList()
        val low = runCatching { java.math.BigInteger(parts[1].removePrefix("0x"), 16) }.getOrNull() ?: return@io emptyList()
        val pb = "!1m2!1y$high!2y$low!2m2!2i0!3i20!3e1!5m2!1svela!7e81"
        val url = "https://www.google.com/maps/preview/review/listentitiesreviews" +
            "?authuser=0&hl=en&gl=us&pb=${pb.enc()}"
        runCatching { ReviewsParser.parse(GoogleResponse.parse(get(url))) }.getOrDefault(emptyList())
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
    ): List<Route> = io {
        session.ensure()
        val pb = DirectionsPb.build(origin, destination, mode)
        val url = "https://www.google.com/maps/preview/directions?authuser=0&hl=en&gl=us&pb=${pb.enc()}"
        val routes = DirectionsParser.parse(GoogleResponse.parse(get(url)))
        // Google's response carries no decodable line; draw it via an open router.
        // FOSSGIS OSRM has a per-mode backend, so drive/walk/bike each get a real
        // path-following line (transit has none → keeps the parser's approximation).
        val geometry = RouteGeometry.fetch(http, origin, destination, mode)
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

    private fun getNominatim(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/PimpinPumpkin/Vela)")
            .header("Accept-Language", "en")
            .build()
        http.newCall(req).execute().use { resp -> return resp.body?.string().orEmpty() }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        // Fallback viewport when no user location is available — search is
        // viewport-driven and needs one. Callers normally pass the real location.
        val DEFAULT_VIEWPORT = LatLng(37.7749, -122.4194)
    }
}
