package app.vela.core.voice

import android.content.Context
import java.io.File

/**
 * Single source of truth for Vela's premium Kokoro neural voice: where it lives and whether it's
 * usable. Shared by [VoiceGuide] (`:core`) and the installer/synth (`:app`).
 *
 * The model is downloaded at runtime into `filesDir/kokoro` — never bundled. New downloads fetch the
 * **fp32** multi-lang model (`kokoro-multi-lang-v1_0`, ~310 MB): counter-intuitively it's ~2× FASTER
 * than the int8 build on ARM (int8 dynamic-quant has dequant overhead with no fast kernels) AND higher
 * quality. We still accept an already-downloaded int8 model, so we resolve the model file by glob.
 */
object VelaKokoro {
    const val ENGINE_ID = "vela.kokoro"
    const val LABEL = "Vela Neural (Kokoro) — premium"

    fun modelDir(context: Context): File = File(context.filesDir, "kokoro")

    /** The Kokoro ONNX file actually present (fp32 `model.onnx`, or a legacy `model.int8.onnx`). */
    fun modelFile(context: Context): File? =
        modelDir(context).listFiles()?.firstOrNull { it.name.matches(MODEL_RE) }

    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return modelFile(context) != null &&
            File(d, "voices.bin").exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }

    private val MODEL_RE = Regex("model.*\\.onnx")
}
