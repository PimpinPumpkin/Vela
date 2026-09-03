package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading other apps' saved places ([PlaceImport], issue #279).
 *
 * The fixtures are the real shapes these apps export. The failure that matters most is silent:
 * GPX writes lat then lon, while KML and GeoJSON write LNG FIRST, so getting the order wrong
 * imports someone's whole bookmark collection into the Gulf of Guinea without erroring.
 */
class PlaceImportTest {

    // Organic Maps / OsmAnd / CoMaps all export GPX waypoints in this shape.
    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Organic Maps">
          <wpt lat="38.5449" lon="-121.7405"><name>Davis Food Co-op</name></wpt>
          <wpt lat="37.7749" lon="-122.4194"><name><![CDATA[Ferry Building]]></name></wpt>
        </gpx>
    """.trimIndent()

    private val kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
          <Placemark><name>Bookshop &amp; Cafe</name>
            <Point><coordinates>-121.7405,38.5449,0</coordinates></Point></Placemark>
          <Placemark><name>Second Spot</name>
            <Point><coordinates>-122.4194,37.7749</coordinates></Point></Placemark>
        </Document></kml>
    """.trimIndent()

    // Google Takeout's saved places: name and address nested under properties.location.
    private val takeout = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-121.7405,38.5449]},
           "properties":{"google_maps_url":"http://maps.google.com/?cid=1",
                         "location":{"name":"Covell Market","address":"1451 W Covell Blvd, Davis, CA"}}}]}
    """.trimIndent()

    @Test fun `gpx waypoints come in with their names and coordinates`() {
        val out = PlaceImport.parse(gpx)
        assertEquals(2, out.size)
        assertEquals("Davis Food Co-op", out[0].name)
        assertEquals(38.5449, out[0].lat, 1e-6)
        assertEquals(-121.7405, out[0].lng, 1e-6)
    }

    @Test fun `a CDATA wrapped name is read as plain text`() {
        assertEquals("Ferry Building", PlaceImport.parse(gpx)[1].name)
    }

    // KML is lng,lat - reading it as lat,lng would put California in the Atlantic.
    @Test fun `kml coordinates are read longitude first`() {
        val out = PlaceImport.parse(kml)
        assertEquals(2, out.size)
        assertEquals(38.5449, out[0].lat, 1e-6)
        assertEquals(-121.7405, out[0].lng, 1e-6)
        assertTrue("latitude must be plausible, not swapped", out.all { it.lat in 30.0..40.0 })
    }

    @Test fun `xml entities in a name are decoded`() {
        assertEquals("Bookshop & Cafe", PlaceImport.parse(kml)[0].name)
    }

    @Test fun `google takeout brings the name and address across`() {
        val out = PlaceImport.parse(takeout)
        assertEquals(1, out.size)
        assertEquals("Covell Market", out[0].name)
        assertEquals("1451 W Covell Blvd, Davis, CA", out[0].address)
        assertEquals(38.5449, out[0].lat, 1e-6)
    }

    // Re-importing the same file must not double every pin, so ids are content-derived.
    @Test fun `the same place imports to the same id twice`() {
        assertEquals(PlaceImport.parse(gpx).map { it.id }, PlaceImport.parse(gpx).map { it.id })
    }

    @Test fun `an unset coordinate is dropped rather than pinned at null island`() {
        val junk = """<gpx><wpt lat="0" lon="0"><name>Nowhere</name></wpt></gpx>"""
        assertTrue(PlaceImport.parse(junk).isEmpty())
    }

    @Test fun `an impossible coordinate is dropped`() {
        val bad = """<gpx><wpt lat="999" lon="12"><name>Broken</name></wpt></gpx>"""
        assertTrue(PlaceImport.parse(bad).isEmpty())
    }

    @Test fun `a waypoint with no name still imports, labelled by its coordinate`() {
        val noName = """<gpx><wpt lat="38.5449" lon="-121.7405"></wpt></gpx>"""
        val out = PlaceImport.parse(noName)
        assertEquals(1, out.size)
        assertTrue("should be labelled by coordinate", out[0].name.contains("38.54"))
    }

    @Test fun `something that is not a places file yields nothing`() {
        assertTrue(PlaceImport.parse("hello there").isEmpty())
        assertTrue(PlaceImport.parse("{\"unrelated\":true}").isEmpty())
        assertTrue(PlaceImport.parse("").isEmpty())
    }
}
