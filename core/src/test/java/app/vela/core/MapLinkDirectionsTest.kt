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

    @Test fun `a plus code in a classic link keeps its plus`() {
        // %2B is the code's own "+"; the "+" between words are spaces.
        val l = MapLinkParser.parse("https://maps.google.com/maps?daddr=849VCWC8%2BR9+Mountain+View")!!
        assertEquals("849VCWC8+R9 Mountain View", l.query)
        val chained = MapLinkParser.parse("https://maps.google.com/maps?saddr=Davis,+CA&daddr=Sacramento,+CA+to:849VCWC8%2BR9+Mountain+View")!!
        assertEquals("849VCWC8+R9 Mountain View", chained.query)
    }

    @Test fun `a planned trip keeps its stops and each place's own coordinate`() {
        // The desktop address bar's form: every place in the path, its coordinate in the blob.
        val l = MapLinkParser.parse(
            "https://www.google.com/maps/dir/Sacramento,+California/Davis,+California/San+Francisco,+California/" +
                "@38.2,-122.0,9z/am=t/data=!4m20!4m19" +
                "!1m5!1m1!1s0x809ac672b28397f9:0x921f6aaa74197fdb!2m2!1d-121.4943996!2d38.5815719" +
                "!1m5!1m1!1s0x808529999495543f:0xed7bb8fb4c8510c0!2m2!1d-121.7405167!2d38.5449065" +
                "!1m5!1m1!1s0x80859a6d00690021:0x4a501367f076adff!2m2!1d-122.4194155!2d37.7749295!3e0" +
                "?entry=ttu&g_ep=EgoyMDI2",
        )!!
        assertTrue(l.directions)
        assertEquals("San Francisco, California", l.query)
        assertEquals(37.7749295, l.lat!!, 1e-6); assertEquals(-122.4194155, l.lng!!, 1e-6)
        assertEquals("Sacramento, California", l.origin!!.query)
        assertEquals(38.5815719, l.origin!!.lat!!, 1e-6)
        assertEquals(1, l.stops.size)
        assertEquals("Davis, California", l.stops[0].query)
        assertEquals(38.5449065, l.stops[0].lat!!, 1e-6); assertEquals(-121.7405167, l.stops[0].lng!!, 1e-6)
        assertEquals(TravelMode.DRIVE, l.mode)
    }

    @Test fun `stops survive without a blob, and a blob that lists other places is ignored`() {
        val plain = MapLinkParser.parse("https://www.google.com/maps/dir/Sacramento/Davis/Vacaville/San+Francisco/")!!
        assertEquals(listOf("Davis", "Vacaville"), plain.stops.map { it.query })
        assertNull(plain.stops[0].lat); assertNull(plain.lat)
        // A blob with a place for each of the path's, the second one empty: the first gets its pin.
        val half = MapLinkParser.parse(
            "https://www.google.com/maps/dir/Sacramento/Davis/data=!4m8!4m7!1m5!1m1!1s0x1:0x2!2m2!1d-121.4944!2d38.5816!1m0!3e2",
        )!!
        assertEquals(38.5816, half.origin!!.lat!!, 1e-6)
        assertNull(half.lat)
        assertEquals(TravelMode.WALK, half.mode)
        // Two places in the path, one in the blob: which is which is unknown, so names only.
        val odd = MapLinkParser.parse("https://www.google.com/maps/dir/Sacramento/Davis/data=!4m7!4m6!1m5!1m1!1s0x1:0x2!2m2!1d-121.7405!2d38.5449")!!
        assertNull(odd.lat); assertNull(odd.origin!!.lat)
    }

    @Test fun `the blob's places are read in order, an empty one as none`() {
        val pins = MapLinkParser.dirPins("https://www.google.com/maps/dir/A/B/C/data=!4m14!4m13!1m5!1m1!1s0x1:0x2!2m2!1d-121.5!2d38.5!1m0!1m5!1m1!1s0x3:0x4!2m2!1d-122.4!2d37.7!3e0")
        assertEquals(3, pins.size)
        assertEquals(38.5, pins[0]!!.first, 1e-9); assertEquals(-121.5, pins[0]!!.second, 1e-9)
        assertNull(pins[1])
        assertEquals(37.7, pins[2]!!.first, 1e-9)
        assertTrue(MapLinkParser.dirPins("https://www.google.com/maps/dir/A/B/").isEmpty())
    }

    @Test fun `classic and Maps URLs links keep their stops too`() {
        val classic = MapLinkParser.parse("https://maps.google.com/maps?saddr=Sacramento&daddr=Davis,+CA+to:Vacaville+to:San+Francisco")!!
        assertEquals("San Francisco", classic.query)
        assertEquals(listOf("Davis, CA", "Vacaville"), classic.stops.map { it.query })
        val api = MapLinkParser.parse("https://www.google.com/maps/dir/?api=1&origin=Sacramento&destination=San+Francisco&waypoints=Davis%7C38.3566,-121.9877")!!
        assertEquals(2, api.stops.size)
        assertEquals("Davis", api.stops[0].query)
        assertEquals(38.3566, api.stops[1].lat!!, 1e-6)
    }

    @Test fun `a whole Maps address is a link, a sentence that mentions one is not`() {
        assertTrue(MapLinkParser.isMapsUrl("https://www.google.com/maps/dir/Sacramento/Davis/"))
        assertTrue(MapLinkParser.isMapsUrl("google.co.uk/maps/place/Big+Ben/@51.5007,-0.1246,17z"))
        assertTrue(MapLinkParser.isMapsUrl("https://maps.google.com/maps?saddr=A&daddr=B"))
        assertTrue(MapLinkParser.isMapsUrl("https://www.google.com/maps?q=coffee"))
        assertFalse(MapLinkParser.isMapsUrl("see https://www.google.com/maps/dir/Sacramento/Davis/"))
        assertFalse(MapLinkParser.isMapsUrl("https://www.google.com/search?q=maps"))
        assertFalse(MapLinkParser.isMapsUrl("google maps"))
    }
}
