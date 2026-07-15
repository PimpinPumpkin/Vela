package app.vela.core.data

import app.vela.core.model.LatLng
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient

/**
 * Fetches TRAFFIC-SIGNAL locations (OSM `highway=traffic_signals` nodes) near a route from **Overpass**
 * (OpenStreetMap's keyless query API), for Google-style landmark guidance ("pass the light, then turn left").
 * Best-effort: any failure → empty list (guidance simply omits the landmark clause). Sibling of [OverpassPois].
 *
 * Coverage is OSM's - dense in US/EU urban+suburban areas, thin in rural/developing regions; where a signal
 * isn't mapped, no clause is added (it's never wrong, just absent). Queried ONCE per driven route, not per fix.
 */
/** A drawn traffic control at [loc]: a traffic light (`stop == false`) or a stop sign (`stop == true`). */
data class TrafficControl(val loc: LatLng, val stop: Boolean)

@Serializable
private data class SignalsResp(val elements: List<SignalNode> = emptyList())

@Serializable
private data class SignalNode(
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String>? = null,
)

object OverpassTrafficSignals {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Traffic-signal AND stop-sign nodes inside a bounding box, for DRAWING on the map (a sibling of the
     * nav-landmark [fetchAlong]). `highway=traffic_signals` → a light, `highway=stop` → a stop sign; the
     * node's `highway` tag disambiguates (so `out` must carry tags - the default body verbosity does).
     * Returns **null on FAILURE** (network/timeout/non-2xx) and an (possibly empty) list on a SUCCESSFUL
     * parse - the distinction matters: the caller area-caches the result, and caching a failure as an
     * authoritative "no controls here" would blank the layer until the user pans out of the cached box.
     * Queried per padded viewport by the caller (which area-caches it, since controls are static), NOT per fix.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun fetchControlsInBox(
        http: OkHttpClient,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 6000,
    ): List<TrafficControl>? {
        val box = "($south,$west,$north,$east)"
        val query = "[out:json][timeout:25];" +
            "(node[\"highway\"=\"traffic_signals\"]$box;node[\"highway\"=\"stop\"]$box;);out $limit;"
        // Failover across mirrors (see OverpassEndpoints): a 504 from the primary no longer blanks the layer.
        // run() returns null only when EVERY endpoint fails - a real failure, not a genuine empty area.
        return OverpassEndpoints.run(http, query) { body ->
            // STREAM into a tiny DTO, same as OverpassAlprCameras: an `out 6000` dense-metro response
            // held as a String + full JsonElement DOM cost ~5-10x the wire size in transient heap -
            // the same churn that OOM'd the flock fetch (this was its named follow-up).
            json.decodeFromStream<SignalsResp>(body.byteStream()).elements.mapNotNull { n ->
                val lat = n.lat ?: return@mapNotNull null
                val lng = n.lon ?: return@mapNotNull null
                TrafficControl(LatLng(lat, lng), stop = n.tags?.get("highway") == "stop")
            }
        }
    }

    /** Traffic-signal node coordinates within the route's bounding box (padded a little). */
    @OptIn(ExperimentalSerializationApi::class)
    fun fetchAlong(http: OkHttpClient, polyline: List<LatLng>, limit: Int = 4000): List<LatLng> {
        if (polyline.size < 2) return emptyList()
        val pad = 0.003 // ~300 m, so a signal just off the sampled line still lands in the box
        val s = polyline.minOf { it.lat } - pad
        val n = polyline.maxOf { it.lat } + pad
        val w = polyline.minOf { it.lng } - pad
        val e = polyline.maxOf { it.lng } + pad
        val query = "[out:json][timeout:25];node[\"highway\"=\"traffic_signals\"]($s,$w,$n,$e);out $limit;"
        return OverpassEndpoints.run(http, query) { body ->
            json.decodeFromStream<SignalsResp>(body.byteStream()).elements.mapNotNull { node ->
                val lat = node.lat ?: return@mapNotNull null
                val lng = node.lon ?: return@mapNotNull null
                LatLng(lat, lng)
            }
        } ?: emptyList()
    }
}
