package app.vela.core.data

import app.vela.core.model.ImportedList
import app.vela.core.model.LatLng
import app.vela.core.model.MapShape
import app.vela.core.model.Place

/**
 * A Google My Maps custom map, read from the KML Google serves for any map shared by link
 * (`/maps/d/kml?mid=<id>&forcekml=1`, no key, no session). Issue #669.
 *
 * What a custom map holds and what becomes of it here:
 *  - layers (`<Folder>`): the layer's name is each pin's category and each shape's [MapShape.layer];
 *  - markers (`<Point>`): places, the description as the pin's note;
 *  - lines and routes (`<LineString>`) and areas (`<Polygon>`, outer ring): [MapShape]s in the
 *    map's own colors (`<LineStyle>` / `<PolyStyle>`, through `<StyleMap>` when the map uses one);
 *  - the map's title and description.
 * Marker icons and colors, photos and the base map style are not carried: pins take their list's
 * icon like every other list in Vela.
 *
 * Regex over the text, like [PlaceImport]: the format is flat and regular, and `:core` stays free
 * of an XML dependency. KML coordinates are `lng,lat[,alt]`.
 */
object MyMapKml {
    const val MAX_PLACES = 2000
    const val MAX_SHAPES = 500
    const val MAX_SHAPE_POINTS = 150_000

    private data class Style(val line: Long? = null, val width: Float? = null, val fill: Long? = null)

    fun parse(kml: String, mid: String = ""): ImportedList? {
        if (!kml.contains("<kml", ignoreCase = true)) return null
        val head = kml.substringBefore("<Folder>").substringBefore("<Placemark>")
        val title = tag(head.substringAfter("<Document>", head), "name")?.ifBlank { null } ?: "My Maps"
        val description = tag(head.substringAfter("<Document>", head), "description")?.ifBlank { null }

        val styles = HashMap<String, Style>()
        STYLE.findAll(kml).forEach { m ->
            val body = m.groupValues[2]
            val line = Regex("<LineStyle>(.*?)</LineStyle>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val poly = Regex("<PolyStyle>(.*?)</PolyStyle>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            styles[m.groupValues[1]] = Style(
                line = line?.let { tag(it, "color") }?.let(::kmlColor),
                width = line?.let { tag(it, "width") }?.toFloatOrNull(),
                fill = poly?.let { tag(it, "color") }?.let(::kmlColor),
            )
        }
        // A StyleMap names a normal and a highlight style; the normal one is the look.
        STYLE_MAP.findAll(kml).forEach { m ->
            val normal = Regex("<key>normal</key>\\s*<styleUrl>#(.*?)</styleUrl>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[2])?.groupValues?.get(1)
            styles[normal]?.let { styles[m.groupValues[1]] = it }
        }

        // One layer says nothing ("Untitled layer" on most maps); several are worth showing.
        val layered = Regex("<Folder>").findAll(kml).count() > 1
        val places = ArrayList<Place>()
        val shapes = ArrayList<MapShape>()
        var layer: String? = null
        var shapePoints = 0
        TOKEN.findAll(kml).forEach { m ->
            when {
                m.value.startsWith("<Folder") -> layer = tag(m.value, "name")?.ifBlank { null }
                m.value == "</Folder>" -> layer = null
                else -> {
                    val body = m.groupValues[2]
                    val name = tag(body, "name").orEmpty()
                    val note = tag(body, "description")?.let(::plainText)?.ifBlank { null }
                    val style = tag(body, "styleUrl")?.removePrefix("#")?.let { styles[it] }
                    POINT.findAll(body).forEach { p ->
                        val c = coords(p.groupValues[1]).firstOrNull()
                        if (c != null && places.size < MAX_PLACES) {
                            places += Place(
                                id = "mymap:" + (mid + "|" + name + "|" + c.lat + "," + c.lng).hashCode().toString(16),
                                name = name.ifBlank { layer ?: title }, location = c, category = layer.takeIf { layered }, savedNote = note,
                            )
                        }
                    }
                    fun shape(raw: String, closed: Boolean) {
                        val pts = coords(raw)
                        if (pts.size < 2 || shapes.size >= MAX_SHAPES || shapePoints + pts.size > MAX_SHAPE_POINTS) return
                        shapePoints += pts.size
                        shapes += MapShape(
                            name = name, description = note, pts = pts.flatMap { listOf(it.lat, it.lng) }, closed = closed,
                            color = style?.line ?: DEFAULT_COLOR, width = (style?.width ?: 3f).coerceIn(1f, 12f),
                            fill = if (closed) (style?.fill ?: (DEFAULT_COLOR and 0x00FFFFFF or 0x40000000)) else null, layer = layer,
                        )
                    }
                    LINE.findAll(body).forEach { shape(it.groupValues[1], closed = false) }
                    POLYGON.findAll(body).forEach { shape(it.groupValues[1], closed = true) }
                }
            }
        }
        if (places.isEmpty() && shapes.isEmpty()) return null
        return ImportedList(title = title, description = description, places = places, shapes = shapes)
    }

    private const val DEFAULT_COLOR = 0xFF1A73E8

    private val STYLE = Regex("<Style id=\"([^\"]+)\">(.*?)</Style>", RegexOption.DOT_MATCHES_ALL)
    private val STYLE_MAP = Regex("<StyleMap id=\"([^\"]+)\">(.*?)</StyleMap>", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN = Regex("<Folder>\\s*<name>.*?</name>|</Folder>|<Placemark(\\s[^>]*)?>(.*?)</Placemark>", RegexOption.DOT_MATCHES_ALL)
    private val POINT = Regex("<Point>.*?<coordinates>(.*?)</coordinates>.*?</Point>", RegexOption.DOT_MATCHES_ALL)
    private val LINE = Regex("<LineString>.*?<coordinates>(.*?)</coordinates>.*?</LineString>", RegexOption.DOT_MATCHES_ALL)
    private val POLYGON = Regex("<Polygon>.*?<outerBoundaryIs>.*?<coordinates>(.*?)</coordinates>.*?</outerBoundaryIs>.*?</Polygon>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(text: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?.trim()?.removePrefix("<![CDATA[")?.removeSuffix("]]>")?.trim()?.let(::unescape)

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /** A description is HTML (line breaks, links, an image tag for each photo): the words only. */
    private fun plainText(html: String): String =
        html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ")
            .let(::unescape).lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun coords(raw: String): List<LatLng> = raw.trim().split(Regex("\\s+")).mapNotNull { t ->
        val p = t.split(',')
        val lng = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else LatLng(lat, lng)
    }

    /** KML writes a color as aabbggrr; Vela holds ARGB. */
    private fun kmlColor(c: String): Long? {
        val v = c.trim().toLongOrNull(16) ?: return null
        if (c.trim().length != 8) return null
        val a = (v shr 24) and 0xFF; val b = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val r = v and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
