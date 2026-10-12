package app.vela.car

import org.json.JSONArray
import org.json.JSONObject

/**
 * The layers the phone map adds to Liberty at runtime, written into the car's style JSON before
 * the snapshotter loads it. A `MapSnapshotter` can look a layer up and set its properties, but it
 * cannot add a layer or a source, so what the phone's `ensureLayers` builds with the layer API
 * goes in here as JSON: the flat wetland and plaza twins (parking lots and pedestrian areas, in
 * place of Liberty's patterned fills), the sports field and commercial fills, park trails and bike
 * paths, the style light, and Google's traffic raster, hidden until the renderer shows it.
 * The ids match the phone's, so `applyMapTheme` colors them on both.
 */
internal object CarStyle {
    const val TRAFFIC_SRC = "vela-traffic-src"
    const val TRAFFIC_LAYER = "vela-traffic"
    const val TRAFFIC_OPACITY = 0.6
    const val LIGHT_INTENSITY = 0.25

    private val POLYGON = JSONArray(listOf("match", JSONArray(listOf("geometry-type")), JSONArray(listOf("Polygon", "MultiPolygon")), true, false))
    private val LINE = JSONArray(listOf("match", JSONArray(listOf("geometry-type")), JSONArray(listOf("LineString", "MultiLineString")), true, false))
    private val PATH_WIDTH = JSONArray(listOf("interpolate", JSONArray(listOf("exponential", 1.4)), JSONArray(listOf("zoom")), 14, 0.7, 16, 1.6, 19, 4))

    /** [json] with the car layers in it. The JSON is returned untouched when the style has no
     *  `openmaptiles` source or lacks an anchor layer; the twins then stay absent as they would
     *  on a style without those layers on the phone. With [twins] false (a look Vela does not
     *  paint, where nothing would color them) only the traffic raster goes in. */
    fun inject(json: String, trafficTiles: String, twins: Boolean = true): String {
        val style = runCatching { JSONObject(json) }.getOrNull() ?: return json
        val sources = style.optJSONObject("sources") ?: return json
        val src = when {
            sources.has("openmaptiles") -> "openmaptiles"
            else -> sources.keys().asSequence().firstOrNull { sources.optJSONObject(it)?.optString("type") == "vector" } ?: return json
        }
        val layers = style.optJSONArray("layers") ?: return json
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).optString("id") }
        if ("vela-plaza" in ids || (!twins && TRAFFIC_LAYER in ids)) return json
        val out = mutableListOf<JSONObject>()
        for (i in 0 until layers.length()) out += layers.getJSONObject(i)

        fun layer(id: String, type: String, sourceLayer: String, filter: Any?, minzoom: Int?, paint: JSONObject?, layout: JSONObject?): JSONObject =
            JSONObject().apply {
                put("id", id); put("type", type); put("source", src); put("source-layer", sourceLayer)
                if (filter != null) put("filter", filter)
                if (minzoom != null) put("minzoom", minzoom)
                if (paint != null) put("paint", paint)
                if (layout != null) put("layout", layout)
            }
        fun index(id: String) = out.indexOfFirst { it.optString("id") == id }
        fun hide(id: String) {
            val l = out.getOrNull(index(id)) ?: return
            val layout = l.optJSONObject("layout") ?: JSONObject().also { l.put("layout", it) }
            layout.put("visibility", "none")
        }
        fun above(anchor: String, l: JSONObject) { val i = index(anchor); if (i >= 0) out.add(i + 1, l) }
        fun below(anchor: String, l: JSONObject) { val i = index(anchor); if (i >= 0) out.add(i, l) }
        fun classIn(vararg classes: String) = JSONArray(listOf("match", JSONArray(listOf("get", "class")), JSONArray(classes.toList()), true, false))

        // Liberty's patterned fills (a fern hatch, dots) are hidden and flat twins take their
        // place, the same two the phone hides.
        if (twins && "landcover_wetland" in ids) {
            hide("landcover_wetland")
            above("landcover_wetland", layer("vela-wetland", "fill", "landcover", JSONArray(listOf("==", JSONArray(listOf("get", "class")), "wetland")), 12, null, null))
        }
        if (twins && "road_area_pattern" in ids) {
            hide("road_area_pattern")
            above("road_area_pattern", layer("vela-plaza", "fill", "transportation", POLYGON, null, null, null))
        }
        if (twins && "park" in ids) {
            // The phone's order: the pitch twin first, then the commercial twin under it.
            above("park", layer("vela-pitch", "fill", "landuse", classIn("pitch", "playground", "track", "stadium"), 13, null, null))
            above("park", layer("vela-commercial", "fill", "landuse", classIn("commercial", "retail"), 12, null, null))
        }
        if (twins && "road_minor" in ids) {
            val lineLayout = JSONObject().put("line-cap", "round")
            val linePaint = JSONObject().put("line-width", PATH_WIDTH)
            val isPath = JSONArray(listOf("==", JSONArray(listOf("get", "class")), "path"))
            val foot = JSONArray(listOf("match", JSONArray(listOf("get", "subclass")), JSONArray(listOf("path", "bridleway")), true, false))
            val bike = JSONArray(listOf("==", JSONArray(listOf("get", "subclass")), "cycleway"))
            below("road_minor", layer("vela-trails", "line", "transportation", JSONArray(listOf("all", LINE, isPath, foot)), 14, linePaint, lineLayout))
            below("road_minor", layer("vela-bikeroutes", "line", "transportation", JSONArray(listOf("all", LINE, isPath, bike)), 14, linePaint, lineLayout))
        }
        // Google's traffic raster, where the phone puts it: under the labels, over the buildings.
        sources.put(TRAFFIC_SRC, JSONObject().put("type", "raster").put("tiles", JSONArray(listOf(trafficTiles))).put("tileSize", 256))
        val traffic = JSONObject().apply {
            put("id", TRAFFIC_LAYER); put("type", "raster"); put("source", TRAFFIC_SRC)
            put("layout", JSONObject().put("visibility", "none"))
            put("paint", JSONObject().put("raster-opacity", TRAFFIC_OPACITY).put("raster-fade-duration", 0))
        }
        when {
            "building-3d" in ids -> above("building-3d", traffic)
            "building" in ids -> above("building", traffic)
            else -> out.add(out.indexOfFirst { it.optString("type") == "symbol" }.takeIf { it >= 0 } ?: out.size, traffic)
        }
        // The phone's style light, so the car's building tops keep the palette color.
        style.put("light", (style.optJSONObject("light") ?: JSONObject()).put("intensity", LIGHT_INTENSITY))
        style.put("layers", JSONArray(out))
        return style.toString()
    }
}
