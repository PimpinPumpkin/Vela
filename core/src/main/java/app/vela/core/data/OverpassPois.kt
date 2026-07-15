package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient

/**
 * Fetches named POIs in a bounding box from **Overpass** (OpenStreetMap's keyless
 * query API) so the app has its own offline place index - the open, no-Google,
 * no-backend source behind offline search. Best-effort: any failure → empty.
 *
 * STREAM-PARSED (the largeHeap memory rule): these are the biggest Overpass bodies in the
 * app — an offline-area download pulls tens of thousands of address points and street
 * geometry in one response — and the old `.string()` + parseToJsonElement held ~5-10x the
 * wire size in transient heap, the exact pattern that OOM'd the Flock fetch (issue #182).
 * `decodeFromStream` into the slim DTOs below reads incrementally.
 */
object OverpassPois {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class OvPoint(val lat: Double? = null, val lon: Double? = null)

    @Serializable
    private data class OvElement(
        val id: Long? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: OvPoint? = null,
        val tags: Map<String, String> = emptyMap(),
        val geometry: List<OvPoint> = emptyList(),
    )

    @Serializable
    private data class OvResp(val elements: List<OvElement> = emptyList())

    /** Named amenities/shops/tourism POIs in [south,west]..[north,east], capped. */
    @OptIn(ExperimentalSerializationApi::class)
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
        OverpassEndpoints.run(http, query) { body ->
            json.decodeFromStream<OvResp>(body.byteStream()).elements.mapNotNull { toPlace(it) }
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Every addressed point (`addr:housenumber`) in the bbox - nodes AND ways (`out center` gives a
     * way a representative point) - for the offline [OfflineAddressStore] geocoder. Capped high because
     * a residential download area holds thousands of houses; use a long-timeout client for the body. */
    @OptIn(ExperimentalSerializationApi::class)
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
        OverpassEndpoints.run(http, query) { body ->
            json.decodeFromStream<OvResp>(body.byteStream()).elements.mapNotNull { toAddr(it) }
        } ?: emptyList()
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
    @OptIn(ExperimentalSerializationApi::class)
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
        OverpassEndpoints.run(http, query) { body ->
            json.decodeFromStream<OvResp>(body.byteStream()).elements.flatMap { toStreetPts(it) }
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Thin a way's inline `geometry` to ~one kept point per [SAMPLE_M] metres (endpoints always kept). */
    private fun toStreetPts(el: OvElement): List<OfflineAddressStore.StreetPt> {
        val name = el.tags["name"] ?: return emptyList()
        val pts = el.geometry.mapNotNull { g ->
            val lat = g.lat
            val lon = g.lon
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

    private fun toAddr(el: OvElement): OfflineAddressStore.Addr? {
        val hn = el.tags["addr:housenumber"] ?: return null
        val street = el.tags["addr:street"] ?: return null // no street = not routable/searchable as an address
        val lat = el.lat ?: el.center?.lat ?: return null
        val lng = el.lon ?: el.center?.lon ?: return null
        return OfflineAddressStore.Addr(
            id = "addr:${el.id ?: "$lat,$lng"}",
            housenumber = hn,
            street = street,
            city = el.tags["addr:city"],
            lat = lat,
            lng = lng,
        )
    }

    private fun toPlace(el: OvElement): Place? {
        val name = el.tags["name"] ?: return null
        // Nodes carry lat/lon directly; ways/relations (from `out center`, e.g. a national-park boundary)
        // carry a representative point under `center` instead.
        val lat = el.lat ?: el.center?.lat ?: return null
        val lng = el.lon ?: el.center?.lon ?: return null
        fun tag(k: String) = el.tags[k]
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
            id = "osm:${el.id ?: name.hashCode()}",
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
