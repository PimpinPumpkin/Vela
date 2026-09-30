package app.vela.core

import app.vela.core.data.MapLinkParser
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directions links other apps hand to a maps app (issue #632). */
class MapLinkDirectionsTest {

    @Test fun telegramDirectionsButton() {
        // Telegram's LocationActivity.openDirections, with and without a known position.
        val l = MapLinkParser.parse("http://maps.google.com/maps?saddr=38.550000,-121.760000&daddr=38.544900,-121.740500")!!
        assertTrue(l.directions)
        assertEquals(38.5449, l.lat!!, 1e-6); assertEquals(-121.7405, l.lng!!, 1e-6)
        assertEquals(38.55, l.origin!!.lat!!, 1e-6)
        val noStart = MapLinkParser.parse("http://maps.google.com/maps?saddr=&daddr=38.544900,-121.740500")!!
        assertTrue(noStart.directions); assertNull(noStart.origin)
    }

    @Test fun classicFormTakesTheLastStopAndTheMode() {
        val l = MapLinkParser.parse("https://maps.google.com/maps?saddr=Current+Location&daddr=Davis,+CA+to:1451+W+Covell+Blvd,+Davis&dirflg=w")!!
        assertEquals("1451 W Covell Blvd, Davis", l.query)
        assertNull(l.origin)
        assertEquals(TravelMode.WALK, l.mode)
    }

    @Test fun mapsUrlsApi() {
        val l = MapLinkParser.parse("https://www.google.com/maps/dir/?api=1&origin=Sacramento&destination=1451%20W%20Covell%20Blvd%2C%20Davis&travelmode=bicycling")!!
        assertTrue(l.directions)
        assertEquals("1451 W Covell Blvd, Davis", l.query)
        assertEquals("Sacramento", l.origin!!.query)
        assertEquals(TravelMode.BICYCLE, l.mode)
        val coords = MapLinkParser.parse("https://www.google.com/maps/dir/?api=1&destination=38.5449,-121.7405")!!
        assertEquals(38.5449, coords.lat!!, 1e-6); assertNull(coords.origin); assertNull(coords.mode)
    }

    @Test fun pathForm() {
        val l = MapLinkParser.parse("https://www.google.com/maps/dir/Sacramento,+CA/Davis,+CA/@38.55,-121.6,11z/data=!4m2!4m1!3e0")!!
        assertTrue(l.directions)
        assertEquals("Davis, CA", l.query); assertEquals("Sacramento, CA", l.origin!!.query)
        val fromHere = MapLinkParser.parse("https://www.google.com/maps/dir//38.5449,-121.7405/")!!
        assertNull(fromHere.origin); assertEquals(38.5449, fromHere.lat!!, 1e-6)
    }

    @Test fun googleNavigationIntent() {
        val l = MapLinkParser.parse("google.navigation:q=2000+Sutter+Pl,+Davis,+CA&mode=w")!!
        assertTrue(l.directions)
        assertEquals("2000 Sutter Pl, Davis, CA", l.query)
        assertEquals(TravelMode.WALK, l.mode)
        val ll = MapLinkParser.parse("google.navigation:ll=38.5449,-121.7405")!!
        assertEquals(-121.7405, ll.lng!!, 1e-6)
    }

    @Test fun placeAndSearchLinksAreNotDirections() {
        assertFalse(MapLinkParser.parse("https://www.google.com/maps/place/Foo/@38.5,-121.7,15z")!!.directions)
        assertFalse(MapLinkParser.parse("https://www.google.com/maps/search/coffee")!!.directions)
        assertFalse(MapLinkParser.parse("geo:38.5449,-121.7405")!!.directions)
        // A directions link with no destination is not a target at all.
        assertNull(MapLinkParser.parse("https://www.google.com/maps/dir/?api=1&origin=Davis"))
    }
}
