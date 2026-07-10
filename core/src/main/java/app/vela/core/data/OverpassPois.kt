package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
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
 * query API) so the app has its own offline place index - the open, no-Google,
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
        // leisure/amenity/tourism are the categories OSM overwhelmingly maps as AREAS (a park/playground,
        // a school/hospital campus, a museum/zoo footprint), so those clauses are `nwr` - a `node`-only
        // query silently drops every area-mapped park (the common tagging for leisure=park) from the offline
        // index. shop/public_transport stay `node` (storefronts + stops are point-tagged,
        // bounding the extra Overpass load). boundary=national_park catches big parks tagged as a boundary.
        // `out center` gives every way/relation a representative point (a no-op for nodes, which already have
        // lat/lon) - toPlace reads `center` when top-level lat/lon is absent.
        val query = "[out:json][timeout:25];" +
            "(nwr[amenity][name]($bbox);node[shop][name]($bbox);nwr[tourism][name]($bbox);" +
            "node[\"public_transport\"][name]($bbox);nwr[leisure][name]($bbox);" +
            "nwr[boundary=national_park][name]($bbox););out center $limit;"
        val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/alltechdev/vela-dpad)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            root["elements"]?.jsonArray?.mapNotNull { el -> toPlace(el.jsonObject) }.orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Every addressed point (`addr:housenumber`) in the bbox - nodes AND ways (`out center` gives a
     * way a representative point) - for the offline [OfflineAddressStore] geocoder. Capped high because
     * a residential download area holds thousands of houses; use a long-timeout client for the body. */
    fun fetchAddresses(
        http: OkHttpClient,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int = 40000,
    ): List<OfflineAddressStore.Addr> = try {
        val bbox = "$south,$west,$north,$east"
        val query = "[out:json][timeout:120];" +
            "(node[\"addr:housenumber\"]($bbox);way[\"addr:housenumber\"]($bbox););out center $limit;"
        val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/alltechdev/vela-dpad)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            root["elements"]?.jsonArray?.mapNotNull { el -> toAddr(el.jsonObject) }.orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Named road centrelines in the bbox → sampled representative points per street, for the offline
     * geocoder's STREET-LEVEL fallback: OSM maps roads far more completely than house numbers, so a
     * suburb with no `addr:housenumber` points still has every named street here - enough to route to
     * "156th Street SE" even when no individual house on it is mapped. Vehicle-routable highway classes
     * only (skips footways/paths/tracks). Geometry comes back inline via `out geom`; we thin it to ~one
     * point per [SAMPLE_M] metres so the table stays bounded while "nearest point on the street" stays
     * accurate. Long-timeout client (a metro's road network is a big body). */
    fun fetchStreets(
        http: OkHttpClient,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int = 60000,
    ): List<OfflineAddressStore.StreetPt> = try {
        val bbox = "$south,$west,$north,$east"
        val query = "[out:json][timeout:120];" +
            "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|" +
            "residential|living_street|service|road)(_link)?$\"][\"name\"]($bbox);out geom $limit;"
        val url = "$ENDPOINT?data=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VelaMaps/0.1 (+https://github.com/alltechdev/vela-dpad)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            root["elements"]?.jsonArray?.flatMap { el -> toStreetPts(el.jsonObject) }.orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Thin a way's inline `geometry` to ~one kept point per [SAMPLE_M] metres (endpoints always kept). */
    private fun toStreetPts(el: JsonObject): List<OfflineAddressStore.StreetPt> {
        val name = (el["tags"]?.jsonObject?.get("name") as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val geom = el["geometry"]?.jsonArray ?: return emptyList()
        val pts = geom.mapNotNull { g ->
            val o = g.jsonObject
            val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull
            val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull
            if (lat != null && lon != null) LatLng(lat, lon) else null
        }
        if (pts.isEmpty()) return emptyList()
        val out = ArrayList<OfflineAddressStore.StreetPt>()
        var last: LatLng? = null
        for ((i, p) in pts.withIndex()) {
            val keep = i == 0 || i == pts.lastIndex || last == null || p.distanceTo(last!!) >= SAMPLE_M
            if (keep) { out.add(OfflineAddressStore.StreetPt(name, p.lat, p.lng)); last = p }
        }
        return out
    }

    private const val SAMPLE_M = 120.0 // keep ~one street-centreline point per this many metres

    private fun toAddr(el: JsonObject): OfflineAddressStore.Addr? {
        val tags = el["tags"]?.jsonObject ?: return null
        fun tag(k: String) = (tags[k] as? JsonPrimitive)?.contentOrNull
        val hn = tag("addr:housenumber") ?: return null
        val street = tag("addr:street") ?: return null // no street = not routable/searchable as an address
        val center = el["center"]?.jsonObject
        val lat = (el["lat"] as? JsonPrimitive)?.doubleOrNull
            ?: (center?.get("lat") as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (el["lon"] as? JsonPrimitive)?.doubleOrNull
            ?: (center?.get("lon") as? JsonPrimitive)?.doubleOrNull ?: return null
        return OfflineAddressStore.Addr(
            id = "addr:${el["id"]?.let { (it as? JsonPrimitive)?.content } ?: "$lat,$lng"}",
            housenumber = hn,
            street = street,
            city = tag("addr:city"),
            lat = lat,
            lng = lng,
        )
    }

    private fun toPlace(el: JsonObject): Place? {
        val tags = el["tags"]?.jsonObject ?: return null
        val name = (tags["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        // Nodes carry lat/lon directly; ways/relations (from `out center`, e.g. a national-park boundary)
        // carry a representative point under `center` instead.
        val center = el["center"]?.jsonObject
        val lat = (el["lat"] as? JsonPrimitive)?.doubleOrNull
            ?: (center?.get("lat") as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (el["lon"] as? JsonPrimitive)?.doubleOrNull
            ?: (center?.get("lon") as? JsonPrimitive)?.doubleOrNull ?: return null
        fun tag(k: String) = (tags[k] as? JsonPrimitive)?.contentOrNull
        val category = tag("amenity") ?: tag("shop") ?: tag("tourism") ?: tag("leisure") ?: tag("public_transport") ?: tag("boundary")
        // Keep the useful OSM detail tags too, so offline POIs aren't just a name on a
        // pin - address (addr:*), phone, website and opening_hours where mapped.
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
            // as a single line - better than nothing offline.
            hours = (tag("opening_hours"))?.let { listOf(it) } ?: emptyList(),
        )
    }
}
