package app.vela.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** The size of the light and stop sign glyphs on the car map (issue #844). */
class CarControlGlyphTest {
    @Test fun `a twentieth of the short side at the drive's zoom`() {
        assertEquals(36f, CarMapRenderer.controlGlyphPx(720, 15.5), 0.01f)
        assertEquals(24f, CarMapRenderer.controlGlyphPx(480, 15.5), 0.01f)
    }

    @Test fun `it grows as the map zooms in and shrinks as it zooms out`() {
        assertEquals(46.8f, CarMapRenderer.controlGlyphPx(720, 17.0), 0.01f)
        assertEquals(46.8f, CarMapRenderer.controlGlyphPx(720, 18.5), 0.01f)
        assertEquals(25.2f, CarMapRenderer.controlGlyphPx(720, 14.0), 0.01f)
        assertEquals(30.6f, CarMapRenderer.controlGlyphPx(720, 14.75), 0.01f)
    }

    @Test fun `never smaller than 16 px or larger than 56`() {
        assertEquals(16f, CarMapRenderer.controlGlyphPx(300, 13.5), 0.01f)
        assertEquals(56f, CarMapRenderer.controlGlyphPx(1440, 17.0), 0.01f)
    }
}
