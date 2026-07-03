package app.vela.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.vela.core.voice.NeuralSynth
import app.vela.core.voice.VelaMatcha
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vela's BALANCED neural voice: Matcha-TTS (flow-matching acoustic model + Vocos vocoder) via
 * sherpa-onnx [OfflineTts] → [AudioTrack]. Warmer than Piper, realtime unlike Kokoro. Same pipeline
 * as [PiperSynth]/[KokoroSynth]; only the config differs (Matcha needs BOTH an acoustic model and a
 * vocoder). Sample rate from the generated audio (22050).
 */
@Singleton
class MatchaSynth @Inject constructor(
    @ApplicationContext private val context: Context,
) : NeuralSynth {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "matcha-tts").apply { isDaemon = true }
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var loadFailed = false
    @Volatile private var generation = 0

    override val ready: Boolean get() = tts != null

    override fun warmUp() {
        if (tts != null || loadFailed || !VelaMatcha.isReady(context)) return
        worker.execute { ensureLoaded() }
    }

    private fun ensureLoaded(): OfflineTts? {
        tts?.let { return it }
        if (loadFailed || !VelaMatcha.isReady(context)) return null
        return try {
            val dir = VelaMatcha.modelDir(context).absolutePath
            val matcha = OfflineTtsMatchaModelConfig(
                acousticModel = "$dir/${VelaMatcha.ACOUSTIC}",
                vocoder = "$dir/${VelaMatcha.VOCODER}",
                tokens = "$dir/tokens.txt",
                dataDir = "$dir/espeak-ng-data",
            )
            val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(matcha = matcha, numThreads = 2, debug = false))
            val engine = OfflineTts(assetManager = null, config = cfg)
            runCatching { engine.generate(text = " ", sid = 0, speed = SPEED) }
            tts = engine
            Log.i(TAG, "loaded ok: sampleRate=${engine.sampleRate()} speakers=${engine.numSpeakers()}")
            engine
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed: ${t.message}", t)
            loadFailed = true
            null
        }
    }

    override fun speak(text: String, interrupt: Boolean, onDone: () -> Unit) {
        val myGen = if (interrupt) ++generation else generation
        worker.execute {
            val engine = ensureLoaded()
            if (engine == null || myGen != generation) { onDone(); return@execute }
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val audio = engine.generate(text = text, sid = 0, speed = SPEED)
                val genMs = android.os.SystemClock.elapsedRealtime() - t0
                if (myGen != generation) { onDone(); return@execute }
                val samples = audio.samples
                if (samples.isNotEmpty()) {
                    val at = ensureTrack(audio.sampleRate)
                    at.pause(); at.flush(); at.play()
                    at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
                Log.i(TAG, "spoke ${"%.1f".format(samples.size / audio.sampleRate.toFloat())}s audio in ${genMs}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed: ${t.message}", t)
            } finally {
                onDone()
            }
        }
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let {
            if (it.sampleRate == sampleRate) return it
            it.release(); track = null
        }
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4))
            .build()
        track = t
        return t
    }

    override fun stop() {
        generation++
        worker.execute { runCatching { track?.pause(); track?.flush() } }
    }

    override fun release() {
        generation++
        worker.execute {
            runCatching { track?.release() }; track = null
            runCatching { tts?.release() }; tts = null
        }
    }

    private companion object {
        const val TAG = "MatchaSynth"
        const val SPEED = 1.0f
    }
}
