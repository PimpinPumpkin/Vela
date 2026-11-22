package app.vela.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test

class DuckRenderTest {
    private fun isBill(c: Int): Boolean {
        val r = c shr 16 and 0xff; val g = c shr 8 and 0xff; val b = c and 0xff
        return (c ushr 24) == 255 && r > 150 && g < r * 0.62f && b < 60
    }

    /** Mean position of the bill's pixels, relative to the ground center. */
    private fun billAt(heading: Float, tilt: Float): Pair<Float, Float> {
        val n = 160
        val px = DuckRender.render(n, n, n / 2f, n / 2f, n * 0.46f / DuckRender.radius, heading, tilt)
        var sx = 0f; var sy = 0f; var k = 0
        for (i in px.indices) if (isBill(px[i])) { sx += i % n - n / 2f; sy += i / n - n / 2f; k++ }
        assertTrue("no bill drawn at heading $heading", k > 20)
        return sx / k to sy / k
    }

    @Test fun `the bill points along the heading`() {
        val (x0, y0) = billAt(0f, 0f)
        assertTrue("heading 0 bill above center: $x0,$y0", y0 < -20 && kotlin.math.abs(x0) < 4)
        val (x1, y1) = billAt(90f, 0f)
        assertTrue("heading 90 bill right of center: $x1,$y1", x1 > 20 && kotlin.math.abs(y1) < 4)
        val (x2, _) = billAt(270f, 50f)
        assertTrue("heading 270 bill left of center: $x2", x2 < -15)
    }

    @Test fun `the duck is drawn whole and its outline is smoothed`() {
        val n = 200
        val px = DuckRender.render(n, n, n / 2f, n / 2f, n * 0.46f / DuckRender.radius, 30f, 55f)
        val solid = px.count { it ushr 24 == 255 }
        val partial = px.count { val a = it ushr 24; a in 1..254 }
        assertTrue("too few duck pixels: $solid", solid > n * n / 8)
        assertTrue("no anti-aliased outline", partial > 100)
        // Nothing touches the bitmap's edge: the fit leaves a margin.
        for (i in 0 until n) {
            assertTrue(px[i] ushr 24 == 0 && px[(n - 1) * n + i] ushr 24 == 0)
            assertTrue(px[i * n] ushr 24 == 0 && px[i * n + n - 1] ushr 24 == 0)
        }
    }

    /** Writes stills to -DvelaDuck=<folder> to look at the model on the desk. */
    @Test fun deskStills() {
        val dir = System.getProperty("velaDuck") ?: return
        val n = 320
        for ((h, t) in listOf(0f to 0f, 25f to 50f, 0f to 55f, 90f to 55f, 180f to 55f, 20f to 55f)) {
            val px = DuckRender.render(n, n, n / 2f, n / 2f, n * 0.46f / DuckRender.radius, h, t)
            val img = java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, n, n, px, 0, n)
            javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "duck_h${h.toInt()}_t${t.toInt()}.png"))
        }
    }
}
