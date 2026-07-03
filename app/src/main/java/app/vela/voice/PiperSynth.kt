package app.vela.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.vela.core.voice.NeuralSynth
import app.vela.core.voice.SpeechText
import app.vela.core.voice.VelaPiper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vela's FAST neural voice: a Piper VITS model via sherpa-onnx [OfflineTts] → [AudioTrack]. Same
 * pipeline as [KokoroSynth] (single worker thread, generation-counter interrupt, full-utterance-then-
 * play) but a VITS config — Piper runs at/above realtime, so nav prompts are timely. Lower fidelity
 * than Kokoro; the user picks per taste. Sample rate is read from the generated audio (Piper=22050).
 */
@Singleton
class PiperSynth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibration: app.vela.core.config.CalibrationStore,
) : NeuralSynth {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "piper-tts").apply { isDaemon = true }
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var loadFailed = false
    @Volatile private var generation = 0

    /** Number of speakers in the loaded model (libritts_r is multi-speaker, ~900); 0 until loaded. */
    @Volatile var numSpeakers: Int = 0
        private set

    override val ready: Boolean get() = tts != null

    /** The user's chosen speaker (persisted), clamped to the model's range. */
    private fun speakerId(): Int {
        val n = context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .getInt("voice_speaker", calibration.current().defaultVoiceSpeaker)
        return if (numSpeakers > 0) n.coerceIn(0, numSpeakers - 1) else n.coerceAtLeast(0)
    }

    /** The user's chosen speech-speed multiplier (persisted; 1.0 = normal, >1 = faster), clamped.
     *  Defaults to the remotely-configurable [Calibration.defaultVoiceSpeed] until the user adjusts it. */
    private fun speed(): Float =
        context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .getFloat("voice_speed", calibration.current().defaultVoiceSpeed).coerceIn(0.5f, 2.0f)

    override fun warmUp() {
        if (tts != null || loadFailed || !VelaPiper.isReady(context)) return
        worker.execute { ensureLoaded() }
    }

    private fun ensureLoaded(): OfflineTts? {
        tts?.let { return it }
        if (loadFailed || !VelaPiper.isReady(context)) return null
        return try {
            val dir = VelaPiper.modelDir(context).absolutePath
            val vits = OfflineTtsVitsModelConfig(
                model = "$dir/${VelaPiper.MODEL}",
                tokens = "$dir/tokens.txt",
                dataDir = "$dir/espeak-ng-data",
            )
            val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false))
            val engine = OfflineTts(assetManager = null, config = cfg)
            numSpeakers = engine.numSpeakers()
            runCatching { engine.generate(text = " ", sid = 0, speed = SPEED) }
            tts = engine
            Log.i(TAG, "loaded ok: sampleRate=${engine.sampleRate()} speakers=$numSpeakers")
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
                val sid = speakerId()
                val spd = speed()
                // Synthesize each sentence on its own and splice a fixed silence gap between them, so
                // periods get a real, controllable beat. sherpa-onnx's own `silenceScale` config is a
                // no-op for this Piper/VITS path (measured on-device: 0.2 vs 1.4 gave identical audio
                // length), and one-shot generation runs sentences together, so we do the pausing here.
                // Splitting (which periods are real sentence ends vs. abbreviations) lives in :core so
                // it's unit-tested — see SpeechText.
                val parts = SpeechText.splitSentences(text)
                var sampleRate = 22050
                val chunks = ArrayList<FloatArray>(parts.size)
                for (p in parts) {
                    if (myGen != generation) { onDone(); return@execute }
                    val a = engine.generate(text = p, sid = sid, speed = spd)
                    sampleRate = a.sampleRate
                    if (a.samples.isNotEmpty()) chunks.add(a.samples)
                }
                val samples = joinWithGaps(chunks, sampleRate)
                val genMs = android.os.SystemClock.elapsedRealtime() - t0
                if (myGen != generation) { onDone(); return@execute }
                if (samples.isNotEmpty()) {
                    val at = ensureTrack(sampleRate)
                    at.pause(); at.flush(); at.play()
                    at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
                Log.i(TAG, "spoke ${"%.1f".format(samples.size / sampleRate.toFloat())}s audio (${parts.size} sent.) in ${genMs}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed: ${t.message}", t)
            } finally {
                onDone()
            }
        }
    }

    /** Concatenate sentence audio chunks with [PAUSE_SEC]-worth of silence between (not after the
     *  last), giving a clear beat at each period. Single chunk → returned as-is (no trailing silence). */
    private fun joinWithGaps(chunks: List<FloatArray>, sampleRate: Int): FloatArray {
        if (chunks.isEmpty()) return FloatArray(0)
        if (chunks.size == 1) return chunks[0]
        val gap = (sampleRate * PAUSE_SEC).toInt()
        val total = chunks.sumOf { it.size } + gap * (chunks.size - 1)
        val out = FloatArray(total)
        var pos = 0
        for ((i, c) in chunks.withIndex()) {
            c.copyInto(out, pos); pos += c.size
            if (i < chunks.size - 1) pos += gap // leave zeros (silence) for the gap
        }
        return out
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
        const val TAG = "PiperSynth"
        const val SPEED = 1.0f
        // Silence spliced between sentences (seconds) — a natural period beat for nav prompts.
        const val PAUSE_SEC = 0.32f
    }
}
