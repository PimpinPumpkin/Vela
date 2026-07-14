package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * **Photon** (photon.komoot.io) - komoot's community OSM geocoder, keyless with a fair-use policy,
 * built exactly for search-as-you-type. Vela uses it for ADDRESS suggestions only: Google's keyless
 * ranking is great for businesses but barely honours the location bias for a partial house address
 * ("123 main st" led with matches states away). Photon takes a lat/lon bias and ranks around it,
 * which is the polished-feeling piece the suggest dropdown was missing. One small call per typed
 * pause, only when the query LOOKS like an address (digits leading) - business queries never hit it.
 */
object PhotonGeocoder {
    private const val BASE = "https://photon.komoot.io/api/"
    private val json = Json { ignoreUnknownKeys = true }

    /** Does this query read as a street address (house number first)? Those are the queries
     *  Google's keyless suggest ranks badly and Photon ranks well. */
    fun looksLikeAddress(q: String): Boolean = Regex("""^\d+\s+\S+""").containsMatchIn(q.trim())

    /** Address suggestions near [near], nearest-biased by Photon itself. Empty on any failure. */
    fun suggest(http: OkHttpClient, q: String, near: LatLng?, lang: String = "en", limit: Int = 4): List<Place> {
        val url = buildString {
            append(BASE).append("?q=").append(URLEncoder.encode(q, "UTF-8")).append("&limit=").append(limit)
            // A HARD metro bbox (~±60 km), not the soft lat/lon bias: probed live, the bias still
            // let a famous far "123 Main Street" outrank every nearby one, while the bbox returns
            // only matches around you - which is what a partial house address means.
            near?.let {
                val dLat = 0.55
                val dLng = 0.55 / Math.cos(Math.toRadians(it.lat)).coerceAtLeast(0.2)
                append("&bbox=").append(it.lng - dLng).append(",").append(it.lat - dLat)
                    .append(",").append(it.lng + dLng).append(",").append(it.lat + dLat)
            }
            // Photon only speaks a few UI languages; anything else falls back to default names.
            if (lang in setOf("en", "de", "fr")) append("&lang=").append(lang)
        }
        val body = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "VelaMaps/0.4 (+https://github.com/PimpinPumpkin/Vela)").build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching { json.decodeFromString<PhotonResp>(body) }.getOrNull() ?: return emptyList()
        return parsed.features.mapNotNull { f ->
            val c = f.geometry?.coordinates ?: return@mapNotNull null
            if (c.size < 2) return@mapNotNull null
            val loc = LatLng(c[1], c[0])
            val p = f.properties ?: return@mapNotNull null
            // "123 Main St" style primary line; Photon splits number/street/name.
            val primary = listOfNotNull(p.housenumber, p.street ?: p.name).joinToString(" ").ifBlank { p.name ?: return@mapNotNull null }
            val locality = listOfNotNull(p.city ?: p.district, p.state, p.postcode).joinToString(", ").ifBlank { null }
            Place(
                id = "photon:${p.osm_id ?: "${loc.lat},${loc.lng}"}",
                name = primary,
                location = loc,
                category = "Address",
                address = listOfNotNull(primary, locality).joinToString(", "),
                distanceMeters = near?.distanceTo(loc),
            )
        }
    }

    @Serializable
    private data class PhotonResp(val features: List<Feature> = emptyList())

    @Serializable
    private data class Feature(val geometry: Geometry? = null, val properties: Props? = null)

    @Serializable
    private data class Geometry(val coordinates: List<Double> = emptyList())

    @Serializable
    private data class Props(
        val name: String? = null,
        val housenumber: String? = null,
        val street: String? = null,
        val city: String? = null,
        val district: String? = null,
        val state: String? = null,
        val postcode: String? = null,
        val osm_id: Long? = null,
    )
}
