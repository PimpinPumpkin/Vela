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

    @Test fun geoZoomIsHonored() {
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

    @Test fun geoLabeledPoint() {
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

    /** The exact shape Vela's "Map pin (geo:)" share emits: a labeled point that
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
        val ok = MapLinkParser.parseBareCoordinate("37.7749, -122.4194")!!
        assertEquals(37.7749, ok.lat!!, 1e-9)
        assertEquals(-122.4194, ok.lng!!, 1e-9)
        // geo: prefix works too
        assertEquals(31.7767, MapLinkParser.parseBareCoordinate("geo:31.7767,35.2274")!!.lat!!, 1e-9)
        // an address with numbers is NOT a coordinate
        assertNull(MapLinkParser.parseBareCoordinate("1451 W Covell Blvd, Davis"))
        // trailing text disqualifies (must be the whole string)
        assertNull(MapLinkParser.parseBareCoordinate("37.7, -122.4 area"))
        // out-of-range values are rejected
        assertNull(MapLinkParser.parseBareCoordinate("91.0, 10.0"))
        assertNull(MapLinkParser.parseBareCoordinate("47.0, 181.0"))
        // integers without a decimal point stay a search
        assertNull(MapLinkParser.parseBareCoordinate("5, 7"))
    }

    @Test fun `a place link with only a feature id keeps the id and says where its cell is`() {
        val l = MapLinkParser.parse("https://www.google.com/maps/place/Davis+Food+Co-op/data=!4m2!3m1!1s0x8085299a7b6c6f0b:0x9ab2c3d4e5f60718!18m1!1e1?utm_source=mstt_1")!!
        assertEquals("Davis Food Co-op", l.query)
        assertEquals("0x8085299a7b6c6f0b:0x9ab2c3d4e5f60718", l.featureId)
        assertNull(l.lat)
        val cell = MapLinkParser.featureCell(l.featureId)!!
        assertEquals(38.55, cell.lat, 0.05)
        assertEquals(-121.73, cell.lng, 0.05)
        assertNull(MapLinkParser.featureCell(null))
        assertNull(MapLinkParser.parse("https://www.google.com/maps/place/Davis+Ace+Hardware/@38.5449,-121.7405,17z")!!.featureId)
    }

    @Test fun `a Street View link from the web map opens the panorama, facing its way`() {
        val l = MapLinkParser.parse("https://www.google.com/maps/@38.5449,-121.7405,3a,75y,90.5h,90t/data=!3m6!1e1!3m4!1sAbCdEfGhIjKlMnOpQrStUv!2e0!7i16384!8i8192?entry=ttu")!!
        assertTrue(l.streetView)
        assertEquals(38.5449, l.lat!!, 1e-6)
        assertEquals(-121.7405, l.lng!!, 1e-6)
        assertEquals(90.5, l.heading!!, 1e-6)
        assertEquals("AbCdEfGhIjKlMnOpQrStUv", l.panoId)
        // The same place's map view is no Street View.
        assertEquals(false, MapLinkParser.parse("https://www.google.com/maps/@38.5449,-121.7405,17z")!!.streetView)
    }

    @Test fun `the documented pano action and the old layer=c link open it too`() {
        val a = MapLinkParser.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=38.5449%2C-121.7405&heading=-45&pitch=10&fov=80")!!
        assertTrue(a.streetView)
        assertEquals(38.5449, a.lat!!, 1e-6)
        assertEquals(-45.0, a.heading!!, 1e-6)
        assertNull(a.panoId)
        val p = MapLinkParser.parse("https://www.google.com/maps/@?api=1&map_action=pano&pano=AbCdEfGhIjKlMnOpQrStUv")!!
        assertTrue(p.streetView)
        assertEquals("AbCdEfGhIjKlMnOpQrStUv", p.panoId)
        assertNull(p.lat)
        val c = MapLinkParser.parse("http://maps.google.com/maps?q=&layer=c&cbll=38.5449,-121.7405&cbp=12,210,0,0,0")!!
        assertTrue(c.streetView)
        assertEquals(-121.7405, c.lng!!, 1e-6)
        assertEquals(210.0, c.heading!!, 1e-6)
    }

    @Test fun `a directions link is never taken for Street View`() {
        val d = MapLinkParser.parse("https://www.google.com/maps/dir/?api=1&destination=38.5449,-121.7405")!!
        assertEquals(false, d.streetView)
        assertTrue(d.directions)
    }
}
