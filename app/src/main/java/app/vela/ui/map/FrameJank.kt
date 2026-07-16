package app.vela.ui.map

/**
 * UI-thread frame-pacing accumulator, fed by VelaMapView's per-frame nav ticker (which already
 * runs `withFrameNanos` every frame during turn-by-turn - zero extra listeners). The trip
 * recorder samples + resets it every 30 s into `J` lines, so "it felt laggy around the exit"
 * becomes a measurable statement in a shared trip file. Plain fields, written from the frame
 * loop and read from a 30 s ticker - torn reads across the pair are harmless for a diagnostic.
 *
 * LIMITATION worth remembering: this is the CHOREOGRAPHER cadence = the UI thread + vsync. The
 * GL map renders on its own thread, so pure map-thread stalls don't show here - but every jank
 * episode so far (style churn, placement storms, main-thread queries) stalled BOTH, and this
 * catches those.
 */
object FrameJank {
    @Volatile var frames = 0
    @Volatile var janky = 0 // frame gap > 32 ms (two 60 Hz vsyncs)
    @Volatile var worstMs = 0

    fun tick(dtMs: Int) {
        frames++
        if (dtMs > 32) janky++
        if (dtMs > worstMs) worstMs = dtMs
    }

    /** Returns "frames,janky,worstMs" and resets the window. */
    fun sampleAndReset(): String {
        val out = "$frames,$janky,$worstMs"
        frames = 0; janky = 0; worstMs = 0
        return out
    }
}
