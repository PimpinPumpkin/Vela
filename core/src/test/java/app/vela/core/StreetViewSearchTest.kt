package app.vela.core

import app.vela.core.config.Calibration
import app.vela.core.data.google.StreetViewParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The POST lookup that replaced the GET Google switched off on 2026-10-05. The fixture is a
 *  captured reply for downtown Davis. */
class StreetViewSearchTest {
    private val reply = javaClass.getResource("/streetview/search_davis.json")!!.readText()

    @Test
    fun `the bare JSON reply parses into a pano near the asked point`() {
        val p = StreetViewParser.parse(reply, 38.5435, -121.7400)
        assertNotNull(p)
        assertEquals(22, p!!.panoId.length)
        assertTrue(kotlin.math.abs(p.lat - 38.5435) < 0.002 && kotlin.math.abs(p.lng + 121.7400) < 0.002)
        assertTrue(p.levelDims.isNotEmpty())
        assertTrue(p.neighbors.isNotEmpty())
    }

    @Test
    fun `an error reply is no pano`() {
        assertNull(StreetViewParser.parse("[[5,\"generic\",\"Search returned no images.\"]]", 38.5, -121.7))
        assertNull(StreetViewParser.parse("/**/cb && cb( [[5,\"generic\",\"GeoPhotoService.SingleImageSearch is decommissioned and turned off.\"]] )", 38.5, -121.7))
    }

    @Test
    fun `the request body is valid JSON once filled in`() {
        val body = Calibration.DEFAULT_STREETVIEW_SEARCH_BODY.replace("{LAT}", "38.5435000").replace("{LNG}", "-121.7400000").replace("{RADIUS}", "50")
        kotlinx.serialization.json.Json.parseToJsonElement(body)
    }
}
