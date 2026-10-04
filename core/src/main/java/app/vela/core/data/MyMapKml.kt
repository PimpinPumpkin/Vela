package app.vela.core.data

import app.vela.core.model.ImportedList
import app.vela.core.model.LatLng
import app.vela.core.model.MapShape
import app.vela.core.model.distanceTo
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
 *  - marker icons: the KML names an icon only by number (`icon-1577-FFD600`); the map's public
 *    viewer page names it in full (`1577-food-fork-knife`), and Google's icon server draws any
 *    named icon in any color with no key ([iconUrl]). A number the page does not name, and the
 *    plain pin (1899), keep Vela's own pin in the marker's color;
 *  - directions layers: a layer holding one line and the points it runs through, first to last,
 *    is a planned trip. Its points become the line's [MapShape.stops] and leave the pin list.
 * The base map style and videos are not carried.
 *
 * Regex over the text, like [PlaceImport]: the format is flat and regular, and `:core` stays free
 * of an XML dependency. KML coordinates are `lng,lat[,alt]`.
 */
object MyMapKml {
    const val MAX_PLACES = 2000
    const val MAX_SHAPES = 500
    const val MAX_SHAPE_POINTS = 150_000

    private data class Style(val line: Long? = null, val width: Float? = null, val fill: Long? = null, val icon: Long? = null, val href: String? = null)

    /** Icon number -> full icon name, read off the map's viewer page. */
    fun iconNames(viewerHtml: String?): Map<String, String> =
        ICON_NAME.findAll(viewerHtml.orEmpty()).associate { it.groupValues[1] to it.groupValues[1] + "-" + it.groupValues[2] }

    /** Google's icon server URL for a named My Maps icon in [rgb] (six hex digits). */
    fun iconUrl(name: String, rgb: String): String =
        "https://mt.googleapis.com/vt/icon/name=icons/onion/SHARED-mymaps-container-bg_4x.png,icons/onion/SHARED-mymaps-container_4x.png," +
            "icons/onion/${name}_4x.png&highlight=ff000000,${rgb.uppercase()}&scale=4.0"

