package app.vela.core.voice

/**
 * An in-process neural TTS backend that [VoiceGuide] can drive instead of Android [android.speech.tts.TextToSpeech].
 *
 * Implemented in `:app` (KokoroSynth, wrapping the sherpa-onnx AAR) - that native runtime can't live
 * in the `:core` library module, so this interface is the seam: `:core`'s VoiceGuide holds a nullable
 * [NeuralSynth] that `:app` wires in. Calls come from the main thread; the implementation must run the
 * actual synthesis off it. [onDone] lets VoiceGuide release audio focus when an utterance finishes.
 */
interface NeuralSynth {
    /** True once the model is loaded and it can actually produce audio. */
    val ready: Boolean

    /** The language code of the currently-selected voice ("en", "ru", "fr", …), or null if unknown.
     * A Piper voice is a single-language model - reading another language's text through it is
     * gibberish - so [VoiceGuide] compares this to the language the nav text is generated in and
     * routes to a system TTS (or stays silent) on a mismatch. */
    val voiceLanguage: String? get() = null

    /** Begin loading the model off the main thread (idempotent, cheap to call repeatedly). */
    fun warmUp()

    /** Speak [text]; when [interrupt] is true, cancel any in-flight utterance first (imminent turn).
     * [onDone] runs once audio for this call finishes or is abandoned. */
    fun speak(text: String, interrupt: Boolean, onDone: () -> Unit)

    /** Stop any current + queued speech now. */
    fun stop()

    /** Free the native model + audio resources. */
    fun release()
}
