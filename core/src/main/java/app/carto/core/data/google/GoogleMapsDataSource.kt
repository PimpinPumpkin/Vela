package app.carto.core.data.google

import app.carto.core.CartoConfig
import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.MapDataSource
import app.carto.core.data.google.parse.DirectionsParser
import app.carto.core.data.google.parse.SearchParser
import app.carto.core.model.LatLng
import app.carto.core.model.Place
import app.carto.core.model.Route
import app.carto.core.model.SearchResult
import app.carto.core.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real extractor: drives Google's public web endpoints directly from the
 * device. Request construction uses the correct `pb` grammar ([PbBuilder]); the
 * specific field trees and the response array indices are marked `CALIBRATE:`
 * and must be pinned from a live capture before this source is switched on (see
 * [CartoConfig.USE_GOOGLE_SOURCE]).
 *
 * Why these endpoints: they are the same ones google.com/maps calls from a
 * browser, so they can't demand Play Integrity (great for GrapheneOS) and they
 * return traffic-aware ETAs with the traffic already baked in.
 */
@Singleton
class GoogleMapsDataSource @Inject constructor(
    private val http: OkHttpClient,
    private val session: GoogleSession,
) : MapDataSource {

    override suspend fun search(query: String, near: LatLng?): SearchResult = io {
        session.ensure()
        // The search term rides in the URL `q`; `pb` carries the viewport bias.
        // CALIBRATE: confirm the viewport message field numbers (4m2/1d/2d here
        // are the documented shape, not verified-current).
        val pb = PbBuilder().apply {
            if (near != null) {
                message(4, 2) {
                    double(1, near.lng)
                    double(2, near.lat)
                }
            }
        }.toString()
        val url = buildString {
            append("https://www.google.com/search?tbm=map&hl=en&gl=us&authuser=0")
            append("&q=").append(query.enc())
            if (pb.isNotEmpty()) append("&pb=").append(pb.enc())
        }
        SearchParser.parse(query, GoogleResponse.parse(get(url)))
    }

    override suspend fun placeDetails(id: String): Place = io {
        session.ensure()
        // CALIBRATE: the place-detail RPC (reviews, hours, popular times) lives
        // under /maps/preview/place. Not yet pinned.
        throw CalibrationNeededException("placeDetails RPC")
    }

    override suspend fun directions(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
    ): List<Route> = io {
        session.ensure()
        val travel = when (mode) {
            TravelMode.DRIVE -> 0
            TravelMode.BICYCLE -> 1
            TravelMode.WALK -> 2
            TravelMode.TRANSIT -> 3
        }
        // CALIBRATE: origin/destination encoding + the traffic-aware flag. The
        // tree below is illustrative of the grammar, not a verified payload.
        val pb = PbBuilder().apply {
            message(1, 2) { double(1, origin.lat); double(2, origin.lng) }
            message(2, 2) { double(1, destination.lat); double(2, destination.lng) }
            enum(3, travel)
        }.toString()
        val url = "https://www.google.com/maps/preview/directions" +
            "?hl=en&gl=us&authuser=0&pb=${pb.enc()}"
        DirectionsParser.parse(GoogleResponse.parse(get(url)))
    }

    // --- plumbing -----------------------------------------------------------

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CartoConfig.USER_AGENT)
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
}
