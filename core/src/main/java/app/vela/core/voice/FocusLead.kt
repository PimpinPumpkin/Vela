package app.vela.core.voice

import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts a line now, or after the focus lead when audio focus was just requested
 * (`VoiceGuide.FOCUS_LEAD_MS`), and drops a waiting line when the voice is stopped before its
 * lead is over. A stop or a mute cuts the line being spoken and drops the queue; a line still
 * waiting out its lead is part of that queue, so a prompt asked just before the mute button is
 * pressed does not speak after it. [post] runs a block after a delay (the voice's main-thread
 * handler).
 */
class FocusLead(private val post: (delayMs: Long, block: () -> Unit) -> Unit) {
    private val stops = AtomicInteger()

    /** Drop every line still waiting for its lead. */
    fun stop() {
        stops.incrementAndGet()
    }

    /** Run [go] at once when focus was already held ([fresh] false), else after [leadMs] unless
     *  [stop] was called in between. */
    fun run(fresh: Boolean, leadMs: Long, go: () -> Unit) {
        if (!fresh) {
            go()
            return
        }
        val mark = stops.get()
        post(leadMs) { if (stops.get() == mark) go() }
    }
}
