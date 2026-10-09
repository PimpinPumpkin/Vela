package app.vela.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drive camera's framing from the measured chrome ([NavFraming], [NavFramer]). Fixture: a
 *  1080 x 2340 px phone, 420 dpi (2.625) by default; its largest display size is 546 dpi (3.4125). */
class NavFramingTest {
    private val h = 2340.0
    private val w = 1080.0
    private val d0 = 2.625
    private val dLarge = 3.4125

    private fun puckFrac(f: NavFraming.Frame) = (1 + f.pad) / 2

    @Test fun `default size keeps the old frame`() {
        // Card bottom 250 dp, bar top 140 dp from the bottom, pill under the arrow 40 dp tall.
        val bottom = NavFraming.bottomEdge(w, d0, h - 140 * d0, false, 40 * d0, h - 236 * d0, 86 * d0, 101.0)
        val f = NavFraming.frame(h, d0, d0, 250 * d0, bottom, NavFraming.belowPuck(true, 40 * d0))
        assertEquals(NavFraming.DEFAULT_PAD, f.pad, 1e-9)
        assertEquals(0.0, f.zoomOffset, 1e-9)
    }

    @Test fun `a lane strip and a then tab at default size leave the zoom alone`() {
        val f = NavFraming.frame(h, d0, d0, 321 * d0, 0.0, 0.0)
        assertEquals(NavFraming.DEFAULT_PAD, f.pad, 1e-9)
        assertTrue("offset ${f.zoomOffset}", f.zoomOffset > -0.1)
    }

    @Test fun `large display size and text pull the zoom back`() {
        // The card capped at a third of the screen: about 257 dp from the top.
        val f = NavFraming.frame(h, dLarge, d0, 257 * dLarge, 0.0, 0.0)
        assertEquals(NavFraming.DEFAULT_PAD, f.pad, 1e-9)
        assertTrue("offset ${f.zoomOffset}", f.zoomOffset < -0.5 && f.zoomOffset > -1.0)
        // The card left at 45 percent of the screen: a bigger pull-back, within the bound.
        val big = NavFraming.frame(h, dLarge, d0, 0.455 * h, 0.0, 0.0)
        assertTrue(big.zoomOffset < f.zoomOffset)
        assertTrue(big.zoomOffset >= -NavFraming.MAX_ZOOM_OUT)
    }

    @Test fun `the arrow rises until the pill under it clears the bottom chrome`() {
        val barTop = 0.78 * h
        val pill = 60 * dLarge
        val below = NavFraming.belowPuck(true, pill)
        val f = NavFraming.frame(h, dLarge, d0, 257 * dLarge, barTop, below)
        val y = puckFrac(f) * h
        assertTrue("pad ${f.pad}", f.pad < NavFraming.DEFAULT_PAD)
        assertEquals(barTop - NavFraming.MARGIN_DP * dLarge, y + below, 1e-6)
    }

    @Test fun `the arrow never rises above its floor`() {
        val f = NavFraming.frame(h, dLarge, d0, 0.0, 0.5 * h, 300.0)
        assertEquals(NavFraming.MIN_PUCK_FRAC, puckFrac(f), 1e-9)
    }

    @Test fun `the speed box counts only once it reaches under the arrow`() {
        val barTop = h - 400
        val speedTop = h - 700
        // Default size: the box stays in the corner, the bar is the edge.
        assertEquals(barTop, NavFraming.bottomEdge(w, dLarge, barTop, false, 0.0, speedTop, 300.0, 101.0), 1e-9)
        // Large text: it reaches the arrow's column and becomes the edge.
        assertEquals(speedTop, NavFraming.bottomEdge(w, dLarge, barTop, false, 0.0, speedTop, 500.0, 101.0), 1e-9)
    }

    @Test fun `the pill above the bar raises the edge by its height and gap`() {
        val e = NavFraming.bottomEdge(w, d0, 2000.0, true, 100.0, 0.0, 0.0, 101.0)
        assertEquals(2000.0 - 100.0 - NavFraming.BAR_PILL_GAP_DP * d0, e, 1e-9)
        assertEquals(0.0, NavFraming.bottomEdge(w, d0, 0.0, true, 100.0, 0.0, 0.0, 101.0), 1e-9)
    }

