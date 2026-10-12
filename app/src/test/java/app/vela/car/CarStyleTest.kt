package app.vela.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarStyleTest {
    private val style = """
        {"version":8,"sources":{"openmaptiles":{"type":"vector","tiles":["https://x/{z}/{x}/{y}.pbf"]}},
         "layers":[
          {"id":"park","type":"fill","source":"openmaptiles","source-layer":"landuse"},
          {"id":"landcover_wetland","type":"fill","source":"openmaptiles","source-layer":"landcover","paint":{"fill-pattern":"wetland"}},
          {"id":"road_area_pattern","type":"fill","source":"openmaptiles","source-layer":"transportation","paint":{"fill-pattern":"dots"}},
          {"id":"road_minor","type":"line","source":"openmaptiles","source-layer":"transportation"},
          {"id":"building","type":"fill","source":"openmaptiles","source-layer":"building"},
          {"id":"poi_r1","type":"symbol","source":"openmaptiles","source-layer":"poi"}
         ]}
    """.trimIndent()

    private fun ids(json: String): List<String> {
        val layers = org.json.JSONObject(json).getJSONArray("layers")
        return (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
    }

    @Test fun `the twins sit at the phone's anchors and the traffic raster over the buildings`() {
        val out = CarStyle.inject(style, "https://t/{z}/{x}/{y}.png")
        val ids = ids(out)
        assertEquals(
            listOf("park", "vela-commercial", "vela-pitch", "landcover_wetland", "vela-wetland", "road_area_pattern", "vela-plaza",
                "vela-trails", "vela-bikeroutes", "road_minor", "building", CarStyle.TRAFFIC_LAYER, "poi_r1"),
            ids,
        )
        val o = org.json.JSONObject(out)
        assertEquals("none", o.getJSONArray("layers").getJSONObject(3).getJSONObject("layout").getString("visibility"))
        assertEquals("none", o.getJSONArray("layers").getJSONObject(5).getJSONObject("layout").getString("visibility"))
        assertEquals("none", o.getJSONArray("layers").getJSONObject(11).getJSONObject("layout").getString("visibility"))
        assertEquals("raster", o.getJSONObject("sources").getJSONObject(CarStyle.TRAFFIC_SRC).getString("type"))
        assertEquals(CarStyle.LIGHT_INTENSITY, o.getJSONObject("light").getDouble("intensity"), 1e-9)
    }

    @Test fun `a second pass changes nothing`() {
        val once = CarStyle.inject(style, "https://t/{z}/{x}/{y}.png")
        assertEquals(once, CarStyle.inject(once, "https://t/{z}/{x}/{y}.png"))
    }

    @Test fun `another look gets the traffic raster and no twins`() {
        val out = CarStyle.inject(style, "https://t/{z}/{x}/{y}.png", twins = false)
        val ids = ids(out)
        assertEquals(listOf("park", "landcover_wetland", "road_area_pattern", "road_minor", "building", CarStyle.TRAFFIC_LAYER, "poi_r1"), ids)
        assertEquals(out, CarStyle.inject(out, "https://t/{z}/{x}/{y}.png", twins = false))
    }

    @Test fun `a style with no vector source is left alone`() {
        val raster = """{"version":8,"sources":{"sat":{"type":"raster","tiles":["https://s/{z}/{x}/{y}.jpg"]}},"layers":[{"id":"sat","type":"raster","source":"sat"}]}"""
        assertEquals(raster, CarStyle.inject(raster, "https://t/{z}/{x}/{y}.png"))
        assertTrue(CarStyle.inject("not json", "x") == "not json")
    }
}
