package app.vela.core

import app.vela.core.data.MapLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLinkParserTest {

    @Test fun geoPoint() {
        val l = MapLinkParser.parse("geo:38.5449,-121.7405")!!
        assertEquals(38.5449, l.lat!!, 1e-6)
        assertEquals(-121.7405, l.lng!!, 1e-6)
        assertNull(l.query)
    }

    @Test fun geoZoomIsHonoured() {
        val l = MapLinkParser.parse("geo:38.5449,-121.7405?z=17")!!
        assertEquals(17.0, l.zoom!!, 1e-6)
        // Junk/out-of-range zooms are dropped, never crash.
        assertNull(MapLinkParser.parse("geo:38.5449,-121.7405?z=potato")!!.zoom)
        assertNull(MapLinkParser.parse("geo:38.5449,-121.7405?z=99")!!.zoom)
        // Google web links carry it after the @coords.
        val g = MapLinkParser.parse("https://www.google.com/maps/place/Foo/@38.5,-121.7,15z")!!
        assertEquals(15.0, g.zoom!!, 1e-6)
    }

    @Test fun geoZeroWithQueryIsSearch() {
        val l = MapLinkParser.parse("geo:0,0?q=Temple%20Coffee")!!
        assertEquals("Temple Coffee", l.query)
        assertNull(l.lat)
    }

    @Test fun geoLabelledPoint() {
        val l = MapLinkParser.parse("geo:0,0?q=38.5,-121.7(Home)")!!
        assertEquals("Home", l.query)
        assertEquals(38.5, l.lat!!, 1e-6)
        assertEquals(-121.7, l.lng!!, 1e-6)
    }

    @Test fun geoNamedNearPoint() {
        val l = MapLinkParser.parse("geo:38.5,-121.7?q=Pier 39")!!
        assertEquals("Pier 39", l.query)
        assertEquals(38.5, l.lat!!, 1e-6)
    }

    /** The exact shape Vela's "Map pin (geo:)" share emits: a labelled point that
     *  also pins the coordinates — must round-trip back to name + coords. */
    @Test fun geoSelfShareRoundTrips() {
        val l = MapLinkParser.parse("geo:38.5449,-121.7405?q=38.5449,-121.7405(Temple%20Coffee)")!!
        assertEquals("Temple Coffee", l.query)
        assertEquals(38.5449, l.lat!!, 1e-6)
        assertEquals(-121.7405, l.lng!!, 1e-6)
    }

    @Test fun mapsPlaceUrl() {
        val l = MapLinkParser.parse("https://www.google.com/maps/place/Temple+Coffee/@38.55,-121.74,15z")!!
        assertEquals("Temple Coffee", l.query)
        assertEquals(38.55, l.lat!!, 1e-6)
    }

    @Test fun mapsSearchUrl() {
        val l = MapLinkParser.parse("https://www.google.com/maps/search/coffee+davis")!!
        assertEquals("coffee davis", l.query)
    }

    @Test fun mapsQueryParam() {
        val l = MapLinkParser.parse("https://maps.google.com/?q=Sacramento")!!
        assertEquals("Sacramento", l.query)
    }

    @Test fun mapsCoordQueryIsPoint() {
        val l = MapLinkParser.parse("https://www.google.com/maps?q=38.5,-121.7")!!
        assertEquals(38.5, l.lat!!, 1e-6)
        assertNull(l.query)
    }

    @Test fun nonMapLinkIsNull() {
        assertNull(MapLinkParser.parse("https://example.com/foo"))
        assertNull(MapLinkParser.parse("geo:0,0"))
    }

    @Test fun hasTargetGate() {
        assertTrue(MapLinkParser.parse("geo:38.5,-121.7")!!.hasTarget)
    }

    @Test fun bareTypedCoordinates() {
        val ok = MapLinkParser.parseBareCoordinate("47.6097, -122.3331")!!
        assertEquals(47.6097, ok.lat!!, 1e-9)
        assertEquals(-122.3331, ok.lng!!, 1e-9)
        // geo: prefix works too
        assertEquals(31.7767, MapLinkParser.parseBareCoordinate("geo:31.7767,35.2274")!!.lat!!, 1e-9)
        // an address with numbers is NOT a coordinate
        assertNull(MapLinkParser.parseBareCoordinate("1451 W Covell Blvd, Davis"))
        // trailing text disqualifies (must be the whole string)
        assertNull(MapLinkParser.parseBareCoordinate("47.6, -122.3 area"))
        // out-of-range values are rejected
        assertNull(MapLinkParser.parseBareCoordinate("91.0, 10.0"))
        assertNull(MapLinkParser.parseBareCoordinate("47.0, 181.0"))
        // integers without a decimal point stay a search
        assertNull(MapLinkParser.parseBareCoordinate("5, 7"))
    }
}
