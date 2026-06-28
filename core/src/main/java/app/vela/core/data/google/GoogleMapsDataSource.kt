package app.vela.core.data.google

import app.vela.core.VelaConfig
import app.vela.core.config.CalibrationStore
import app.vela.core.config.JsTransforms
import app.vela.core.diag.DiagLog
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.PhotosParser
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.math.log2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val calibration: CalibrationStore,
    private val jsTransforms: JsTransforms,
    private val diag: DiagLog,
) : MapDataSource {

    override suspend fun search(query: String, near: LatLng?): SearchResult = io {
        session.ensure()
        // Results are viewport-driven, so a location is required; callers
        // normally pass the user's location, with a fallback for the rare null.
        val viewport = near ?: DEFAULT_VIEWPORT
        val cal = calibration.current()
        val pb = SearchPb.build(query, viewport, cal.searchPb)
        val url = "${cal.searchEndpoint}&q=${query.enc()}&pb=${pb.enc()}"
        val raw = get(url)
        // A remote transforms.js can fully re-parse a reshaped response (searchOverride);
        // otherwise the compiled parser runs. Either way, an optional transformPlaces
        // hook gets the last word. No hook / any error → pure compiled path.
        val places = try {
            jsTransforms.searchOverride(raw)
                ?: SearchParser.parse(query, GoogleResponse.parse(raw), near, cal.paths).places
        } catch (e: CalibrationNeededException) {
            // Capture the exact request that drifted so an opted-in user can hand it
            // to a dev (no-op unless diagnostics are on).
            diag.record("drift", "search parse drift: ${e.message}", url)
            throw e
        }
        // detail = the exact request URL so an opted-in user's export is replayable.
        diag.record("search", "\"$query\" near ${near?.lat ?: "?"},${near?.lng ?: "?"} → ${places.size} results", url)
        SearchResult(query, jsTransforms.refineSearch(places))
    }

    override suspend fun nearbyPlaces(center: LatLng, spanMeters: Double): List<Place> = io {
        session.ensure()
        val cal = calibration.current()
        // The wide default search (!1d≈25229, !4f13.1) returns the ~20 most prominent places over a
        // big area, so a strip mall shows almost none. Tighten the viewport (and match the !4f zoom)
        // + ask for more (!7i40). Calibrated live: span 25229↔zoom 13.1; span ~3.5–4 km returns
        // ~25 places within 700 m vs 1 at the default.
        val zoom = (13.1 + log2(25229.0 / spanMeters)).coerceIn(13.0, 17.5)
        // FAN OUT across category terms + merge: one "places" query is biased to prominent food/
        // shops, so it misses whole tiers (a strip mall's plumber, nail salon, IT shop). A handful
        // of category queries roughly DOUBLES local coverage (live: 22→52 unique within 600 m).
        val terms = listOf("places", "restaurants", "coffee", "stores", "shopping", "services", "beauty salon", "fast food")
        val all = coroutineScope {
            terms.map { term ->
                async {
                    runCatching {
                        val pb = SearchPb.build(term, center, cal.searchPb)
                            .replaceFirst(Regex("!1d[0-9.]+"), "!1d${spanMeters.toInt()}")
                            .replaceFirst(Regex("!4f[0-9.]+"), "!4f${String.format(java.util.Locale.US, "%.1f", zoom)}")
                            .replaceFirst(Regex("!7i\\d+"), "!7i40")
                        val url = "${cal.searchEndpoint}&q=${term.enc()}&pb=${pb.enc()}"
                        SearchParser.parse(term, GoogleResponse.parse(get(url)), center, cal.paths).places
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        // Dedup by feature id (same place returned under several terms); fall back to name+coords.
        all.distinctBy {
            it.featureId ?: "${it.name}@${(it.location.lat * 1e4).toInt()},${(it.location.lng * 1e4).toInt()}"
        }
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
        val cal = calibration.current()
        val pb = cal.reviewsPb.replace("{HIGH}", high.toString()).replace("{LOW}", low.toString())
        val url = "${cal.reviewsEndpoint}&pb=${pb.enc()}"
        runCatching { ReviewsParser.parse(GoogleResponse.parse(get(url))) }.getOrDefault(emptyList())
    }

    override suspend fun placePhotos(featureId: String): List<String> = io {
        // batchexecute `hspqX` (/MapsPhotoService.ListEntityPhotos) — a keyless POST
        // (no `at` token, just the warmed session cookies). The feature id goes in
        // the proto verbatim ([2][0]); the response carries the full gallery, URL at
        // each entry's [6][0]. (Calibrated live 2026-06-17.) Best-effort: any failure
        // returns empty so the caller keeps the search-preview photos.
        if (!featureId.contains(":")) return@io emptyList()
        session.ensure()
        val cal = calibration.current()
        val inner = cal.photosProto.replace("{FID}", featureId).replace("{COUNT}", PHOTO_COUNT.toString())
        // JsonPrimitive(...).toString() = the proto as a properly-escaped JSON string literal.
        val freq = "[[[\"hspqX\",${JsonPrimitive(inner)},null,\"generic\"]]]"
        runCatching { PhotosParser.parse(post(cal.photosEndpoint, "f.req=${freq.enc()}")).map { it.url } }.getOrDefault(emptyList())
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
    ): List<Route> = io {
        coroutineScope {
            // PRIMARY: the open router (OSRM) — complete, street-named turn-by-turn + real geometry.
            // Google's keyless directions endpoint hands back ABBREVIATED steps for longer routes
            // (a 6-mi route came back with 2 of ~10 turns), so Google is only the FALLBACK + the
            // live-traffic source. Fetch both in parallel so the traffic round-trip is free.
            val openD = async { RouteGeometry.route(http, origin, destination, mode) }
            val googleD = async { runCatching { googleDirections(origin, destination, mode) }.getOrNull().orEmpty() }
            val open = openD.await()
            val google = googleD.await()
            diag.record(
                "directions",
                "$mode → OSRM ${open.size} routes / ${open.firstOrNull()?.maneuvers?.size ?: 0} steps; " +
                    "google ${google.size} (traffic=${google.firstOrNull()?.durationInTrafficSeconds != null})",
                "",
            )
            if (open.isNotEmpty()) open.map { applyTraffic(it, google.firstOrNull()) }
            else google // OSRM unreachable → Google's abbreviated route beats nothing
        }
    }

    /** Overlay Google's live-traffic ETA + congestion onto an open-router [route] (best-effort):
     *  scale the route's free-flow duration by Google's in-traffic/typical ratio, and map its
     *  congestion spans onto the open geometry by fraction. No Google traffic → keep free-flow. */
    private fun applyTraffic(route: Route, g: Route?): Route {
        val typical = g?.durationSeconds?.takeIf { it > 0 } ?: return route
        val inTraffic = g.durationInTrafficSeconds ?: return route
        val factor = (inTraffic / typical).coerceIn(0.5, 4.0)
        val scale = if (g.distanceMeters > 0) route.distanceMeters / g.distanceMeters else 1.0
        return route.copy(
            durationInTrafficSeconds = route.durationSeconds * factor,
            trafficSpans = g.trafficSpans.map { it.copy(startMeters = it.startMeters * scale, lengthMeters = it.lengthMeters * scale) },
        )
    }

    /** Google's keyless directions — now the FALLBACK router (OSRM unreachable) and the
     *  live-traffic source (ETA / duration-in-traffic / congestion spans). Its step list is
     *  abbreviated for long routes, which is exactly why OSRM is primary. */
    private suspend fun googleDirections(origin: LatLng, destination: LatLng, mode: TravelMode): List<Route> {
        session.ensure()
        val cal = calibration.current()
        val pb = DirectionsPb.build(origin, destination, mode, cal.directionsPb)
        val url = "${cal.directionsEndpoint}&pb=${pb.enc()}"
        val routes = try {
            DirectionsParser.parse(GoogleResponse.parse(get(url)))
        } catch (e: CalibrationNeededException) {
            diag.record("drift", "directions parse drift: ${e.message}", url)
            throw e
        }
        return if (routes.all { it.polyline.size > 2 }) routes
        else {
            val geoms = RouteGeometry.fetchAll(http, origin, destination, mode)
            routes.mapIndexed { i, r ->
                if (r.polyline.size > 2) r
                else RouteGeometry.reposition(r, geoms.getOrNull(i) ?: listOf(origin, destination))
            }
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

    private fun post(url: String, body: String): String {
        val media = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(media))
            .header("User-Agent", VelaConfig.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.google.com/maps/")
            .header("X-Same-Domain", "1") // batchexecute expects this from a same-origin caller
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
        const val PHOTO_COUNT = 50 // gallery page size for the hspqX request
    }
}
