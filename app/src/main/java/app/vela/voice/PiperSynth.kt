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

    /** Which voice id `tts` currently holds — lets [ensureLoaded] detect a voice switch and rebuild. */
    @Volatile private var loadedVoiceId: String? = null

    /** Number of speakers in the loaded model (libritts_r is multi-speaker, ~900); 0 until loaded. */
    @Volatile var numSpeakers: Int = 0
        private set

    override val ready: Boolean get() = tts != null

    /** Language code of the loaded (or, before load, the selected) Piper voice — Piper voice ids are
     *  `<lang>_<REGION>-<name>` (e.g. `en_US-hfc_female-medium` → "en"), so the langCode is the id's
     *  prefix. VoiceGuide uses this to avoid reading, say, Russian nav text through the English model. */
    override val voiceLanguage: String?
        get() = (loadedVoiceId ?: VelaPiper.effectiveVoiceId(context))?.substringBefore('_')

    /** The user's chosen speaker (persisted PER VOICE — libritts_r's 904 speakers are meaningless for
     *  a single-speaker voice), clamped to the loaded model's range. Only the fleet-default voice seeds
     *  from the remotely-configurable [Calibration.defaultVoiceSpeaker]; others default to speaker 0. */
    private fun speakerId(): Int {
        val prefs = context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
        val id = loadedVoiceId ?: VelaPiper.effectiveVoiceId(context) ?: VelaPiper.DEFAULT_VOICE_ID
        // defaultVoiceSpeaker only tunes the multi-speaker libritts_r; single-speaker voices default to 0.
        val seed = if (id == VelaPiper.LEGACY_ID) calibration.current().defaultVoiceSpeaker else 0
        val n = prefs.getInt(VelaPiper.speakerKey(id), seed)
        return if (numSpeakers > 0) n.coerceIn(0, numSpeakers - 1) else n.coerceAtLeast(0)
    }

    /** The user's chosen speech-speed multiplier (persisted; 1.0 = normal, >1 = faster), clamped.
     *  Defaults to the remotely-configurable [Calibration.defaultVoiceSpeed] until the user adjusts it. */
    private fun speed(): Float =
        context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .getFloat("voice_speed", calibration.current().defaultVoiceSpeed).coerceIn(0.5f, 2.0f)

    override fun warmUp() {
        // No `tts != null` short-circuit: ensureLoaded must be able to REBUILD when the selected voice
        // changed. It's idempotent per-voice (returns the current engine when the right voice is up), so
        // a warm-up on the already-loaded voice is a cheap no-op.
        if (loadFailed || !VelaPiper.isReady(context)) return
        worker.execute { ensureLoaded() }
    }

    private fun ensureLoaded(): OfflineTts? {
        val r = VelaPiper.resolved(context) ?: return null // nothing usable installed
        val cur = tts
        if (cur != null && loadedVoiceId == r.voiceId && !loadFailed) return cur // right voice already up
        // A switch (or first load) → (re)build. Tear the old engine down first: we ARE the single worker
        // thread, so no generate() can be running concurrently → releasing here is safe (never a
        // use-after-free). loadFailed resets so a previously-bad voice doesn't block a new one.
        runCatching { cur?.release() }
        tts = null; loadedVoiceId = null; numSpeakers = 0; loadFailed = false
        // Two attempts: a voice loaded the instant its download/extract finishes can lose the race with
        // the filesystem flush on some devices — the first OfflineTts load throws, and (without a retry)
        // loadFailed sticks so the voice stays SILENT until an app restart. A brief retry heals it.
        repeat(2) { attempt ->
            try {
                // Lower the VITS noise scales below the library defaults (noiseScale 0.667, noiseScaleW 0.8).
                // Those defaults make synthesis STOCHASTIC — the same phrase varies run to run, which is why
                // stop consonants land cleanly most of the time but occasionally drop/soften ("left"→"lef",
                // "turn"→"durn"). Calmer sampling hews closer to the model's mean prediction, so consonants
                // come out consistently; the small loss of prosodic variety is a good trade for nav clarity.
                val vits = OfflineTtsVitsModelConfig(
                    model = r.model, tokens = r.tokens, dataDir = r.dataDir,
                    noiseScale = 0.5f, noiseScaleW = 0.6f,
                )
                val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false))
                val engine = OfflineTts(assetManager = null, config = cfg)
                numSpeakers = engine.numSpeakers()
                runCatching { engine.generate(text = " ", sid = 0, speed = SPEED) }
                tts = engine
                loadedVoiceId = r.voiceId
                Log.i(TAG, "loaded ${r.voiceId}: sampleRate=${engine.sampleRate()} speakers=$numSpeakers")
                return engine
            } catch (t: Throwable) {
                Log.e(TAG, "model load failed (attempt ${attempt + 1}): ${t.message}", t)
                if (attempt == 0) runCatching { Thread.sleep(200) } // let a just-written model settle, then retry
            }
        }
        loadFailed = true
        loadedVoiceId = null
        return null
    }

    /**
     * Switch to the currently-selected voice (call right after changing the `voice_model` pref). The
     * SINGLE switch trigger. Race-free: bump [generation] to abort any in-flight [speak] at its next
     * per-sentence check, then queue teardown + rebuild on the SAME serial worker — so it runs AFTER
     * the aborted speak returns and never frees `tts` mid-`generate()`.
     */
    fun reloadVoice() {
        generation++
        worker.execute {
            runCatching { track?.pause(); track?.flush() }
            runCatching { tts?.release() }
            tts = null; loadedVoiceId = null; numSpeakers = 0; loadFailed = false
            ensureLoaded()
        }
    }

    /** Delete a voice's model dir ON the worker thread, so the unlink can't race an in-flight
     *  `generate()` reading those files. Deleting the ACTIVE voice must call [reloadVoice]/[release]
     *  first so the engine is off the old files before this runs. */
    fun deleteModelDir(dir: java.io.File) {
        generation++
        worker.execute { runCatching { dir.deleteRecursively() } }
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
                // Break into phrase fragments (sentences + comma/semicolon clauses), synth each on its
                // own, and splice the tagged silence after it — a firm beat at periods, a shorter one at
                // commas (Piper reads straight through commas otherwise, running "In a quarter mile, turn
                // right" together). The split lives in :core so it's unit-tested (see SpeechText).
                val frags = SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC)
                var sampleRate = 22050
                val chunks = ArrayList<FloatArray>(frags.size * 2)
                for ((frag, gapAfter) in frags) {
                    // Abort paths return WITHOUT calling onDone — the finally below fires it
                    // exactly once per speak(). The old explicit onDone()+return double-fired
                    // (then finally again), which double-decremented VoiceGuide's audio-focus
                    // refcount and un-ducked music over the interrupting prompt.
                    if (myGen != generation) return@execute
                    val a = engine.generate(text = frag, sid = sid, speed = spd)
                    sampleRate = a.sampleRate
                    if (a.samples.isNotEmpty()) chunks.add(a.samples)
                    if (gapAfter > 0f) chunks.add(FloatArray((sampleRate * gapAfter).toInt())) // spliced silence
                }
                val samples = concat(chunks)
                val genMs = android.os.SystemClock.elapsedRealtime() - t0
                if (myGen != generation) return@execute
                if (samples.isNotEmpty()) {
                    val at = ensureTrack(sampleRate)
                    at.pause(); at.flush(); at.play()
                    at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
                Log.i(TAG, "spoke ${"%.1f".format(samples.size / sampleRate.toFloat())}s audio (${frags.size} frag.) in ${genMs}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed: ${t.message}", t)
            } finally {
                onDone()
            }
        }
    }

    /** Concatenate audio + spliced-silence chunks into one buffer (the gaps are already silence chunks
     *  inserted by the caller). Single chunk → returned as-is. */
    private fun concat(chunks: List<FloatArray>): FloatArray {
        if (chunks.isEmpty()) return FloatArray(0)
        if (chunks.size == 1) return chunks[0]
        val out = FloatArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(out, pos); pos += c.size }
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
        // Shorter beat spliced at commas/semicolons so clauses don't run together ("In a quarter mile, …").
        const val CLAUSE_PAUSE_SEC = 0.16f
    }
}
