package app.vela.voice

import android.content.Context
import java.io.File

/**
 * The on-device speech-to-text model Vela downloads for voice search (tier-1): **Whisper tiny
 * multilingual (int8) + Silero VAD**, hosted on this repo's `asr-models` GitHub release (like the
 * `tts-runtime`/`routing-graphs` infra releases - the asset lives nowhere else). ~58 MB one-time,
 * optional download; everything runs on the phone, no account and no third-party voice app.
 *
 * This holds only the metadata + a cheap install check. Actually loading + running the model needs
 * the sherpa-onnx AAR, which lives in [WhisperRecognizer] (`:app`, not `:core`).
 */
object AsrModel {
    const val ID = "whisper-tiny"
    const val URL = "https://github.com/alltechdev/vela-dpad/releases/download/asr-models/vela-asr-whisper-tiny.tar.bz2"
    const val SIZE_MB = 58

    const val ENCODER = "tiny-encoder.int8.onnx"
    const val DECODER = "tiny-decoder.int8.onnx"
    const val TOKENS = "tiny-tokens.txt"
    const val VAD = "silero_vad.onnx"

    /** `filesDir/asr/whisper-tiny/` - the extracted archive's single top-level folder. */
    fun dir(context: Context): File = File(context.filesDir, "asr/$ID")

    /** True only when EVERY model file is present and non-empty, so a partial or aborted download
     *  self-heals (a missing file reads as not-installed -> re-download). Pure file check, no model
     *  load, so it's safe to call from the UI thread / availability gates. */
    fun isInstalled(context: Context): Boolean {
        val d = dir(context)
        return listOf(ENCODER, DECODER, TOKENS, VAD).all { File(d, it).length() > 0L }
    }
}
