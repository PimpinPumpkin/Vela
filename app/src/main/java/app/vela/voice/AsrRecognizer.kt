package app.vela.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device speech-to-text for voice search (tier-1). Loads whichever [AsrEngine] the user has
 * active - **Whisper tiny** (multilingual default), **SenseVoice** (en/zh/ja/ko/yue), or **Moonshine**
 * (English) - through the bundled sherpa-onnx runtime, records from the mic, uses Silero VAD to spot
 * the end of speech, and returns the transcript. Nothing leaves the phone and no third-party voice app
 * is needed (that's tier-2 - the RECOGNIZE_SPEECH intent handoff in MapScreen).
 *
 * The recognizer loads lazily and is rebuilt when the active engine OR the app language changes (both
 * fold into [loadedKey]); the VAD is created per listen (tiny, holds streaming state). R8 must keep
 * `com.k2fsa.sherpa.onnx.**` (JNI resolves classes by name) - already in `consumer-rules`/`proguard`
 * for Piper.
 */
@Singleton
class AsrRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val loadLock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedKey: String? = null

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    @Volatile private var focusRequest: AudioFocusRequest? = null

    /** Take TRANSIENT audio focus so whatever is playing (music, a podcast) pauses while we listen,
     *  the way a phone assistant does - `AUDIOFOCUS_GAIN_TRANSIENT` (not `_MAY_DUCK`) makes media
     *  players pause rather than just duck. Abandoned in [abandonAudioFocus] the moment we stop. */
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    /** Give focus back so the paused music resumes right after the utterance. */
    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        focusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }

    private companion object {
        // Grep target for a tester's logcat: every listen() failure logs under this tag with its
        // VoiceResult.Reason, so "it doesn't work" arrives already diagnosed (issue #81).
        const val TAG = "VELAASR"
        // Crash-sentinel keys are PER-ENGINE (suffixed with the engine id): a truncated SenseVoice
        // must never quarantine or delete Whisper. KEY_LOAD_STRIKES counts loads the process died
        // inside (bumped before the native load, zeroed once it returns); TWO in a row quarantine -
        // one stranded load is as likely a mid-load kill (user swipe-away, memory reclaim) as a
        // crash, and must not delete a healthy download. KEY_MODEL_BAD_ latches the quarantine so a
        // bad model is not retried every launch; a fresh download clears it.
        const val KEY_LOAD_STRIKES = "asr_load_strikes_"
        const val KEY_MODEL_BAD = "asr_model_bad_"
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512            // Silero v4/v5 window at 16 kHz
        const val MAX_SECONDS = 15            // hard cap on one utterance
    }

    private fun prefs() = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private fun isQuarantined(engine: AsrEngine) = prefs().getBoolean(KEY_MODEL_BAD + engine.id, false)

    /** Voice search is available when at least one engine is installed AND not quarantined. */
    fun isInstalled(): Boolean = AsrEngine.installed(context).any { !isQuarantined(it) }

    /** Lift the quarantine for [engine] after a fresh download - the bad files are gone, so the next
     *  load may try again. Called by the installer path, never automatically. */
    fun clearQuarantine(engine: AsrEngine = AsrEngine.DEFAULT) {
        prefs().edit()
            .putBoolean(KEY_MODEL_BAD + engine.id, false)
            .putInt(KEY_LOAD_STRIKES + engine.id, 0).apply()
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The app language, normalized: Android hands back the LEGACY code for Hebrew ("iw"), not "he"
     *  (2026-07-12), so map it. */
    private fun appLang(): String =
        app.vela.ui.AppLocale.effective().language.let { if (it == "iw") "he" else it }

    /** The engine the recognizer should LOAD for the current language: the user's pick when it can do
     *  the language, else Whisper (see [AsrEngine.forRecognition]). */
    private fun engineForNow(): AsrEngine = AsrEngine.forRecognition(context, appLang())

    /** The language to pin recognition to: the app language when the engine supports it, else
     *  auto-detect. Pinning matters - with auto-detect, a noisy capture can be misread as a whole
     *  other language and come back in the wrong script (a garbled far-field test transcribed to
     *  Cyrillic). Moonshine is English-only and takes no language, so this is unused for it. */
    private fun pinnedLang(engine: AsrEngine): String {
        val l = appLang()
        return when (engine) {
            AsrEngine.WHISPER_TINY -> l.takeIf { it in app.vela.ui.AppLocale.SUPPORTED } ?: ""
            AsrEngine.SENSE_VOICE -> l.takeIf { it in AsrEngine.SENSE_VOICE_LANGS } ?: "auto"
            AsrEngine.MOONSHINE -> ""
        }
    }

    /** Build the recognizer ahead of the first mic tap, off the main thread. The ONNX load takes a
     *  second or two on a phone, which used to show as a "Getting ready" beat on the FIRST dictation
     *  of a session (user 2026-07-10); warmed, the mic listens immediately. Cheap to call when no
     *  engine is installed (no-op), and safe to call repeatedly - the synchronized loader keeps a
     *  built recognizer for the current engine+language. */
    fun warmUp() {
        if (!AsrEngine.anyInstalled(context)) return
        Thread({ runCatching { ensureRecognizer() } }, "asr-warmup").start()
    }

    /** Load the recognizer for the engine we'd run NOW (the pick, or Whisper for a language the pick
     *  can't do), rebuilt when the engine or app language changes. Returns null if no engine is
     *  installed/usable or the native load fails - callers fall back to the provider intent or hide
     *  the mic. */
    private fun ensureRecognizer(): OfflineRecognizer? {
        val engine = engineForNow()
        val lang = pinnedLang(engine)
        val key = "${engine.id}|$lang"
        recognizer?.let { if (loadedKey == key) return it }
        synchronized(loadLock) {
            recognizer?.let { if (loadedKey == key) return it else runCatching { it.release() } }
            recognizer = null
            if (!engine.isInstalled(context)) return null

            // CRASH SENTINEL around the native load, PER ENGINE. sherpa-onnx parses the .onnx files in
            // C++, and a TRUNCATED-but-non-empty model segfaults inside libsherpa-onnx-jni rather than
            // throwing - `runCatching` cannot catch a native abort, it takes the whole process down.
            // Reachable in the real world: a copy that stops partway (storage full, process killed)
            // leaves a short file that isInstalled()'s present-and-non-empty test happily accepts, and
            // warmUp() runs at STARTUP, so the result was an unrecoverable crash loop. So: bump a
            // strike counter before the load, zero it after; a counter that reaches TWO stranded
            // loads means the process died inside the load twice in a row - quarantine THAT engine
            // only and delete THAT engine's dir (a bad SenseVoice must never take out Whisper),
            // report not-installed, let the app start. A fresh download clears the quarantine.
            // TWO strikes, not one (the map sentinel's idiom, and device-measured necessity): the
            // load takes seconds, and a process killed DURING it - the user swiping the app away, the
            // system reclaiming memory, a test harness force-stop - strands the counter exactly like
            // a native crash. One stranded load used to delete a healthy 154 MB download; a genuinely
            // bad model crashes EVERY load, so it still self-heals one launch later.
            val prefs = prefs()
            val strikesKey = KEY_LOAD_STRIKES + engine.id
            val badKey = KEY_MODEL_BAD + engine.id
            val strikes = prefs.getInt(strikesKey, 0)
            if (strikes >= 2) {
                android.util.Log.e(TAG, "two ASR loads never returned (native crash) - quarantining ${engine.id}")
                prefs.edit().putInt(strikesKey, 0).putBoolean(badKey, true).apply()
                runCatching { engine.dir(context).deleteRecursively() }
                return null
            }
            if (prefs.getBoolean(badKey, false)) return null
            prefs.edit().putInt(strikesKey, strikes + 1).apply()

            val dir = engine.dir(context)
            fun p(name: String) = File(dir, name).absolutePath
            val modelConfig = when (engine) {
                AsrEngine.WHISPER_TINY -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = p("tiny-encoder.int8.onnx"),
                        decoder = p("tiny-decoder.int8.onnx"),
                        language = lang,      // pinned to the app language ("" = auto)
                        task = "transcribe",
                        tailPaddings = -1,
                    ),
                    tokens = p("tiny-tokens.txt"),
                    numThreads = 2,
                    modelType = engine.modelType,
                )
                AsrEngine.SENSE_VOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = p("model.int8.onnx"),
                        language = lang,      // "auto" or one of zh/en/ja/ko/yue
                        useInverseTextNormalization = true,   // "5 pm" not "five p m"
                    ),
                    tokens = p("tokens.txt"),
                    numThreads = 2,
                    modelType = engine.modelType,
                )
                AsrEngine.MOONSHINE -> OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = p("preprocess.onnx"),
                        encoder = p("encode.int8.onnx"),
                        uncachedDecoder = p("uncached_decode.int8.onnx"),
                        cachedDecoder = p("cached_decode.int8.onnx"),
                    ),
                    tokens = p("tokens.txt"),
                    numThreads = 2,
                    modelType = engine.modelType,
                )
            }
            val r = runCatching {
                OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = modelConfig,
                    ),
                )
            }.onFailure {
                // NAME the throwable. #84 fixed "the reason was discarded at the point of failure"
                // for listen(), but left it here: getOrNull() ate the one fact that separates a
                // missing .so (UnsatisfiedLinkError - the v7a strip, see app/build.gradle.kts) from
                // an OOM on a small phone, and both surfaced as "re-download the model". A tester
                // re-downloaded 47 MB twice on that advice. The class name alone decides it.
                android.util.Log.e(TAG, "native ASR load failed (${engine.id}): ${it::class.java.simpleName}")
            }.getOrNull()
            // The load RETURNED (success or a catchable failure), so the process survived it: zero
            // the strikes. Only a native abort (or a mid-load kill) leaves a strike standing, and
            // only two in a row quarantine.
            prefs.edit().putInt(strikesKey, 0).apply()
            recognizer = r
            loadedKey = key
            return r
        }
    }

    /**
     * Record from the mic and return what was said, or null if nothing usable was heard (or the
     * model/permission isn't there). [onLevel] gets a 0..1 loudness for the listening animation,
     * [onListening] fires once recording actually starts, and [cancelled] lets the UI stop early
     * (the user tapped done/close). Runs off the main thread; safe to cancel via coroutine too.
     */
    /** Listen, transcribe, and say WHY when it does not work - see [VoiceResult]. Every failure exit
     *  logs under `VELAASR` so a tester's logcat names the cause without another round-trip. */
    suspend fun listen(
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        cancelled: () -> Boolean,
    ): VoiceResult = withContext(Dispatchers.Default) {
        fun fail(reason: VoiceResult.Reason, detail: String? = null): VoiceResult.Failed {
            android.util.Log.e(TAG, "listen failed: $reason${detail?.let { " ($it)" } ?: ""}")
            return VoiceResult.Failed(reason, detail)
        }
        val rec = ensureRecognizer()
            ?: return@withContext fail(VoiceResult.Reason.MODEL, "model absent or native load failed")
        if (!hasMicPermission()) return@withContext fail(VoiceResult.Reason.PERMISSION)

        val vad = runCatching {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(engineForNow().dir(context), AsrEngine.VAD).absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.6f,   // ~0.6 s of quiet ends the utterance
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = MAX_SECONDS.toFloat(),
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        }.getOrNull() ?: return@withContext fail(VoiceResult.Reason.VAD, "silero VAD would not construct")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2),
            )
        }.getOrNull()
        if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
            val detail = if (audio == null) "constructor threw" else "state=${audio.state} minBuf=$minBuf"
            audio?.release(); vad.release()
            return@withContext fail(VoiceResult.Reason.AUDIO_INIT, detail)
        }

        val buf = ShortArray(VAD_WINDOW)
        val chunks = ArrayList<FloatArray>()
        var total = 0
        var sawSpeech = false
        var segment: FloatArray? = null
        try {
            requestAudioFocus() // pause any playing music/podcast while we listen
            audio.startRecording()
            onListening()
            while (!cancelled() && segment == null && total < SAMPLE_RATE * MAX_SECONDS) {
                val n = audio.read(buf, 0, VAD_WINDOW)
                if (n <= 0) continue
                val f = FloatArray(n) { buf[it] / 32768f }
                onLevel(rms(f))
                chunks.add(f)
                total += n
                if (n == VAD_WINDOW) vad.acceptWaveform(f)
                if (vad.isSpeechDetected()) sawSpeech = true
                // A finished speech segment (speech then a beat of silence) = the utterance.
                if (!vad.empty()) {
                    segment = vad.front().samples
                    vad.pop()
                }
            }
        } catch (t: Throwable) {
            runCatching { vad.release() }
            return@withContext fail(VoiceResult.Reason.RECORDING, t.message ?: t::class.java.simpleName)
        } finally {
            // Abandon focus FIRST so the music resumes even if a later call throws; every step is
            // guarded so one failure can't skip the rest and leave playback paused forever.
            abandonAudioFocus() // let the music resume
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }

        // Prefer the VAD-trimmed segment (leading/trailing silence stripped -> cleaner transcript);
        // fall back to everything captured if the user stopped before a segment closed.
        val samples = segment ?: run {
            if (!sawSpeech && total < SAMPLE_RATE / 2) { vad.release(); return@withContext VoiceResult.NoSpeech }
            val out = FloatArray(total)
            var off = 0
            for (c in chunks) { c.copyInto(out, off, 0, min(c.size, out.size - off)); off += c.size }
            out
        }
        vad.release()

        val text = runCatching {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val t = rec.getResult(stream).text
            stream.release()
            t
        }.getOrNull().orEmpty()

        // Whisper writes prose ("Coffee shops near me.") and, on non-speech audio, bracketed sound
        // tags ("[music]", "[thud]"). :core's SpeechText.cleanSearchTranscript strips those into a
        // clean query (or "" -> null below = heard nothing, no search). Unit-tested there.
        val query = app.vela.core.voice.SpeechText.cleanSearchTranscript(text).ifBlank { null }
        if (query == null) VoiceResult.NoSpeech else VoiceResult.Text(query)
    }

    private fun rms(f: FloatArray): Float {
        if (f.isEmpty()) return 0f
        var sum = 0.0
        for (v in f) sum += v.toDouble() * v
        // Scale so a normal speaking level reads near the top of the 0..1 range for the animation
        // (speech at arm's length has RMS around 0.05 to 0.15; scaled so it sweeps most of the range).
        return (sqrt(sum / f.size) * 8.5f).toFloat().coerceIn(0f, 1f)
    }
}
