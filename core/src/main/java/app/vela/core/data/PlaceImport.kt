package app.vela.core.data

import app.vela.core.model.SavedPlace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading saved places out of OTHER apps' export files (issue #279).
 *
 * People arriving from Organic Maps, CoMaps, OsmAnd or Google Takeout have years of pins they do
 * not want to re-create by hand, and until now Vela could only tell them their file was the wrong
 * kind. The formats those apps actually export are GPX waypoints, KML placemarks and GeoJSON
 * points, so those three are what this reads.
 *
 * Deliberately conservative. It only ever produces a NAME and a COORDINATE, because that is the one
 * thing every format agrees on and the one thing that cannot be wrong: a place with the right
 * coordinate and a plain name is useful, whereas a place with a confidently wrong address is worse
 * than no import at all. Everything else in these files (icons, colours, folders, per-app custom
 * fields) is left behind on purpose.
 *
 * Parsing is deliberately shallow string/regex work rather than a real XML parser: these are
 * untrusted files, the shapes are simple and well known, and `:core` already avoids pulling in
 * parsers it does not need.
 */
object PlaceImport {

    /** Sanity bound for a single import, so a pathological file cannot exhaust memory. */
    private const val MAX_PLACES = 5_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Places found in [text], or empty when it is not a format we read.
     *
     * Order is the file's own, and duplicates are left in: the caller merges against what is
     * already saved and is the right place to decide what counts as the same place.
     */
    fun parse(text: String): List<SavedPlace> {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("<") -> parseXml(text)
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseGeoJson(text)
            else -> emptyList()
        }
    }

    // --- GPX / KML ---------------------------------------------------------------------------

    /** GPX `<wpt lat= lon=><name>` and KML `<Placemark><name>…<coordinates>lng,lat`. */
    private fun parseXml(text: String): List<SavedPlace> {
        val gpx = GPX_WPT.findAll(text).mapNotNull { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lng = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            place(nameIn(m.groupValues[3]) ?: fallbackName(lat, lng), lat, lng)
        }.take(MAX_PLACES).toList()
        if (gpx.isNotEmpty()) return gpx

        return KML_PLACEMARK.findAll(text).mapNotNull { m ->
            val body = m.groupValues[1]
            val coords = KML_COORDS.find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            // KML is lng,lat[,alt] - the reversed order is the classic way to import a whole file
            // into the sea off west Africa, so it is worth being explicit about.
            val parts = coords.split(Regex("[,\\s]+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size < 2) return@mapNotNull null
            val lng = parts[0]
            val lat = parts[1]
            place(nameIn(body) ?: fallbackName(lat, lng), lat, lng)
        }.take(MAX_PLACES).toList()
    }

    private val GPX_WPT = Regex(
        """<wpt\b[^>]*\blat\s*=\s*["']([-\d.]+)["'][^>]*\blon\s*=\s*["']([-\d.]+)["'][^>]*>(.*?)</wpt>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val KML_PLACEMARK = Regex(
        """<Placemark\b[^>]*>(.*?)</Placemark>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val KML_COORDS = Regex(
        """<coordinates>(.*?)</coordinates>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val NAME_TAG = Regex("""<name>(.*?)</name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun nameIn(body: String): String? =
        NAME_TAG.find(body)?.groupValues?.get(1)?.let { unescapeXml(it).trim() }?.takeIf { it.isNotBlank() }

    private fun unescapeXml(s: String): String {
        // CDATA is common in KML names written by desktop tools.
        val inner = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
            .find(s)?.groupValues?.get(1) ?: s
        return inner.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    }

    // --- GeoJSON (incl. Google Takeout) ------------------------------------------------------

    private fun parseGeoJson(text: String): List<SavedPlace> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val features = (root as? JsonObject)?.get("features") as? JsonArray
            ?: (root as? JsonArray)
            ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val geom = o["geometry"] as? JsonObject ?: return@mapNotNull null
            val coords = geom["coordinates"] as? JsonArray ?: return@mapNotNull null
            // GeoJSON is [lng, lat] - the same reversal trap as KML.
            val lng = coords.getOrNull(0)?.num() ?: return@mapNotNull null
            val lat = coords.getOrNull(1)?.num() ?: return@mapNotNull null
            val props = o["properties"] as? JsonObject
            place(geoJsonName(props) ?: fallbackName(lat, lng), lat, lng, geoJsonAddress(props))
        }.take(MAX_PLACES)
    }

    /** Takeout hides the name a level down in `properties.location.name`; plain GeoJSON uses
     *  `properties.name` (or `title`). */
    private fun geoJsonName(props: JsonObject?): String? {
        val p = props ?: return null
        val loc = p["location"] as? JsonObject
        return (loc?.get("name")?.text() ?: p["name"]?.text() ?: p["title"]?.text())
            ?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun geoJsonAddress(props: JsonObject?): String? {
        val p = props ?: return null
        val loc = p["location"] as? JsonObject
        return (loc?.get("address")?.text() ?: p["address"]?.text())?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JsonElement.num(): Double? = runCatching { jsonPrimitive.doubleOrNull }.getOrNull()
    private fun JsonElement.text(): String? = runCatching { jsonPrimitive.content }.getOrNull()

    // --- shared ------------------------------------------------------------------------------

    /** A coordinate that cannot be real is dropped rather than imported as a pin in the ocean. */
    private fun place(name: String, lat: Double, lng: Double, address: String? = null): SavedPlace? {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        if (lat == 0.0 && lng == 0.0) return null // null island: an unset coordinate, not a place
        // The id must be STABLE for the same place so re-importing the same file adds nothing the
        // second time; it is derived from the rounded coordinate rather than a counter.
        val id = "imp:%.5f,%.5f".format(java.util.Locale.US, lat, lng)
        return SavedPlace(id = id, name = name.take(120), lat = lat, lng = lng, address = address)
    }

    private fun fallbackName(lat: Double, lng: Double): String =
        "%.4f, %.4f".format(java.util.Locale.US, lat, lng)
}
