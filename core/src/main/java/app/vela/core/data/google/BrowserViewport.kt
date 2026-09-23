package app.vela.core.data.google

/**
 * The browser window Vela's requests describe. Google's search and directions `pb` carry the map's
 * pixel size (`!3m2!1i<w>!2i<h>`) and four rectangles of it the page's own chrome covers (the side
 * panel, the right-hand strip, the top and bottom edges). The templates were captured from one
 * 1024x768 window, so every install sent the same size, a fingerprint no real population of
 * browsers has; autocomplete even claimed 1080x2000, a phone in portrait under a desktop UA.
 *
 * Each install picks one common maximized desktop Chrome viewport once (the app stores the pick)
 * and every request uses it. A template that no longer carries the captured 1024x768 shapes (a
 * recalibration) is left as it is rather than half-rewritten.
 */
object BrowserViewport {
    @Volatile var width: Int = 1024
        private set
    @Volatile var height: Int = 768
        private set

    /** Maximized Chrome on Windows at common screen sizes and scalings (window inner size: the
     *  screen minus the taskbar and the browser's own toolbars). Repeats weight the common ones. */
    val CHOICES: List<Pair<Int, Int>> = listOf(
        1920 to 945, 1920 to 945, 1920 to 945,
        1536 to 730, 1536 to 730,
        1366 to 641, 1366 to 641,
        1440 to 781, 1600 to 773, 1280 to 632, 1680 to 925, 2560 to 1305,
    )

    fun set(w: Int, h: Int) { width = w; height = h }

    /** [CHOICES] entry for a stored per-install index. */
    fun choice(index: Int): Pair<Int, Int> = CHOICES[Math.floorMod(index, CHOICES.size)]

    private const val CAPTURED_SIZE = "!3m2!1i1024!2i768"
    // The four rectangles; the enclosing field number differs (!30m28 in search, !20m28 in directions).
    private const val CAPTURED_RECTS = "m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2m2!1i1024!2i768" +
        "!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768"

    /** [pb] with the captured window replaced by this install's. */
    fun apply(pb: String, w: Int = width, h: Int = height): String {
        if (w == 1024 && h == 768) return pb
        val rects = "m28!1m6!1m2!1i0!2i0!2m2!1i530!2i$h!1m6!1m2!1i${w - 50}!2i0!2m2!1i$w!2i$h" +
            "!1m6!1m2!1i0!2i0!2m2!1i$w!2i20!1m6!1m2!1i0!2i${h - 20}!2m2!1i$w!2i$h"
        return pb.replace(CAPTURED_SIZE, "!3m2!1i$w!2i$h").replace(CAPTURED_RECTS, rects)
    }
}
