package app.vela.core.data.google

import app.vela.core.VelaConfig
import app.vela.core.config.Calibration
import app.vela.core.data.google.BrowserHeaders.browserHeaders
import app.vela.core.data.google.BrowserHeaders.browserXhrHeaders
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the scrape's browser identity. The failure this exists to prevent is SILENT: OkHttp
 * throws on an illegal header value at request-build time, inside `runCatching` blocks that
 * swallow it, so one bad character in a pushed `userAgent` would kill every scrape with no crash
 * and no log.
 */
class BrowserHeadersTest {

    // ---- brands ----------------------------------------------------------------------------

    @Test fun `the brand list parses in order and the major matches the ua`() {
        val brands = BrowserHeaders.brands(VelaConfig.SEC_CH_UA)
        assertEquals(listOf("Chromium", "Google Chrome", "Not A(Brand"), brands.map { it.name })
        assertEquals(BrowserHeaders.chromeMajor(VelaConfig.USER_AGENT), brands[0].major)
        assertEquals(brands[0].major, brands[1].major)
        assertTrue(BrowserHeaders.brands("garbage").isEmpty())
    }

    @Test fun `secChUaFor reproduces real chrome headers`() {
        // Captured from real Chrome releases.
        assertEquals("\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"", BrowserHeaders.secChUaFor(120))
        assertEquals("\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"", BrowserHeaders.secChUaFor(124))
        assertEquals("\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"", BrowserHeaders.secChUaFor(130))
    }

    @Test fun `the compiled hint is the one chrome sends for the compiled ua`() {
        val major = BrowserHeaders.chromeMajor(VelaConfig.USER_AGENT)!!.toInt()
        assertEquals(BrowserHeaders.secChUaFor(major), VelaConfig.SEC_CH_UA)
    }

    @Test fun `xhr headers carry the network hints google asks for and the document fetch does not`() {
        val xhr = Request.Builder().url("https://www.google.com/x").browserXhrHeaders(VelaConfig.USER_AGENT, VelaConfig.SEC_CH_UA, "https://www.google.com/maps/").build()
        assertEquals("10", xhr.header("Downlink"))
        assertEquals("50", xhr.header("RTT"))
        val doc = Request.Builder().url("https://www.google.com/maps").browserHeaders(VelaConfig.USER_AGENT, VelaConfig.SEC_CH_UA).build()
        assertNull(doc.header("Downlink"))
    }

    // ---- sanitize --------------------------------------------------------------------------

    @Test fun `a normal chrome ua passes through unchanged`() {
        assertEquals(VelaConfig.USER_AGENT, BrowserHeaders.sanitize(VelaConfig.USER_AGENT))
    }

    @Test fun `null and blank are rejected`() {
        assertNull(BrowserHeaders.sanitize(null))
        assertNull(BrowserHeaders.sanitize(""))
        assertNull(BrowserHeaders.sanitize("   "))
    }

    @Test fun `an interior newline is rejected - this is the one that would brick the scrape`() {
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0\r\nX-Injected: 1"))
        assertNull(BrowserHeaders.sanitize("Moz\nilla/5.0"))
        assertNull(BrowserHeaders.sanitize("Moz\rilla/5.0"))
    }

    @Test fun `a trailing newline is recovered, not rejected`() {
        // The likeliest artifact of a hand-edited JSON bundle. Trimming it back to a valid UA beats
        // silently falling back to the stale compiled default — which is what the remote channel
        // exists to avoid. Interior control characters are a different case (above).
        assertEquals("Mozilla/5.0", BrowserHeaders.sanitize("Mozilla/5.0\n"))
        assertEquals("Mozilla/5.0", BrowserHeaders.sanitize("Mozilla/5.0\r\n"))
    }

    @Test fun `control characters and tabs are rejected`() {
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0\u0000"))
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0\u0007"))
        assertNull(BrowserHeaders.sanitize("Mozilla\t5.0"))
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0\u007F"))
    }

    @Test fun `interior non-ascii is rejected`() {
        // OkHttp's accepted range is 0x20..0x7E; anything else throws at build time.
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0 (\u00DCnicode)"))
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0 \u4E2D Safari"))
        // NBSP mid-string: invisible in an editor, fatal in a header.
        assertNull(BrowserHeaders.sanitize("Mozilla/5.0\u00A0Safari"))
    }

    @Test fun `trailing NBSP is trimmed like any other space`() {
        // Kotlin's trim treats NBSP as whitespace, so this lands in the recoverable bucket.
        assertEquals("Mozilla/5.0", BrowserHeaders.sanitize("Mozilla/5.0\u00A0"))
    }

    @Test fun `an absurdly long value is rejected`() {
        assertNull(BrowserHeaders.sanitize("M".repeat(401)))
        assertNotNull(BrowserHeaders.sanitize("M".repeat(400)))
    }

    @Test fun `surrounding whitespace is trimmed, not rejected`() {
        assertEquals("Mozilla/5.0", BrowserHeaders.sanitize("  Mozilla/5.0  "))
    }

    @Test fun `every sanitized value is actually sendable`() {
        // The whole point: a value that survives sanitize must never throw inside OkHttp.
        listOf(VelaConfig.USER_AGENT, VelaConfig.SEC_CH_UA, VelaConfig.VELA_UA, "M".repeat(400))
            .forEach { raw ->
                val ok = BrowserHeaders.sanitize(raw)
                assertNotNull("should sanitize: $raw", ok)
                Request.Builder().url("https://www.google.com/").header("User-Agent", ok!!).build()
            }
    }

    // ---- header coherence ------------------------------------------------------------------

    private fun headers(build: Request.Builder.() -> Request.Builder) =
        Request.Builder().url("https://www.google.com/maps/").run(build).build().headers

    @Test fun `a navigation carries the full client-hint and fetch-metadata set`() {
        val h = headers { browserHeaders(VelaConfig.USER_AGENT, VelaConfig.SEC_CH_UA) }
        assertEquals(VelaConfig.USER_AGENT, h["User-Agent"])
        assertEquals(VelaConfig.SEC_CH_UA, h["Sec-CH-UA"])
        assertEquals("?0", h["Sec-CH-UA-Mobile"])
        assertEquals("\"Windows\"", h["Sec-CH-UA-Platform"])
        assertEquals("document", h["Sec-Fetch-Dest"])
        assertEquals("navigate", h["Sec-Fetch-Mode"])
        assertEquals("none", h["Sec-Fetch-Site"])
    }

    @Test fun `a navigation sends no Referer - a real first hit has none`() {
        assertNull(headers { browserHeaders(VelaConfig.USER_AGENT, VelaConfig.SEC_CH_UA) }["Referer"])
    }

    @Test fun `an in-page RPC looks like an xhr, not a navigation`() {
        val h = headers {
            browserXhrHeaders(VelaConfig.USER_AGENT, VelaConfig.SEC_CH_UA, MAPS_REFERER)
        }
        assertEquals("empty", h["Sec-Fetch-Dest"])
        assertEquals("cors", h["Sec-Fetch-Mode"])
        assertEquals("same-origin", h["Sec-Fetch-Site"])
        assertEquals(MAPS_REFERER, h["Referer"])
        assertEquals("*/*", h["Accept"])
    }

    // ---- the invariant the comments promise -------------------------------------------------

    @Test fun `the client hint advertises the same major version as the ua string`() {
        // A hint claiming a different version than the UA is worse than sending no hint. The two are
        // pushed together in the calibration bundle precisely so they can't drift apart; this locks
        // the compiled pair so a careless bump of one is caught here.
        val uaMajor = Regex("""Chrome/(\d+)""").find(VelaConfig.USER_AGENT)?.groupValues?.get(1)
        assertNotNull("no Chrome version in the compiled UA", uaMajor)
        assertTrue(
            "Sec-CH-UA ${VelaConfig.SEC_CH_UA} does not advertise Chrome $uaMajor",
            VelaConfig.SEC_CH_UA.contains("\"$uaMajor\""),
        )
    }

    @Test fun `calibration defaults mirror the compiled constants`() {
        assertEquals(VelaConfig.USER_AGENT, Calibration.DEFAULT.userAgent)
        assertEquals(VelaConfig.SEC_CH_UA, Calibration.DEFAULT.secChUa)
    }

    @Test fun `the community identifier is not a browser string`() {
        // Community services (FOSSGIS OSRM, Nominatim, Photon, Overpass) must get a contactable UA.
        assertTrue(VelaConfig.VELA_UA.startsWith("VelaMaps/"))
        assertTrue(VelaConfig.VELA_UA.contains("github.com/PimpinPumpkin/Vela"))
        assertTrue("must not masquerade as a browser", !VelaConfig.VELA_UA.contains("Mozilla"))
    }
}
