package app.vela.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestCalibrationTest {
    @Test fun `a remote suggest path merges over the compiled ones`() {
        val c = CalibrationStore.parseBundle("""{"version": 99, "suggestPaths": {"title": [9, 9]}}""")!!
        assertEquals(listOf(9, 9), c.suggestPaths["title"])
        assertEquals(Calibration.DEFAULT_SUGGEST_PATHS["lat"], c.suggestPaths["lat"])
        assertEquals(Calibration.DEFAULT_SUGGEST_ENDPOINT, c.suggestEndpoint)
    }

    @Test fun `a remote suggest endpoint and template are adopted`() {
        val c = CalibrationStore.parseBundle(
            """{"version": 99, "suggestEndpoint": "https://www.google.com/s?tbm=map&x=1", "suggestPb": "!1d{SPAN}"}""",
        )!!
        assertEquals("https://www.google.com/s?tbm=map&x=1", c.suggestEndpoint)
        assertEquals("!1d{SPAN}", c.suggestPb)
    }
}
