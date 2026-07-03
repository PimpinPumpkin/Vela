package app.vela.core.voice

import android.content.Context
import java.io.File

/**
 * Single source of truth for Vela's downloaded Kokoro neural voice model: where it lives and whether
 * it's usable. Shared by [VoiceGuide] (`:core`) and the installer/synth (`:app`).
 *
 * The model (~126 MB, `kokoro-int8-multi-lang-v1_0`) is downloaded at runtime into `filesDir/kokoro`
 * — never bundled in the APK (that would bloat it + can't ship in an F-Droid build). Only the tiny
 * sherpa-onnx native runtime is bundled.
 */
object VelaKokoro {
    /** Synthetic engine id for the in-process neural voice — NOT a real installed TTS package, so
     *  VoiceGuide special-cases it (vs a system engine package name). */
    const val ENGINE_ID = "vela.kokoro"
    const val LABEL = "Vela Neural (Kokoro)"

    fun modelDir(context: Context): File = File(context.filesDir, "kokoro")

    /** The files `OfflineTtsKokoroModelConfig` needs at minimum to synthesize English — all must be
     *  present for the voice to be offered/selected. */
    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, "model.int8.onnx").exists() &&
            File(d, "voices.bin").exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }
}
