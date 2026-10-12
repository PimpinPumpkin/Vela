package app.vela.core.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VelaLinksTest {
    @After fun reset() {
        VelaLinks.overrides = emptyMap()
        VelaLinks.compiled = emptyMap()
    }

    @Test fun `defaults derive the files from their bases`() {
        assertEquals("https://github.com/PimpinPumpkin/Vela/releases/download/obf-regions/obf-manifest.json", VelaLinks.obfManifest())
        assertEquals("https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/calibration.json", VelaLinks.calibration())
        assertEquals(VelaLinks.calibration() + ".sig", VelaLinks.calibrationSig())
        assertEquals("https://pimpinpumpkin.github.io/Vela/fonts", VelaLinks.mapFonts())
        assertEquals("https://velamaps.org", VelaLinks.site())
        // The website and the Pages address are separate keys: moving the site leaves the fonts.
        VelaLinks.overrides = mapOf(VelaLinks.SITE to "https://vela.example")
        assertEquals("https://pimpinpumpkin.github.io/Vela/fonts", VelaLinks.mapFonts())
        assertEquals("https://github.com/PimpinPumpkin/Vela/releases/download/voices/x.tar.bz2", VelaLinks.release("voices", "x.tar.bz2"))
        assertEquals("https://api.github.com/repos/PimpinPumpkin/Vela/releases/latest", VelaLinks.api("/releases/latest"))
    }

    @Test fun `a pushed base moves every file under it, and a pushed key moves that one`() {
        VelaLinks.overrides = mapOf(VelaLinks.RELEASES to "https://github.com/NewName/Vela/releases/download")
        assertEquals("https://github.com/NewName/Vela/releases/download/obf-regions/obf-manifest.json", VelaLinks.obfManifest())
        assertEquals("https://github.com/NewName/Vela/releases/download/fuel-gb/", VelaLinks.fuelGb())
        assertEquals("https://github.com/PimpinPumpkin/Vela", VelaLinks.repo())
        VelaLinks.overrides = mapOf(VelaLinks.RAW to "https://example.org/vela", VelaLinks.PRIVACY to "https://example.org/privacy")
        assertEquals("https://example.org/vela/calibration.json", VelaLinks.calibration())
        assertEquals("https://example.org/privacy", VelaLinks.privacy())
    }

    @Test fun `a build's own link beats the default and loses to a pushed one`() {
        VelaLinks.compiled = mapOf(VelaLinks.OBF_MANIFEST to "http://127.0.0.1:8099/obf-manifest.json", VelaLinks.CELLS_MANIFEST to "")
        assertEquals("http://127.0.0.1:8099/obf-manifest.json", VelaLinks.obfManifest())
        assertEquals(VelaLinks.default(VelaLinks.CELLS_MANIFEST), VelaLinks.cellsManifest())
        VelaLinks.overrides = mapOf(VelaLinks.OBF_MANIFEST to "https://example.org/obf.json")
        assertEquals("https://example.org/obf.json", VelaLinks.obfManifest())
    }

    @Test fun `a bundle may push https links under known names only`() {
        assertTrue(VelaLinks.acceptable(mapOf(VelaLinks.SITE to "https://vela.example")))
        assertFalse(VelaLinks.acceptable(mapOf(VelaLinks.SITE to "http://vela.example")))
        assertFalse(VelaLinks.acceptable(mapOf("somethingElse" to "https://vela.example")))
        assertFalse(VelaLinks.acceptable(mapOf(VelaLinks.SITE to "https://vela.example/a b")))
        val c = CalibrationStore.parseBundle("""{"version": 99, "links": {"site": "https://vela.example", "repo": ""}}""")!!
        assertEquals(mapOf("site" to "https://vela.example"), c.links)
    }
}
