package app.vela.core

import app.vela.core.data.google.StreetViewParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the SingleImageSearch field indices against the real response shape (SF capture
 *  2026-07-15, trimmed of the neighbour graph + depth map). If Google shifts a field, this
 *  fails before the device does. The address/copyright/position all live INSIDE the pano
 *  node (root[1]) - the classic off-by-one trap. */
class StreetViewParserTest {
    private val response = "/**/cb && cb( " +
        """[[0],[[1],[2,"UiZ-8FRkJwHjR3mwzBTPmg"],[2,2,[8192,16384],""" +
        """[[[[256,512]],[[512,1024]],[[1024,2048]],[[2048,4096]],[[4096,8192]],[[8192,16384]]],[512,512]],""" +
        """null,null,null,null,null,"UiZ-8FRkJwHjR3mwzBTPmg"],""" +
        """[null,null,[["San Francisco, California","en"]]],""" +
        """[[[["© 2026 Google"]]]],""" +
        """[[[1],[[null,null,37.77487374168061,-122.4194003523425],""" +
        """[17.42217636108398,null,-14.82052898406982],""" +
        """[230.4813842773438,80.82630920410156,2.480184555053711],null,"US"],null]]]] """ +
        ")"

    @Test fun parsesPanoAndGeometry() {
        val pano = StreetViewParser.parse(response, 37.7749, -122.4194)!!
        assertEquals("UiZ-8FRkJwHjR3mwzBTPmg", pano.panoId)
        assertEquals(512, pano.tileSize)
        assertEquals(6, pano.maxZoom) // six pyramid levels z0..z5
        assertEquals("San Francisco, California", pano.addressLabel)
        assertEquals("© 2026 Google", pano.copyright)
        assertEquals(230.48, pano.headingDeg, 0.1)
        assertEquals(37.7748737, pano.lat, 1e-5)
        assertEquals(-122.4194003, pano.lng, 1e-5)
    }

    @Test fun noImageryReturnsNull() {
        assertNull(StreetViewParser.parse("/**/cb && cb( [[5]] )", 0.0, 0.0))
        assertNull(StreetViewParser.parse("garbage", 0.0, 0.0))
    }

    @Test fun toleratesMissingTail() {
        // Only the pano id is required; a truncated tail still yields a usable pano.
        val minimal = """/**/cb && cb( [[0],[[1],[2,"AbCdEfGhIjKlMnOpQrStUv"]]] )"""
        val pano = StreetViewParser.parse(minimal, 1.0, 2.0)!!
        assertEquals("AbCdEfGhIjKlMnOpQrStUv", pano.panoId)
        assertEquals(1.0, pano.lat, 1e-9) // falls back to the query point
        assertTrue(pano.tileSize == 512)
    }
}
