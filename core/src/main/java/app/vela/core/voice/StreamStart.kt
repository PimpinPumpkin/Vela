package app.vela.core.voice

/**
 * When a line rendered phrase by phrase may start playing. Started on its first phrase, a voice
 * that renders about as fast as it speaks ran dry mid-line and paused ("In 500 feet ... turn left",
 * #795). Playing from now, the audio made must outlast two waits: the render of the next phrase,
 * which arrives whole, and the amount by which the rest renders slower than it plays. The
 * per-letter render time is read from whole phrases, each with its fixed start-up cost, so it runs
 * high on a long phrase; that is the margin. On a Pixel 4a with a high-quality voice at normal
 * speed a test line still starts on its first phrase (1.9 s) and plays with no gap. A voice that
 * renders slower than it speaks waits.
 */
object StreamStart {
    const val MARGIN = 1.0

    /** The weight of the newest phrase in a running per-letter figure. */
    private const val EMA_NEW = 0.3

    fun ready(madeAudioMs: Double, charsLeft: Int, nextChars: Int, renderMsPerChar: Double, audioMsPerChar: Double): Boolean {
        if (charsLeft <= 0) return true
        val nextPhrase = nextChars * renderMsPerChar
        val behind = charsLeft * (renderMsPerChar - audioMsPerChar)
        return madeAudioMs >= maxOf(nextPhrase, behind) * MARGIN
    }

    /** A running per-letter figure (render time or audio length), [prev] null on a voice's first phrase. */
    fun perChar(prev: Double?, ms: Double, chars: Int): Double {
        val now = ms / chars.coerceAtLeast(1)
        return if (prev == null) now else prev + (now - prev) * EMA_NEW
    }
}
