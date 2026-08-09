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
 * Coverage is OSM's — dense in US/EU urban+suburban areas, thin in rural/developing regions; where a signal
 * isn't mapped, no clause is added (it's never wrong, just absent). Queried ONCE per driven route, not per fix.
 */
/** A drawn road control/aid at [loc]. Started as lights + stop signs; the static OSM AIDS
 *  (railway level crossings, speed humps - 2026-08-08, the buildable subset of "aids on the road"
 *  after every live-incident source proved dead, see ROADMAP) ride the same pipeline. */
data class TrafficControl(val loc: LatLng, val kind: Kind) {
    enum class Kind { SIGNAL, STOP, RAIL_CROSSING, SPEED_HUMP }
}

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
     * node's `highway` tag disambiguates (so `out` must carry tags — the default body verbosity does).
     * Returns **null on FAILURE** (network/timeout/non-2xx) and an (possibly empty) list on a SUCCESSFUL
     * parse — the distinction matters: the caller area-caches the result, and caching a failure as an
     * authoritative "no controls here" would blank the layer until the user pans out of the cached box.
     * Queried per padded viewport by the caller (which area-caches it, since controls are static), NOT per fix.
     */
    @OptIn(ExperimentalSerializationApi::class)
    /** The one node-selector set every controls fetch shares: lights, stop signs, and the static
     *  road AIDS (level crossings, feelable traffic calming). `traffic_calming` is filtered to the
     *  shapes a driver actually feels - island/chicane/choker are lane geometry, not a bump. */
    private fun controlSelectors(where: String): String =
        "node[\"highway\"=\"traffic_signals\"]$where;" +
            "node[\"highway\"=\"stop\"]$where;" +
            "node[\"railway\"=\"level_crossing\"]$where;" +
            "node[\"traffic_calming\"~\"^(bump|hump|table|cushion)$\"]$where;"

    private fun kindOf(tags: Map<String, String>?): TrafficControl.Kind = when {
        tags?.get("highway") == "stop" -> TrafficControl.Kind.STOP
        tags?.get("railway") == "level_crossing" -> TrafficControl.Kind.RAIL_CROSSING
        tags?.get("traffic_calming") != null -> TrafficControl.Kind.SPEED_HUMP
        else -> TrafficControl.Kind.SIGNAL
    }

    fun fetchControlsInBox(
        http: OkHttpClient,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 6000,
    ): List<TrafficControl>? {
        val box = "($south,$west,$north,$east)"
        val query = "[out:json][timeout:25];(${controlSelectors(box)});out $limit;"
        // Failover across mirrors (see OverpassEndpoints): a 504 from the primary no longer blanks the layer.
        // run() returns null only when EVERY endpoint fails — a real failure, not a genuine empty area.
        return OverpassEndpoints.run(http, query) { body ->
            // STREAM into a tiny DTO, same as OverpassAlprCameras: an `out 6000` dense-metro response
            // held as a String + full JsonElement DOM cost ~5-10x the wire size in transient heap —
            // the same churn that OOM'd the flock fetch (this was its named follow-up).
            json.decodeFromStream<SignalsResp>(body.byteStream()).elements.mapNotNull { n ->
                val lat = n.lat ?: return@mapNotNull null
                val lng = n.lon ?: return@mapNotNull null
                TrafficControl(LatLng(lat, lng), kindOf(n.tags))
            }
        }
    }

    /**
     * Traffic-signal AND stop-sign nodes along a route CORRIDOR (~[radiusM] m either side of the
     * polyline), for drawing during NAV: one fetch covers the whole drive, where the per-viewport
     * [fetchControlsInBox] path churned a refetch every time the moving camera neared its cached box
     * edge (against sometimes-flaky mirrors) and blinked the layer (issue #248). Uses Overpass's
     * `around:` LINESTRING form — consecutive coordinates are treated as segments, so the corridor is
     * continuous; the polyline is only SAMPLED (default ~300 m steps, hard cap [maxPts] points) to keep
     * the GET URL within what every mirror accepts. Same null-on-failure contract as the box fetch.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun fetchControlsAlongCorridor(
        http: OkHttpClient,
        polyline: List<LatLng>,
        radiusM: Int = 120,
        maxPts: Int = 250,
        limit: Int = 6000,
    ): List<TrafficControl>? {
        if (polyline.size < 2) return emptyList()
        val pts = sampleForCorridor(polyline, maxPts)
        val coords = pts.joinToString(",") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng) }
        val around = "(around:$radiusM,$coords)"
        val query = "[out:json][timeout:25];(${controlSelectors(around)});out $limit;"
        return OverpassEndpoints.run(http, query) { body ->
            json.decodeFromStream<SignalsResp>(body.byteStream()).elements.mapNotNull { n ->
                val lat = n.lat ?: return@mapNotNull null
                val lng = n.lon ?: return@mapNotNull null
                TrafficControl(LatLng(lat, lng), kindOf(n.tags))
            }
        }
    }

    /** Thin [polyline] to at most [maxPts] points, keeping first + last, by uniform index stride —
     *  route polylines are already distance-dense, so an index stride approximates a distance stride
     *  without walking haversines over thousands of vertices. */
    private fun sampleForCorridor(polyline: List<LatLng>, maxPts: Int): List<LatLng> {
        if (polyline.size <= maxPts) return polyline
        val stride = (polyline.size - 1).toDouble() / (maxPts - 1)
        return (0 until maxPts).map { polyline[Math.round(it * stride).toInt().coerceAtMost(polyline.size - 1)] }
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
