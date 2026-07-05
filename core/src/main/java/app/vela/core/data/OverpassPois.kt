package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Fetches named POIs in a bounding box from **Overpass** (OpenStreetMap's keyless
 * query API) so the app has its own offline place index — the open, no-Google,
 * no-backend source behind offline search. Best-effort: any failure → empty.
 */
object OverpassPois {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private val json = Json { ignoreUnknownKeys = true }

    /** Named amenities/shops/tourism POIs in [south,west]..[north,east], capped. */
    fun fetch(
        http: OkHttpClient,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int = 1500,
    ): List<Place> = try {
        val bbox = "$south,$west,$north,$east"
        // amenity covers schools/hospitals/etc.; leisure covers parks/playgrounds; boundary=national_park
        // catches big parks tagged as a boundary rather than leisure=park (they'd otherwise be missed).
        val query = "[out:json][timeout:25];" +
            "(node[amenity][name]($bbox);node[shop][name]($bbox);node[tourism][name]($bbox);" +
            "node[\"public_transport\"][name]($bbox);node[leisure][name]($bbox);" +
            "node[boundary=national_park][name]($bbox););out $limit;"
        val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/PimpinPumpkin/Vela)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            root["elements"]?.jsonArray?.mapNotNull { el -> toPlace(el.jsonObject) }.orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toPlace(el: JsonObject): Place? {
        val tags = el["tags"]?.jsonObject ?: return null
        val name = (tags["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val lat = (el["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (el["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        fun tag(k: String) = (tags[k] as? JsonPrimitive)?.contentOrNull
        val category = tag("amenity") ?: tag("shop") ?: tag("tourism") ?: tag("leisure") ?: tag("public_transport") ?: tag("boundary")
        // Keep the useful OSM detail tags too, so offline POIs aren't just a name on a
        // pin — address (addr:*), phone, website and opening_hours where mapped.
        val street = listOfNotNull(tag("addr:housenumber"), tag("addr:street")).joinToString(" ").ifBlank { null }
        val address = listOfNotNull(
            street,
            tag("addr:city"),
            listOfNotNull(tag("addr:state"), tag("addr:postcode")).joinToString(" ").ifBlank { null },
        ).joinToString(", ").ifBlank { null }
        return Place(
            id = "osm:${el["id"]?.let { (it as? JsonPrimitive)?.content } ?: name.hashCode()}",
            name = name,
            location = LatLng(lat, lng),
            category = category?.replace('_', ' ')?.replaceFirstChar { it.uppercase() },
            address = address,
            phone = tag("phone") ?: tag("contact:phone"),
            website = tag("website") ?: tag("contact:website"),
            // OSM's compact opening_hours syntax ("Mo-Fr 08:00-20:00; Sa 09:00-17:00")
            // as a single line — better than nothing offline.
            hours = (tag("opening_hours"))?.let { listOf(it) } ?: emptyList(),
        )
    }
}
