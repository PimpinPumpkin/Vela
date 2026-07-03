package app.vela.core.voice

import android.content.Context
import java.io.File

/**
 * Vela's **balanced** neural voice: Matcha-TTS (`matcha-icefall-en_US-ljspeech`) + a Vocos vocoder.
 * A 2024 flow-matching model — warmer/more natural than Piper, and still comfortably realtime (unlike
 * Kokoro). Two-part download (acoustic model tarball + a separate vocoder `.onnx`) into `filesDir/matcha`.
 */
object VelaMatcha {
    const val ENGINE_ID = "vela.matcha"
    const val LABEL = "Vela Neural (Matcha) — balanced"
    const val ACOUSTIC = "model-steps-3.onnx"
    const val VOCODER = "vocos-22khz-univ.onnx"

    fun modelDir(context: Context): File = File(context.filesDir, "matcha")

    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, ACOUSTIC).exists() &&
            File(d, VOCODER).exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }
}
