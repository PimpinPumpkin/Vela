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
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
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
 * The recognizer loads lazily and is kept for the process lifetime (~1–2 s to load); it's rebuilt when
 * the active engine OR the app language changes (both fold into [loadedKey]). The VAD is created per
 * listen (tiny, holds streaming state). R8 must keep `com.k2fsa.sherpa.onnx.**` (JNI resolves classes
 * by name) - already in `consumer-rules`/`proguard` for Piper.
 */
@Singleton
class AsrRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val loadLock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    /** "<engineId>|<lang>" - the recognizer is rebuilt whenever either changes. */
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
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512            // Silero v4/v5 window at 16 kHz
        const val MAX_SECONDS = 15            // hard cap on one utterance
        // SenseVoice's own language codes. Vela app languages outside this set fall back to "auto"
        // (the user picked SenseVoice knowing it's en/zh/ja/ko/yue only - the picker says so).
        val SENSE_VOICE_LANGS = setOf("zh", "en", "ja", "ko", "yue")
    }

    fun isInstalled(): Boolean = AsrEngine.anyInstalled(context)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The language to pin recognition to: the app language when the engine supports it, else
     *  auto-detect (""). Pinning matters - with auto-detect, a noisy capture can be misread as a whole
     *  other language and come back in the wrong script (a garbled far-field test transcribed to
     *  Cyrillic). The app language is what the user speaks to a maps app in practice. Moonshine is
     *  English-only and takes no language, so this is unused for it. */
    private fun pinnedLang(engine: AsrEngine): String {
        // Android hands back the LEGACY code for Hebrew ("iw"), not "he" (2026-07-12).
        val l = app.vela.ui.AppLocale.effective().language.let { if (it == "iw") "he" else it }
        return when (engine) {
            AsrEngine.WHISPER_TINY -> l.takeIf { it in app.vela.ui.AppLocale.SUPPORTED } ?: ""
            AsrEngine.SENSE_VOICE -> l.takeIf { it in SENSE_VOICE_LANGS } ?: "auto"
            AsrEngine.MOONSHINE -> ""
        }
    }

    /** Build the recognizer ahead of the first mic tap, off the main thread. The ONNX load takes a
     *  second or two on a phone, which used to show as a "Getting ready" beat on the FIRST dictation
     *  of a session (user 2026-07-10); warmed, the mic listens immediately. Cheap to call when no
     *  engine is installed (no-op), and safe to call repeatedly. */
    fun warmUp() {
        if (!AsrEngine.anyInstalled(context)) return
        Thread({ runCatching { ensureRecognizer() } }, "asr-warmup").start()
    }

    /** Load the active engine's recognizer once per (engine, language) key. Returns null if no engine
     *  is installed or the native load fails - callers then fall back to the provider intent or hide
     *  the mic. */
    private fun ensureRecognizer(): OfflineRecognizer? {
        val engine = AsrEngine.active(context)
        if (!engine.isInstalled(context)) return null
        val lang = pinnedLang(engine)
        val key = "${engine.id}|$lang"
        recognizer?.let { if (loadedKey == key) return it }
        synchronized(loadLock) {
            recognizer?.let { if (loadedKey == key) return it else runCatching { it.release() } }
            recognizer = null
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
            }.getOrNull()
            recognizer = r
            loadedKey = key
            return r
        }
    }

    /**
     * Record from the mic and return what was said, or null if nothing usable was heard (or no
     * engine/permission is present). [onLevel] gets a 0..1 loudness for the listening animation,
     * [onListening] fires once recording actually starts, and [cancelled] lets the UI stop early
     * (the user tapped done/close). Runs off the main thread; safe to cancel via coroutine too.
     */
    suspend fun listen(
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        cancelled: () -> Boolean,
    ): String? = withContext(Dispatchers.Default) {
        val rec = ensureRecognizer() ?: return@withContext null
        if (!hasMicPermission()) return@withContext null

        val vadModel = AsrEngine.active(context).let { File(it.dir(context), AsrEngine.VAD).absolutePath }
        val vad = runCatching {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModel,
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
        }.getOrNull() ?: return@withContext null

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
            audio?.release(); vad.release(); return@withContext null
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
            return@withContext null
        } finally {
            // Abandon focus FIRST so the music resumes even if a later call throws; every step is
            // guarded so one failure can't skip the rest and leave playback paused forever.
            abandonAudioFocus() // let the music resume
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }

        // Prefer the VAD-trimmed segment (leading/trailing silence stripped → cleaner transcript);
        // fall back to everything captured if the user stopped before a segment closed.
        val samples = segment ?: run {
            if (!sawSpeech && total < SAMPLE_RATE / 2) { vad.release(); return@withContext null }
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

        cleanTranscript(text).ifBlank { null }
    }

    /** The recognizers write prose: capitalized, ending with a period ("Coffee shops near me.").
     *  A search query wants neither the trailing sentence punctuation nor stray wrapping, so strip
     *  terminal . ! ? , ; : and quotes from the ends. Periods INSIDE the text are left alone -
     *  they can be real ("St. Paul"). */
    private fun cleanTranscript(raw: String): String =
        raw.trim().trim('"', '“', '”').trimEnd('.', '!', '?', ',', ';', ':', '…').trim()

    private fun rms(f: FloatArray): Float {
        if (f.isEmpty()) return 0f
        var sum = 0.0
        for (v in f) sum += v.toDouble() * v
        // Scale so a normal speaking level reads near the top of the 0..1 range for the animation
        // (speech at arm's length has RMS around 0.05 to 0.15; scaled so it sweeps most of the range).
        return (sqrt(sum / f.size) * 8.5f).toFloat().coerceIn(0f, 1f)
    }
}
