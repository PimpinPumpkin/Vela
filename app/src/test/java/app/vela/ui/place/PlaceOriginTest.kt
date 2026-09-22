package app.vela.ui.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceOriginTest {
    @Test fun `ids name their source`() {
        assertEquals(PlaceOrigin(PlaceOrigin.Kind.OVERTURE), PlaceOrigin.of("overture:08f28d4a3b1c2d3e"))
        assertEquals(PlaceOrigin(PlaceOrigin.Kind.ATP, detail = "chevron_us"), PlaceOrigin.of("overture:atp:chevron_us:1234"))
        assertEquals(
            PlaceOrigin(PlaceOrigin.Kind.OSM, detail = "n4521", osmUrl = "https://www.openstreetmap.org/node/4521"),
            PlaceOrigin.of("overture:osm:n4521"),
        )
        assertEquals("https://www.openstreetmap.org/way/77", PlaceOrigin.of("overture:osm:w77")?.osmUrl)
        assertEquals(PlaceOrigin(PlaceOrigin.Kind.OSM), PlaceOrigin.of("poi:-12345"))
    }

    @Test fun `a google listing has no source line`() {
        assertNull(PlaceOrigin.of("0x549005e70e370069:0x34773bd0dd05b5"))
        assertNull(PlaceOrigin.of("ChIJ_UrUg8p9j4ARp1l14ue8nCk"))
    }
}
