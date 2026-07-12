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

/** An automated licence-plate reader (ALPR / "Flock") camera at [loc]. [operator] is the agency/company
 *  that runs it when tagged (e.g. "Flock Safety"), [direction] the way it points (OSM `direction`, degrees
 *  or a compass string) when known - both may be blank. */
data class AlprCamera(val loc: LatLng, val operator: String = "", val direction: String = "")

/**
 * Fetches ALPR surveillance-camera locations from **Overpass** (OpenStreetMap's keyless query API). These are
 * the cameras the community DeFlock project maps as `man_made=surveillance` + `surveillance:type=ALPR`
 * (Flock Safety, Motorola, police plate readers). Sibling of [OverpassTrafficSignals]; same best-effort,
 * area-cached, viewport-fetched contract. Coverage is OSM's/DeFlock's - strong in the US, growing elsewhere.
 */
object OverpassAlprCameras {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ALPR camera nodes inside a bounding box, for DRAWING on the map. Matches the canonical DeFlock tag
     * `surveillance:type=ALPR` AND the older `man_made=surveillance` + `camera:type` plate-reader form some
     * imports used. Returns **null on FAILURE** (network/timeout/non-2xx) and a (possibly empty) list on a
     * SUCCESSFUL parse - the caller area-caches success only, so a failure retries instead of stamping a
     * false "no cameras here". Queried per padded viewport (area-cached; cameras are static), NOT per fix.
     */
    fun fetchInBox(
        http: OkHttpClient,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 4000,
    ): List<AlprCamera>? {
        return try {
            val box = "($south,$west,$north,$east)"
            // `out body` (NOT `out tags`): for a node, `out tags` prints only id + tags and OMITS
            // lat/lon, so the parser below dropped every camera (no coordinates) and the layer drew
            // nothing. `out body` is the default full print - id, lat, lon, AND tags - which is what
            // we need to both place the marker and read operator/direction. (device-caught 2026-07-12)
            val query = "[out:json][timeout:25];" +
                "node[\"surveillance:type\"=\"ALPR\"]$box;out body $limit;"
            val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "VelaMaps/0.1 (+https://github.com/PimpinPumpkin/Vela)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null // a failure, NOT a genuine empty area - don't cache it
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["elements"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val lng = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val tags = o["tags"]?.jsonObject
                    // Real DeFlock nodes tag the vendor as `manufacturer` ("Flock Safety"), not
                    // `operator` (which is usually the agency and often absent) - fall back to it so
                    // the camera actually shows who runs it.
                    val op = ((tags?.get("operator") ?: tags?.get("manufacturer")) as? JsonPrimitive)
                        ?.content.orEmpty()
                    val dir = (tags?.get("direction") as? JsonPrimitive)?.content.orEmpty()
                    AlprCamera(LatLng(lat, lng), op, dir)
                }.orEmpty()
            }
        } catch (e: Exception) {
            null
        }
    }
}
