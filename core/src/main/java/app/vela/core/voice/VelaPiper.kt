package app.vela.core.voice

import android.content.Context
import java.io.File

/**
 * Vela's on-device neural voice: a Piper VITS model (`en_US-libritts_r-medium` — the most expressive
 * Piper English voice, and **multi-speaker**: ~900 voices you step through in the playground to pick
 * the one you like). Runs comfortably above realtime even on old phones, which is why it's the sole
 * neural voice — Kokoro (prettier) and Matcha were dropped as too slow / not worth the size. Bundled
 * sherpa-onnx runtime, model downloaded at runtime into `filesDir/piper`.
 */
object VelaPiper {
    const val ENGINE_ID = "vela.piper"
    const val LABEL = "Vela voice"
    const val MODEL = "en_US-libritts_r-medium.onnx"

    fun modelDir(context: Context): File = File(context.filesDir, "piper")

    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, MODEL).exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }
}
