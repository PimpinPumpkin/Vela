package app.vela.voice

/**
 * What an on-device voice-search attempt actually did.
 *
 * [WhisperRecognizer.listen] used to return `String?`, so its SEVEN different exits all collapsed into
 * one indistinguishable `null` and the listening sheet just closed again. A TCL Flip 2 tester
 * reported exactly that and nothing more - "a box pops up for a second then closes" (issue #81) - and
 * neither they nor we could say which of a missing model, a denied mic, a failed `AudioRecord` init
 * or an OOM it was, because the reason was discarded at the point of failure. The question "what does
 * not working mean" was unanswerable by construction.
 *
 * So the reason travels back to the UI. [NoSpeech] is the benign one (tapped the mic, said nothing);
 * every [Failed] carries a [Reason] the user is told about and logcat records under `VELAASR`.
 */
sealed interface VoiceResult {

    /** A usable transcript, already cleaned of Whisper's prose and sound tags. */
    data class Text(val query: String) : VoiceResult

    /** Heard nothing worth searching. Not an error - no scary message, just close. */
    data object NoSpeech : VoiceResult

    /** The attempt could not run. [detail] is for the log, never for the user. */
    data class Failed(val reason: Reason, val detail: String? = null) : VoiceResult

    enum class Reason {
        /** The Whisper model is missing, corrupt, or the native load failed (OOM on a small phone). */
        MODEL,

        /** RECORD_AUDIO is not granted - possible on a locked-down ROM that answers the prompt for you. */
        PERMISSION,

        /** The Silero VAD model could not be constructed (asset missing or corrupt). */
        VAD,

        /** `AudioRecord` refused to initialise - the prime suspect on unusual mic configurations. */
        AUDIO_INIT,

        /** Recording or decoding threw partway through. */
        RECORDING,
    }
}
