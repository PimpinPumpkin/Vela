package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Naming a file the importer cannot use ([ImportFormats]).
 *
 * This exists only to write a better sentence: someone who exported from Google Takeout or Organic
 * Maps should be told what their file is, instead of "nothing to import", which sends them looking
 * for a bug in Vela (issue #287 - a reporter hit exactly that with Takeout data).
 */
class ImportFormatsTest {

    // The shape Google Takeout actually produces for saved places.
    private val takeout = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
        "geometry":{"type":"Point","coordinates":[-121.7405,38.5449]},
        "properties":{"google_maps_url":"http://maps.google.com/?cid=123","location":{"name":"Somewhere"}}}]}
    """.trimIndent()

    @Test fun `google takeout is named specifically`() {
        assertEquals("Google Takeout", ImportFormats.describe(takeout))
    }

    @Test fun `plain geojson is named as geojson`() {
        val geo = """{"type":"FeatureCollection","features":[]}"""
        assertEquals("GeoJSON", ImportFormats.describe(geo))
    }

    @Test fun `gpx and kml are recognised`() {
        assertEquals("GPX", ImportFormats.describe("""<?xml version="1.0"?><gpx version="1.1"></gpx>"""))
        assertEquals("KML", ImportFormats.describe("""<kml><Placemark></Placemark></kml>"""))
    }

    // Vela's own export must never be mistaken for someone else's format.
    @Test fun `vela's own export is not named as foreign`() {
        val ours = """[{"id":"g:1","name":"Somewhere","lat":38.5449,"lng":-121.7405}]"""
        assertNull(ImportFormats.describe(ours))
    }

    @Test fun `unrecognised junk simply has no name`() {
        assertNull(ImportFormats.describe("not json at all"))
        assertNull(ImportFormats.describe("{}"))
    }
}
