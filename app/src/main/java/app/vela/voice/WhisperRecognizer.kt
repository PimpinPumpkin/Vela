package app.vela.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
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
 * On-device speech-to-text for voice search (tier-1). Loads **Whisper tiny (int8, multilingual)** +
 * **Silero VAD** through the bundled sherpa-onnx runtime, records from the mic, uses the VAD to spot
 * the end of speech, and returns the transcript. Nothing leaves the phone and no third-party voice
 * app is needed (that's tier-2 - the RECOGNIZE_SPEECH intent handoff in MapScreen).
 *
 * The Whisper recognizer loads lazily and is kept for the process lifetime (~1 s to load); the VAD is
 * created per listen (it's tiny and holds streaming state). R8 must keep `com.k2fsa.sherpa.onnx.**`
 * (JNI resolves classes by name) - already in `consumer-rules`/`proguard` for Piper.
 */
@Singleton
class WhisperRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val loadLock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLang: String? = null

    private companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512            // Silero v4/v5 window at 16 kHz
        const val MAX_SECONDS = 15            // hard cap on one utterance
    }

    fun isInstalled(): Boolean = AsrModel.isInstalled(context)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The language Whisper is pinned to: the app language when it's one Vela supports, else
     *  auto-detect. Pinning matters - with auto-detect, a noisy capture can be misread as a whole
     *  other language and come back in the wrong script (a garbled far-field test transcribed to
     *  Cyrillic). The app language is what the user speaks to a maps app in practice. */
    private fun whisperLang(): String {
        // Android hands back the LEGACY code for Hebrew ("iw"), which isn't in SUPPORTED ("he"), so
        // without this normalize Hebrew dictation fell through to Whisper auto-detect instead of
        // being pinned. Whisper's own code for Hebrew is "he". (Ports upstream PR #87.)
        val l = app.vela.ui.AppLocale.effective().language.let { if (it == "iw") "he" else it }
        return l.takeIf { it in app.vela.ui.AppLocale.SUPPORTED } ?: ""
    }

    /** Build the recognizer ahead of the first mic tap, off the main thread. The ONNX load takes a
     *  second or two on a phone, which used to show as a "Getting ready" beat on the FIRST dictation
     *  of a session (user 2026-07-10); warmed, the mic listens immediately. Cheap to call when the
     *  model isn't installed (no-op), and safe to call repeatedly - the synchronized loader keeps a
     *  built recognizer for the current language. A mid-session app-language switch still rebuilds
     *  lazily on the next listen (rare enough not to chase). */
    fun warmUp() {
        if (!AsrModel.isInstalled(context)) return
        Thread({ runCatching { ensureRecognizer() } }, "asr-warmup").start()
    }

    /** Load the Whisper recognizer once per language (rebuilt if the app language changes). Returns
     *  null if the model isn't installed or the native load fails - callers then fall back to the
     *  provider intent or hide the mic. */
    private fun ensureRecognizer(): OfflineRecognizer? {
        val lang = whisperLang()
        recognizer?.let { if (loadedLang == lang) return it }
        synchronized(loadLock) {
            recognizer?.let { if (loadedLang == lang) return it else runCatching { it.release() } }
            recognizer = null
            if (!AsrModel.isInstalled(context)) return null
            val dir = AsrModel.dir(context)
            val r = runCatching {
                OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = File(dir, AsrModel.ENCODER).absolutePath,
                                decoder = File(dir, AsrModel.DECODER).absolutePath,
                                language = lang,      // pinned to the app language ("" = auto)
                                task = "transcribe",
                                tailPaddings = -1,
                            ),
                            tokens = File(dir, AsrModel.TOKENS).absolutePath,
                            numThreads = 2,
                            modelType = "whisper",
                        ),
                    ),
                )
            }.getOrNull()
            recognizer = r
            loadedLang = lang
            return r
        }
    }

    /**
     * Record from the mic and return what was said, or null if nothing usable was heard (or the
     * model/permission isn't there). [onLevel] gets a 0..1 loudness for the listening animation,
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

        val vad = runCatching {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(AsrModel.dir(context), AsrModel.VAD).absolutePath,
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
            runCatching { audio.stop() }
            audio.release()
        }

        // Prefer the VAD-trimmed segment (leading/trailing silence stripped -> cleaner transcript);
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

        // Whisper writes prose ("Coffee shops near me.") and, on non-speech audio, bracketed sound
        // tags ("[music]", "[thud]"). :core's SpeechText.cleanSearchTranscript strips those into a
        // clean query (or "" -> null below = heard nothing, no search). Unit-tested there.
        app.vela.core.voice.SpeechText.cleanSearchTranscript(text).ifBlank { null }
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
