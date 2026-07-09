package app.vela.core

import app.vela.core.data.google.parse.EntityListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture captured live 2026-07-08 from the public "Import Me to Vela!" test list
 *  (issue #1), trimmed to the fields the parser reads. */
class EntityListParserTest {

    private val payload = """)]}'
[[["IP3Ygq3G8_vrjzxUTTtMspZKVmNcrQ",1,[[1]],1,1],4,[2,1,"https://www.google.com/maps/placelists/list/IP3Ygq3G8_vrjzxUTTtMspZKVmNcrQ"],["Keron Cyst","https://example.invalid/avatar","109276248602198135011"],"Import Me to Vela!","Shared test list of random locations",null,null,[[null,[null,null,"",null,"2160 Linden Dr SE, Cedar Rapids, IA 52403",[null,null,41.9923686,-91.6393313],["-8654527782550677995","-8127173087487969012"],"/m/0d98kc"],"Brucemore","I don't wanna go here alone!",null,null,null,[],[[1],["-8654527782550677995","-8127173087487969012"]],[1783559373,332117000],[1783559373,332117000],null,["Keron Cyst","https://example.invalid/avatar"]],[null,[null,null,"Cedar Rapids, IA",null,"Cedar Rapids, IA",[null,null,41.9778795,-91.6656232],["-8654687603621150127","6191992379313413568"],"/m/0t0n5"],"Cedar Rapids","",null,null,null,[],[[1],["-8654687603621150127","6191992379313413568"]],[1783559332,606310000],[1783559332,606310000],null,["Keron Cyst","https://example.invalid/avatar"]]]]]"""

    @Test
    fun `parses title, description, author and every place with its note`() {
        val list = EntityListParser.parse(payload)!!
        assertEquals("Import Me to Vela!", list.title)
        assertEquals("Shared test list of random locations", list.description)
        assertEquals("Keron Cyst", list.author)
        assertEquals(2, list.places.size)

        val brucemore = list.places[0]
        assertEquals("Brucemore", brucemore.name)
        assertEquals("2160 Linden Dr SE, Cedar Rapids, IA 52403", brucemore.address)
        assertEquals(41.9923686, brucemore.location.lat, 1e-9)
        assertEquals(-91.6393313, brucemore.location.lng, 1e-9)
        assertEquals("I don't wanna go here alone!", brucemore.savedNote)
        // decimal signed-int64 pair → two's-complement hex feature id
        assertEquals("0x87e4f0d5de515615:0x8f367b22f42a390c", brucemore.featureId)

        val cr = list.places[1]
        assertEquals("Cedar Rapids", cr.name)
        assertNull(cr.savedNote) // blank note → null, so the sheet shows nothing
    }

    @Test
    fun `garbage and non-list payloads return null, never throw`() {
        assertNull(EntityListParser.parse(")]}'\n[[]]"))
        assertNull(EntityListParser.parse("<!doctype html><html>consent wall</html>"))
        assertNull(EntityListParser.parse(""))
    }
}