    /** [viewerHtml]: the map's public viewer page, for the icon names; null = colored pins only. */
    fun parse(kml: String, mid: String = "", viewerHtml: String? = null): ImportedList? {
        val names = iconNames(viewerHtml)
        if (!kml.contains("<kml", ignoreCase = true)) return null
        val head = kml.substringBefore("<Folder>").substringBefore("<Placemark>")
        val title = tag(head.substringAfter("<Document>", head), "name")?.ifBlank { null } ?: "My Maps"
        val description = tag(head.substringAfter("<Document>", head), "description")?.ifBlank { null }

        val styles = HashMap<String, Style>()
        STYLE.findAll(kml).forEach { m ->
            val body = m.groupValues[2]
            val line = Regex("<LineStyle>(.*?)</LineStyle>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val poly = Regex("<PolyStyle>(.*?)</PolyStyle>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            val icon = Regex("<IconStyle>(.*?)</IconStyle>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            styles[m.groupValues[1]] = Style(
                icon = icon?.let { tag(it, "color") }?.let(::kmlColor),
                // A marker with an uploaded image: the export links it. Google's stock blank is not one.
                href = icon?.let { tag(it, "href") }?.takeIf { it.startsWith("https://") && !it.contains("/mapspro/images/stock/") },
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
        var folderPlaces = 0
        var folderShapes = 0
        TOKEN.findAll(kml).forEach { m ->
            when {
                m.value.startsWith("<Folder") -> { layer = tag(m.value, "name")?.ifBlank { null }; folderPlaces = places.size; folderShapes = shapes.size }
                m.value == "</Folder>" -> {
                    // A directions layer: one line, and points it starts at, passes and ends at.
                    val pts = places.subList(folderPlaces, places.size)
                    val line = shapes.getOrNull(folderShapes)?.takeIf { shapes.size == folderShapes + 1 && !it.closed }
                    if (line != null && pts.size in 2..MAX_ROUTE_STOPS) {
                        val a = LatLng(line.pts[0], line.pts[1]); val b = LatLng(line.pts[line.pts.size - 2], line.pts[line.pts.size - 1])
                        if (a.distanceTo(pts.first().location) <= ROUTE_END_M && b.distanceTo(pts.last().location) <= ROUTE_END_M) {
                            shapes[folderShapes] = line.copy(stops = pts.map { app.vela.core.model.ShapeStop(it.name, it.location.lat, it.location.lng) })
                            pts.clear()
                        }
                    }
                    layer = null
                }
                else -> {
                    val body = m.groupValues[2]
                    val name = tag(body, "name").orEmpty()
                    val rawNote = tag(body, "description")
                    val note = rawNote?.let(::plainText)?.ifBlank { null }
                    // Photos: Google lists them in gx_media_links, and as <img> tags in the description.
                    val photos = (MEDIA.find(body)?.groupValues?.get(1)?.let(::cdata)?.trim()?.split(Regex("\\s+")).orEmpty() +
                        IMG.findAll(rawNote.orEmpty()).map { it.groupValues[1] }).filter { it.startsWith("http") }.distinct().take(12)
                    val styleId = tag(body, "styleUrl")?.removePrefix("#")
                    val style = styleId?.let { styles[it] }
                    val iconId = styleId?.let { ICON_STYLE.find(it) }
                    val iconName = iconId?.groupValues?.get(1)?.takeIf { it != PLAIN_PIN }?.let { names[it] }
                    val iconUrl = style?.href ?: iconName?.let { iconUrl(it, iconId.groupValues[2]) }
                    POINT.findAll(body).forEach { p ->
                        val c = coords(p.groupValues[1]).firstOrNull()
                        if (c != null && places.size < MAX_PLACES) {
                            places += Place(
                                id = "mymap:" + (mid + "|" + name + "|" + c.lat + "," + c.lng).hashCode().toString(16),
                                name = name.ifBlank { layer ?: title }, location = c, category = layer.takeIf { layered }, savedNote = note,
                                pinColor = style?.icon, mapLayer = layer, photoUrls = photos, pinIconUrl = iconUrl,
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
    private const val PLAIN_PIN = "1899"
    const val MAX_ROUTE_STOPS = 12
    const val ROUTE_END_M = 250.0
    private val ICON_STYLE = Regex("^icon-(\\d{3,5})-([0-9A-Fa-f]{6})")
    private val ICON_NAME = Regex("icons/onion/(\\d{3,5})-([a-z0-9][a-z0-9_-]*?)_4x\\.png")

    private val STYLE = Regex("<Style id=\"([^\"]+)\">(.*?)</Style>", RegexOption.DOT_MATCHES_ALL)
    private val STYLE_MAP = Regex("<StyleMap id=\"([^\"]+)\">(.*?)</StyleMap>", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN = Regex("<Folder>\\s*<name>.*?</name>|</Folder>|<Placemark(\\s[^>]*)?>(.*?)</Placemark>", RegexOption.DOT_MATCHES_ALL)
    private val POINT = Regex("<Point>.*?<coordinates>(.*?)</coordinates>.*?</Point>", RegexOption.DOT_MATCHES_ALL)
    private val LINE = Regex("<LineString>.*?<coordinates>(.*?)</coordinates>.*?</LineString>", RegexOption.DOT_MATCHES_ALL)
    private val POLYGON = Regex("<Polygon>.*?<outerBoundaryIs>.*?<coordinates>(.*?)</coordinates>.*?</outerBoundaryIs>.*?</Polygon>", RegexOption.DOT_MATCHES_ALL)

    private val MEDIA = Regex("<Data name=\"gx_media_links\">\\s*<value>(.*?)</value>", RegexOption.DOT_MATCHES_ALL)
    private val IMG = Regex("<img[^>]*\\ssrc=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private fun cdata(s: String) = s.trim().removePrefix("<![CDATA[").removeSuffix("]]>")

    private fun tag(text: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?.let(::cdata)?.trim()?.let(::unescape)

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
