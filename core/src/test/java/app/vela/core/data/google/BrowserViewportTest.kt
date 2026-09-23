package app.vela.core.data.google

import app.vela.core.config.Calibration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewportTest {
    @After fun reset() { BrowserViewport.set(1024, 768) }

    @Test fun `the shipped search template gets this install's window and panel rectangles`() {
        val out = BrowserViewport.apply(Calibration.DEFAULT.searchPb, 1536, 730)
        assertTrue(out.contains("!3m2!1i1536!2i730"))
        assertTrue(out.contains("!2m2!1i530!2i730!1m6!1m2!1i1486!2i0!2m2!1i1536!2i730"))
        assertTrue(out.contains("!1m2!1i0!2i710!2m2!1i1536!2i730"))
        assertFalse(out.contains("1i1024"))
        assertFalse(out.contains("2i768"))
    }

    @Test fun `the shipped directions template gets this install's window`() {
        val out = BrowserViewport.apply(Calibration.DEFAULT.directionsPb, 1920, 945)
        assertTrue(out.contains("!3m2!1i1920!2i945"))
        assertTrue(out.contains("!20m28!1m6!1m2!1i0!2i0!2m2!1i530!2i945!1m6!1m2!1i1870!2i0"))
        assertFalse(out.contains("1i1024"))
        assertFalse(out.contains("2i768"))
    }

    @Test fun `a template without the captured shapes is left alone`() {
        val odd = "!1m3!1d1000!3m2!1i800!2i600!4f13.1"
        assertEquals(odd, BrowserViewport.apply(odd, 1920, 945))
    }

    @Test fun `every choice is a desktop window`() {
        BrowserViewport.CHOICES.forEach { (w, h) -> assertTrue(w >= 1280 && h in 600..1400 && w > h) }
    }
}
