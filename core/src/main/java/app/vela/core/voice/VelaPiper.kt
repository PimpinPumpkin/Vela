package app.vela.core.voice

import android.content.Context
import java.io.File

/**
 * Vela's **fast** on-device neural voice: a Piper VITS model (`en_US-hfc_female-medium`). Runs at or
 * above realtime — the low-latency alternative to the premium-but-slow Kokoro ([VelaKokoro]). Same
 * bundled sherpa-onnx runtime, a different (VITS) model downloaded at runtime into `filesDir/piper`.
 */
object VelaPiper {
    const val ENGINE_ID = "vela.piper"
    const val LABEL = "Vela Neural (Piper) — fast"
    const val MODEL = "en_US-hfc_female-medium.onnx"

    fun modelDir(context: Context): File = File(context.filesDir, "piper")

    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, MODEL).exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }
}
