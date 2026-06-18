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
}
