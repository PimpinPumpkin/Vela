package app.vela.core

import app.vela.core.data.google.StreetViewParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the SingleImageSearch field indices against the real response shape (SF capture
 *  2026-07-15, trimmed). Address/copyright/position/date/history all live INSIDE the pano
 *  node (root[1]) - the classic off-by-one trap; copyright is one level deeper again. */
class StreetViewParserTest {
    // Built to the real nesting: pano node with tile pyramid, address, copyright, a 3-pano local
    // graph (self + a same-spot 2022 capture + a walkable neighbour ~11 m north), a history entry
    // referencing the 2022 pano, and this pano's own capture date (May 2025) at [1][6][7].
    private val response = "/**/cb && cb( " +
        """[[0],[[1],[2,"UiZ-8FRkJwHjR3mwzBTPmg"],[2,2,[8192,16384],""" +
        """[[[[256,512]],[[512,1024]],[[1024,2048]],[[2048,4096]],[[4096,8192]],[[8192,16384]]],[512,512]],""" +
        """null,null,null,null,null,"UiZ-8FRkJwHjR3mwzBTPmg"],""" +
        """[null,null,[["San Francisco, California","en"]]],[[[["© 2026 Google"]]]],""" +
        """[[[1],[[null,null,37.77487,-122.4194],[17.42,null,-14.82],[230.48,80.0,2.0],null,"US"],null,""" +
        """[[[[2,"UiZ-8FRkJwHjR3mwzBTPmg"],null,[[null,null,37.77487,-122.4194],[17.0,null,-14.0],[230.0,80.0,2.0]]],""" +
        """[[2,"HISTpano0000000000000A"],null,[[null,null,37.77488,-122.4194],[17.0,null,-14.0],[230.0,80.0,2.0]]],""" +
        """[[2,"WALKpano0000000000000B"],null,[[null,null,37.77497,-122.4194],[17.0,null,-14.0],[230.0,80.0,2.0]]]]],""" +
        """null,null,null,null,[[1,[2022,4],null,null,null,2]]]],""" +
        """[null,null,null,null,null,null,null,[2025,5]]]] """ +
        ")"

    @Test fun parsesPanoAndGeometry() {
        val pano = StreetViewParser.parse(response, 37.7749, -122.4194)!!
        assertEquals("UiZ-8FRkJwHjR3mwzBTPmg", pano.panoId)
        assertEquals(512, pano.tileSize)
        assertEquals(6, pano.maxZoom)
        // Per-level (width, height) - the tile loader sizes its grid from these; old captures'
        // pyramids are 416-based, so assuming 512·2^z black-banded historical panos.
        assertEquals(6, pano.levelDims.size)
        assertEquals(512 to 256, pano.levelDims.first())
        assertEquals(16384 to 8192, pano.levelDims.last())
        assertEquals("San Francisco, California", pano.addressLabel)
        assertEquals("© 2026 Google", pano.copyright)
        assertEquals(230.48, pano.headingDeg, 0.1)
    }

    @Test fun parsesCaptureDate() {
        val pano = StreetViewParser.parse(response, 37.7749, -122.4194)!!
        assertEquals(2025, pano.captureYear)
        assertEquals(5, pano.captureMonth)
    }

    @Test fun walkableNeighboursExcludeSameSpot() {
        val pano = StreetViewParser.parse(response, 37.7749, -122.4194)!!
        // The 2022 pano is ~1 m away (same spot) so it must NOT be a walk target; the ~11 m
        // north one must be.
        assertEquals(1, pano.neighbors.size)
        assertEquals("WALKpano0000000000000B", pano.neighbors[0].panoId)
        assertTrue(pano.neighbors[0].distanceM in 5.0..20.0)
        // Bearing is roughly north.
        assertTrue(pano.neighbors[0].bearingDeg < 20.0 || pano.neighbors[0].bearingDeg > 340.0)
    }

    @Test fun historyResolvesDatesNewestFirst() {
        val pano = StreetViewParser.parse(response, 37.7749, -122.4194)!!
        assertEquals(2, pano.history.size)
        assertEquals("UiZ-8FRkJwHjR3mwzBTPmg", pano.history[0].panoId) // May 2025 leads
        assertEquals(2025, pano.history[0].year)
        assertEquals("HISTpano0000000000000A", pano.history[1].panoId) // Apr 2022
        assertEquals(2022, pano.history[1].year)
    }

    @Test fun noImageryReturnsNull() {
        assertNull(StreetViewParser.parse("/**/cb && cb( [[5]] )", 0.0, 0.0))
        assertNull(StreetViewParser.parse("garbage", 0.0, 0.0))
    }

    @Test fun streetOfDropsHouseNumberButKeepsOrdinal() {
        assertEquals("5th st", StreetViewParser.streetOf("2005 5th St, Sacramento, CA"))
        assertEquals("5th st", StreetViewParser.streetOf("5th St")) // bare street, no number
        assertEquals("4th st", StreetViewParser.streetOf("2001 4th St"))
        assertEquals("main st", StreetViewParser.streetOf("120 Main Street")) // Street -> st
        assertEquals("1st st", StreetViewParser.streetOf("42B 1st St")) // house 42B dropped, ordinal kept
        assertEquals("s 12th st", StreetViewParser.streetOf("1107 S 12th St")) // directional kept
    }

    @Test fun svThumbExtractsGooglesOwnPanoAndYaw() {
        // The search entry carries Google's exact Street View pick as a thumbnail URL - panoid + yaw.
        val entry = kotlinx.serialization.json.Json.parseToJsonElement(
            """[null,[null,"https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=USSGIQe-w8dTe9yhxA6SzQ&cb_client=search.gws-prod.gps&w=408&h=240&yaw=221.14272&pitch=0&thumbfov=100",3]]""",
        )
        val sv = app.vela.core.data.google.parse.SearchParser.svThumb(entry)
        assertEquals("USSGIQe-w8dTe9yhxA6SzQ", sv?.first)
        assertEquals(221.14272, sv?.second ?: 0.0, 1e-6)
        // No thumbnail in the entry → null, so the heuristic fallback runs.
        assertNull(app.vela.core.data.google.parse.SearchParser.svThumb(
            kotlinx.serialization.json.Json.parseToJsonElement("""[null,["no pano here"]]"""),
        ))
    }

    @Test fun streetOfRejectsNonStreets() {
        // Needs a suffix or ordinal - a bare city, neighbourhood, or business name is NOT a street,
        // so it can't shadow the real street (which lives in a different field for address results).
        assertNull(StreetViewParser.streetOf("Sacramento, California"))
        assertNull(StreetViewParser.streetOf("Midtown, Sacramento"))
        assertNull(StreetViewParser.streetOf("Joe's Cafe"))
        assertNull(StreetViewParser.streetOf(null))
    }

    @Test fun streetMatchesAcrossAddressForms() {
        // An alley/behind pano labelled only with the city must NOT match the address's street.
        assertFalse(StreetViewParser.streetMatches("Sacramento, California", "2005 5th St, Sacramento, CA"))
        // A pano on the address's own street matches, house numbers and suffix spelling aside.
        assertTrue(StreetViewParser.streetMatches("1933 5th St", "2005 5th St, Sacramento, CA"))
        assertTrue(StreetViewParser.streetMatches("50 Main St", "120 Main Street"))
        // A parallel street does not.
        assertFalse(StreetViewParser.streetMatches("2001 4th St", "2005 5th St"))
        assertFalse(StreetViewParser.streetMatches(null, "2005 5th St"))
    }
}
