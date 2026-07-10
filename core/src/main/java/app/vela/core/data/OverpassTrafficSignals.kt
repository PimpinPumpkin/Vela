package app.vela.core.data

import app.vela.core.model.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

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

object OverpassTrafficSignals {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
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
    fun fetchControlsInBox(
        http: OkHttpClient,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 6000,
    ): List<TrafficControl>? {
        return try {
            val box = "($south,$west,$north,$east)"
            val query = "[out:json][timeout:25];" +
                "(node[\"highway\"=\"traffic_signals\"]$box;node[\"highway\"=\"stop\"]$box;);out $limit;"
            val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "VelaMaps/0.1 (+https://github.com/alltechdev/vela-dpad)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null // a failure, NOT a genuine empty area - don't cache it
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["elements"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lng = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val kind = (o["tags"]?.jsonObject?.get("highway") as? JsonPrimitive)?.content
                    TrafficControl(LatLng(lat, lng), stop = kind == "stop")
                }.orEmpty()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Traffic-signal node coordinates within the route's bounding box (padded a little). */
    fun fetchAlong(http: OkHttpClient, polyline: List<LatLng>, limit: Int = 4000): List<LatLng> {
        if (polyline.size < 2) return emptyList()
        return try {
        val pad = 0.003 // ~300 m, so a signal just off the sampled line still lands in the box
        val s = polyline.minOf { it.lat } - pad
        val n = polyline.maxOf { it.lat } + pad
        val w = polyline.minOf { it.lng } - pad
        val e = polyline.maxOf { it.lng } + pad
        val query = "[out:json][timeout:25];node[\"highway\"=\"traffic_signals\"]($s,$w,$n,$e);out $limit;"
        val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/alltechdev/vela-dpad)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            root["elements"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lng = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                LatLng(lat, lng)
            }.orEmpty()
        }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