    @Test fun `nothing measured is the old frame`() {
        val f = NavFraming.frame(h, d0, d0, 0.0, 0.0, 0.0)
        assertEquals(NavFraming.DEFAULT_PAD, f.pad, 1e-9)
        assertEquals(0.0, f.zoomOffset, 1e-9)
    }

    @Test fun `the pull-back ramps in and is bounded`() {
        assertEquals(0.0, NavFraming.zoomOffset(1.4), 1e-9)
        assertEquals(0.0, NavFraming.zoomOffset(NavFraming.DEAD_RATIO), 1e-9)
        val full = kotlin.math.ln(NavFraming.FULL_RATIO) / kotlin.math.ln(2.0)
        assertEquals(full, NavFraming.zoomOffset(NavFraming.FULL_RATIO), 1e-9)
        assertEquals(-NavFraming.MAX_ZOOM_OUT, NavFraming.zoomOffset(0.0), 1e-9)
        var last = 0.0
        var r = NavFraming.DEAD_RATIO
        while (r > 0.05) {
            val o = NavFraming.zoomOffset(r)
            assertTrue("not monotonic at $r", o <= last + 1e-12)
            last = o
            r -= 0.01
        }
    }

    @Test fun `the zoom floor holds at speed`() {
        assertEquals(17.5, NavFraming.zoom(18.5, -1.0), 1e-9)
        assertEquals(NavFraming.ZOOM_FLOOR, NavFraming.zoom(15.8, -1.0), 1e-9)
        // A speed zoom already under the floor (never today) is not pushed lower.
        assertEquals(15.2, NavFraming.zoom(15.2, -1.0), 1e-9)
        assertEquals(16.0, NavFraming.zoom(16.0, 0.0), 1e-9)
    }

    @Test fun `the framer adopts at once, then waits for the chrome to settle`() {
        val f = NavFramer()
        f.update(0, 2340, 3.4125f, 2.625f, 0f, 0f, 0f)
        assertEquals(NavFraming.DEFAULT_PAD, f.pad, 1e-9)
        assertEquals(0.0, f.zoomOffset, 1e-9)
        // Adopted at once: a different first frame lands on the first call.
        val g = NavFramer()
        assertTrue(g.update(0, 2340, 3.4125f, 2.625f, 1000f, 0f, 0f))
        // The card grows: nothing moves until it has held for SETTLE_MS.
        val top = (0.455 * 2340).toFloat()
        assertFalse(f.update(100, 2340, 3.4125f, 2.625f, top, 0f, 0f))
        assertFalse(f.update(100 + NavFraming.SETTLE_MS - 1, 2340, 3.4125f, 2.625f, top, 0f, 0f))
        assertTrue(f.update(100 + NavFraming.SETTLE_MS, 2340, 3.4125f, 2.625f, top, 0f, 0f))
        assertTrue(f.zoomOffset < 0.0)
    }

    @Test fun `sub-dp jitter and a passing drag never move the frame`() {
        val f = NavFramer()
        f.update(0, 2340, 3.4125f, 2.625f, 800f, 1800f, 300f)
        val pad = f.pad
        val off = f.zoomOffset
        var t = 0L
        // Half a dp either way, every frame for two seconds.
        repeat(120) { i ->
            t += 16
            assertFalse(f.update(t, 2340, 3.4125f, 2.625f, 800f + if (i % 2 == 0) 1.5f else -1.5f, 1800f, 300f))
        }
        // The bar dragged up and let go within the settle window.
        for (k in 1..15) {
            t += 16
            assertFalse(f.update(t, 2340, 3.4125f, 2.625f, 800f, 1800f - k * 20f, 300f))
        }
        repeat(60) {
            t += 16
            assertFalse(f.update(t, 2340, 3.4125f, 2.625f, 800f, 1800f, 300f))
        }
        assertEquals(pad, f.pad, 0.0)
        assertEquals(off, f.zoomOffset, 0.0)
    }
}
