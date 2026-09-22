package app.vela.core.data.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyless autocomplete envelope, captured live 2026-09-22 (a partial address typed far from the phone). */
class SuggestParserTest {
    private val body = """{"c":0,"d":")]}'\n[[\"459 ralston\",[[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,[[\"459 Ralston Street, San Francisco, CA\",null,null,null,1,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,\"BChIJ_UrUg8p9j4ARp1l14ue8nCk\\u003d\"],[\"459 Ralston Street\",[[0,3],[4,11]]],[\"San Francisco, CA\"],0,0,null,null,[null,38,[428]],null,null,null,[null,null,37.720744599999996,-122.46919609999999],null,[[\"0x808f7dca83d44afd:0x299cbce7e27559a7\",\"459 Ralston Street, San Francisco, CA\",null,[null,null,37.720744599999996,-122.46919609999999],0,null,null,null,0,null,\"/m/0d6lp\"]],[\"459 Ralston Street, San Francisco, CA\",\"0x808f7dca83d44afd:0x299cbce7e27559a7\",[null,null,37.720744599999996,-122.46919609999999]],null,null,null,null,null,[[0],[87]],null,null,null,null,null,null,null,null,null,null,null,null,null,\"US\",null,null,12,null,null,null,0]],[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,[[\"459 Ralston Avenue, Belmont, CA\",null,null,null,1],[\"459 Ralston Avenue\",[[0,3],[4,11]]],[\"Belmont, CA\"],0,0,null,null,[null,38,[443,428,600]],null,null,null,[null,null,37.512373499999995,-122.3041099],null,null,[\"459 Ralston Avenue, Belmont, CA\"],null,null,null,null,null,[[0],[87]],null,null,null,null,null,null,null,null,null,null,null,null,null,\"US\",null,null,12,null,null,null,0]],[null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,[[\"Starbucks\",null,null,null,1],[\"Starbucks\",[[0,9]]],[\"See locations\"],0,0,null,null,[null,38,[428]],null,null,null,null,null,null,[\"Starbucks\"],null,null,null,null,null,[[0],[87]],null,null,null,null,null,null,null,null,null,null,null,null,null,\"US\",null,null,12,null,null,null,0]]]],null,null,null,null,null,null,null]"}/*""*/"""

    @Test fun `address rows become places with feature ids and query rows stay queries`() {
        val r = SuggestParser.parse(body)
        assertEquals(listOf("Starbucks"), r.queries)
        assertEquals(2, r.places.size)
        val sf = r.places[0]
        assertEquals("459 Ralston Street", sf.name)
        assertEquals("San Francisco, CA", sf.address)
        assertEquals("0x808f7dca83d44afd:0x299cbce7e27559a7", sf.featureId)
        assertEquals(sf.featureId, sf.id)
        assertEquals(37.7207, sf.location.lat, 1e-4)
        assertEquals(-122.4692, sf.location.lng, 1e-4)
        // The second row carries no feature id block; the location still comes from column 11.
        val belmont = r.places[1]
        assertNull(belmont.featureId)
        assertEquals("459 Ralston Avenue", belmont.name)
        assertEquals("Belmont, CA", belmont.address)
        assertTrue(belmont.id.startsWith("suggest:"))
        assertEquals(37.5124, belmont.location.lat, 1e-4)
    }

    @Test fun `the app's request gets a second object after the tail and an echoed url`() {
        // The device body: the first object also carries the request URL, and a second object
        // with a token follows the comment tail. The last brace in the body is the wrong one.
        val device = body.replace("""}/*""*/""", ""","u":"https://www.google.com/s?q=459{}"}/*""*/{"c":0,"d":"","e":"tok{en}"}""")
        val r = SuggestParser.parse(device)
        assertEquals(2, r.places.size)
        assertEquals(listOf("Starbucks"), r.queries)
    }

    @Test fun `a broken envelope parses to nothing`() {
        assertEquals(0, SuggestParser.parse("").places.size)
        assertEquals(0, SuggestParser.parse("<html>blocked</html>").places.size)
        assertEquals(0, SuggestParser.parse("""{"c":0,"d":")]}'\n[]"}""").places.size)
        assertNull(SuggestParser.unwrap("nope"))
    }
}
