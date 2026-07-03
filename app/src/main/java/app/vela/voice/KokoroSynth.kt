package app.vela.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.vela.core.voice.NeuralSynth
import app.vela.core.voice.VelaKokoro
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process neural TTS: sherpa-onnx [OfflineTts] running the downloaded Kokoro model, streamed to an
 * [AudioTrack]. The near-Siri voice, with no standalone TTS app.
 *
 * All loading + synthesis runs on a single background thread (onnxruntime's session isn't for
 * concurrent calls, and it's far too heavy for the main thread). [speak] returns immediately. An
 * [interrupt] bumps a generation counter that the in-flight synth callback checks each chunk, so an
 * imminent-turn prompt cuts off whatever's playing near-instantly instead of waiting it out.
 */
@Singleton
class KokoroSynth @Inject constructor(
    @ApplicationContext private val context: Context,
) : NeuralSynth {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kokoro-tts").apply { isDaemon = true }
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var loadFailed = false
    // Bumped on every interrupt/stop so a stale in-flight utterance's chunk callback returns 0 and
    // sherpa stops generating. Read across threads → @Volatile.
    @Volatile private var generation = 0

    override val ready: Boolean get() = tts != null

    override fun warmUp() {
        if (tts != null || loadFailed || !VelaKokoro.isReady(context)) return
        worker.execute { ensureLoaded() }
    }

    private fun ensureLoaded(): OfflineTts? {
        tts?.let { return it }
        if (loadFailed || !VelaKokoro.isReady(context)) return null
        return try {
            val dir = VelaKokoro.modelDir(context).absolutePath
            // ENGLISH config for the multi-lang Kokoro model: model + voices + tokens + espeak-ng-data
            // (G2P) + lang="en". The multi-lang model REQUIRES either a lexicon or a lang — omit both
            // and sherpa native-exit()s the process (an uncatchable crash). We pass lang (not lexicon):
            // passing the Chinese lexicon/dictDir instead SIGABRTs on Android (jieba/FST init). English
            // runs on espeak G2P; the model's other languages just aren't wired here.
            val kokoro = OfflineTtsKokoroModelConfig(
                model = "$dir/model.int8.onnx",
                voices = "$dir/voices.bin",
                tokens = "$dir/tokens.txt",
                dataDir = "$dir/espeak-ng-data",
                lang = "en",
            )
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = 2, debug = false),
            )
            val engine = OfflineTts(assetManager = null, config = cfg)
            // First inference allocates the graph (~1-2 s) — pay it here, off the UI thread, so the
            // first real nav prompt isn't delayed.
            runCatching { engine.generate(text = " ", sid = SPEAKER, speed = SPEED) }
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
        // Bump synchronously on interrupt so an already-running chunk callback (on the worker) sees it
        // and bails; a non-interrupt call just queues behind the current one on the single worker.
        val myGen = if (interrupt) ++generation else generation
        worker.execute {
            val engine = ensureLoaded()
            if (engine == null || myGen != generation) { onDone(); return@execute }
            try {
                // Synthesize the WHOLE utterance first, THEN play. Streaming (play() then feed chunks
                // as they generate) underran the AudioTrack while the model computed the first chunk —
                // AudioFlinger dropped the track and the next write hit a dead track → SIGABRT. Nav
                // phrases are short, so full-synth-first (~0.5-1 s) is both robust and simpler.
                val audio = engine.generate(text = text, sid = SPEAKER, speed = SPEED)
                if (myGen != generation) { onDone(); return@execute }
                val samples = audio.samples
                if (samples.isNotEmpty()) {
                    val at = ensureTrack(audio.sampleRate)
                    at.pause(); at.flush()
                    at.play()
                    at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
                Log.i(TAG, "spoke ${"%.1f".format(samples.size / 24000f)}s")
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
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4)) // ≥ ~1 s of float PCM
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
        const val TAG = "KokoroSynth"
        const val SPEAKER = 3    // af_heart — the standout US-English voice in kokoro-multi-lang-v1_0
        const val SPEED = 1.0f
    }
}
