package app.vela.core.data

import java.net.URLDecoder

/** A target extracted from an external `geo:` URI or Google-Maps web link. */
data class MapLink(val query: String? = null, val lat: Double? = null, val lng: Double? = null) {
    val hasTarget: Boolean get() = !query.isNullOrBlank() || (lat != null && lng != null)
}

/**
 * Parses the links other apps hand to a maps app, so Vela can be the system maps
 * handler on a de-Googled phone:
 * - `geo:38.5,-121.7`               → a point
 * - `geo:0,0?q=Coffee`              → a search
 * - `geo:38.5,-121.7?q=Pier 39`     → a named place near a point
 * - `geo:0,0?q=38.5,-121.7(Label)`  → a labelled point
 * - `https://www.google.com/maps/place/Foo/@38.5,-121.7,15z` → a place / point
 * - `https://www.google.com/maps/search/coffee` / `?q=...`    → a search
 *
 * Pure Kotlin (no `android.net.Uri`) so it's unit-testable in `:core`.
 */
object MapLinkParser {
    private val COORD = Regex("""(-?\d{1,3}\.\d+),\s*(-?\d{1,3}\.\d+)""")
    private val AT = Regex("""@(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)""")

    fun parse(raw: String): MapLink? {
        val link = when {
            raw.startsWith("geo:", ignoreCase = true) -> parseGeo(raw)
            "/maps" in raw || "maps.google" in raw || "maps.app.goo.gl" in raw -> parseMaps(raw)
            else -> null
        }
        return link?.takeIf { it.hasTarget }
    }

    private fun parseGeo(raw: String): MapLink {
        val body = raw.substring(4) // after "geo:"
        val coordPart = body.substringBefore("?")
        var lat = COORD.find(coordPart)?.groupValues?.get(1)?.toDoubleOrNull()
        var lng = COORD.find(coordPart)?.groupValues?.get(2)?.toDoubleOrNull()
        if (lat == 0.0 && lng == 0.0) { lat = null; lng = null } // 0,0 = "no point, see ?q"

        val q = queryParam(raw, "q")?.let { decode(it) }
        if (!q.isNullOrBlank()) {
            // ?q can be "lat,lng(Label)", a bare "lat,lng", or an address/name.
            val label = Regex("""\(([^)]+)\)""").find(q)?.groupValues?.get(1)
            val qc = COORD.find(q)
            if (qc != null && lat == null) {
                return MapLink(query = label, lat = qc.groupValues[1].toDoubleOrNull(), lng = qc.groupValues[2].toDoubleOrNull())
            }
            val text = label ?: q.takeUnless { COORD.matches(it.trim()) }
            return MapLink(query = text, lat = lat, lng = lng)
        }
        return MapLink(lat = lat, lng = lng)
    }

    private fun parseMaps(raw: String): MapLink {
        val lat = AT.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val lng = AT.find(raw)?.groupValues?.get(2)?.toDoubleOrNull()
        val place = Regex("""/place/([^/@?]+)""").find(raw)?.groupValues?.get(1)
        val search = Regex("""/search/([^/@?]+)""").find(raw)?.groupValues?.get(1)
        val q = queryParam(raw, "q") ?: queryParam(raw, "query")
        val query = (place ?: search ?: q)?.let { decode(it.replace('+', ' ')) }?.takeIf { it.isNotBlank() }
        // A query that's really coordinates → treat as a point.
        query?.trim()?.let { COORD.matchEntire(it) }?.let {
            return MapLink(lat = it.groupValues[1].toDoubleOrNull(), lng = it.groupValues[2].toDoubleOrNull())
        }
        return MapLink(query = query, lat = lat, lng = lng)
    }

    private fun queryParam(raw: String, key: String): String? {
        val q = raw.substringAfter('?', "")
        if (q.isEmpty()) return null
        return q.split('&').firstNotNullOfOrNull { p ->
            val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k.equals(key, ignoreCase = true) && v.isNotEmpty()) v else null
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
