package app.vela.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A selectable TTS engine, e.g. Google TTS, RHVoice, eSpeak NG. */
data class VoiceEngine(val packageName: String, val label: String)

/**
 * Spoken guidance via AOSP [TextToSpeech] — no Play Services dependency, works
 * on every ROM. Stock AOSP ships Pico (robotic); GrapheneOS users typically add
 * RHVoice/eSpeak NG from F-Droid, so we enumerate installed engines and let the
 * user pick one ([availableEngines] + [enginePackage]) rather than hard-coding
 * Google's. Navigation prompts duck other audio via transient audio focus.
 */
@Singleton
class VoiceGuide @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    /** Speech-rate multiplier (1.0 = normal, >1 = faster), settable live from Settings. Applied to the
     *  Android TextToSpeech engine; the neural voice reads its own `voice_speed` pref per utterance. */
    @Volatile private var speechRate = 0.97f
    fun setRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }
    private var ready = false
    private var currentEngine: String? = null
    private val pending = ArrayDeque<Pair<String, Boolean>>()

    /** Vela's in-process neural voice (Kokoro/sherpa-onnx), wired from `:app` where the native
     *  runtime lives. When the user selects [VelaKokoro.ENGINE_ID], guidance goes here instead of
     *  Android TextToSpeech. Null until wired / on a build without it. */
    var neural: NeuralSynth? = null
    private var useNeural = false

    /** TTS health for the UI: null = initialising, true = a usable voice is ready,
     *  false = init failed or the chosen language has no installed voice data. Lets
     *  Settings tell the user *why* it's silent instead of failing quietly. */
    @Volatile
    var working: Boolean? = null
        private set

    /** When true, all spoken guidance is suppressed (the in-nav mute button). */
    @Volatile
    var muted = false
        set(value) {
            field = value
            if (value) stop()
        }

    private val audioManager: AudioManager? = context.getSystemService()
    private var focusRequest: AudioFocusRequest? = null

    /** Initialise, or **re-initialise** if [enginePackage] differs from the engine
     *  currently loaded — so picking a different engine in Settings actually takes
     *  effect (the old idempotent guard ignored later picks). */
    fun init(enginePackage: String? = null) {
        // One of Vela's own in-process neural voices (vela.kokoro / vela.piper) — no Android
        // TextToSpeech involved. The right synth is wired into [neural] by MapViewModel first.
        if (enginePackage != null && enginePackage.startsWith("vela.")) {
            if (useNeural && enginePackage == currentEngine) return
            if (tts != null) shutdown()
            currentEngine = enginePackage
            useNeural = true
            ready = true // the neural synth loads + queues internally
            working = neural != null
            neural?.warmUp()
            while (pending.isNotEmpty()) {
                val (text, interrupt) = pending.removeFirst()
                speakNow(text, interrupt)
            }
            return
        }
        useNeural = false
        neural?.stop()
        if (tts != null && enginePackage == currentEngine) return
        if (tts != null) shutdown()
        currentEngine = enginePackage
        working = null
        ready = false
        tts = if (enginePackage != null) {
            TextToSpeech(context, this, enginePackage)
        } else {
            TextToSpeech(context, this)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = abandonFocus()
            @Deprecated("deprecated") override fun onError(utteranceId: String?) {
                working = false // the engine accepted text but couldn't synthesise it
                abandonFocus()
            }
        })
    }

    /** Speak a sample so the user can confirm the engine actually makes sound (the
     *  only true test on their hardware — we can't hear it for them). */
    fun test() = speak("Voice guidance is on. In a quarter mile, turn right.", interrupt = true)

    override fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            working = false // the engine itself failed to start
            return
        }
        val locale = Locale.getDefault()
        val lang = if (t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) locale else Locale.US
        // setLanguage returns the same availability codes; MISSING_DATA / NOT_SUPPORTED
        // (< LANG_AVAILABLE) means the engine has no installed voice for us → silent.
        val langResult = t.setLanguage(lang)
        // A measured pace + neutral pitch reads more like a real nav voice than
        // the engine default (often a touch fast/robotic on stock Pico).
        t.setSpeechRate(speechRate)
        t.setPitch(1.0f)
        selectBestVoice(t, lang)
        ready = true
        working = langResult >= TextToSpeech.LANG_AVAILABLE
        while (pending.isNotEmpty()) {
            val (text, interrupt) = pending.removeFirst()
            speakNow(text, interrupt)
        }
    }

    /** Pick the highest-quality voice for [lang] that works offline — engines
     *  often default to a low-quality or download-required voice; this lifts
     *  guidance to the best installed one so it sounds natural in the car. */
    private fun selectBestVoice(t: TextToSpeech, lang: Locale) {
        runCatching {
            val best = t.voices.orEmpty()
                .filter {
                    it.locale.language == lang.language &&
                        !it.isNetworkConnectionRequired &&
                        it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                }
                .maxByOrNull { it.quality }
            if (best != null) t.voice = best
        }
    }

    /** Every TTS engine the user can pick: Vela's neural voice first (when its model is downloaded),
     *  then every system TTS engine installed on the phone. Enumerated via [android.content.pm.PackageManager]
     *  (the TTS_SERVICE intent) so the list is complete even when no Android [TextToSpeech] instance
     *  is active — e.g. while the neural voice is the current engine and `tts` is null. */
    fun availableEngines(): List<VoiceEngine> {
        val pm = context.packageManager
        val installed = runCatching {
            pm.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
                .mapNotNull { it.serviceInfo }
                .map { VoiceEngine(it.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.packageName }
        }.getOrElse { tts?.engines.orEmpty().map { VoiceEngine(it.name, it.label) } }
        val vela = if (VelaPiper.isReady(context)) listOf(VoiceEngine(VelaPiper.ENGINE_ID, VelaPiper.LABEL)) else emptyList()
        return vela + installed
    }

    /** Speak [text]; [interrupt] flushes the queue (use for the imminent turn). */
    fun speak(text: String, interrupt: Boolean = false) {
        if (muted) return
        if (!ready) {
            pending.addLast(text to interrupt)
            return
        }
        speakNow(text, interrupt)
    }

    private fun speakNow(text: String, interrupt: Boolean) {
        val n = neural
        if (useNeural && n != null) {
            requestFocus()
            n.speak(forSpeech(text), interrupt) { abandonFocus() }
            return
        }
        requestFocus()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(forSpeech(text), mode, null, "vela-${text.hashCode()}")
    }

    /** Expand road abbreviations so the engine SAYS them instead of spelling them: "St" →
     *  "Street", "Pkwy" → "Parkway", "N" → "North", "I-80" → "Interstate 80". Google's markup
     *  (and so the on-screen banner) keeps the compact forms; this is for the spoken text only.
     *  Whole-word, so it never mangles a name that merely contains the letters. */
    private fun forSpeech(text: String): String {
        var s = text
        s = Regex("\\bI-(\\d+)").replace(s) { "Interstate ${it.groupValues[1]}" }
        s = Regex("\\bUS-(\\d+)").replace(s) { "US ${it.groupValues[1]}" }
        SPEECH_WORDS.forEach { (re, rep) -> s = re.replace(s, rep) }
        return s
    }

    fun stop() {
        tts?.stop()
        neural?.stop()
        abandonFocus()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        neural?.stop()
        abandonFocus()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}

/** Whole-word road abbreviation → spoken form, applied to the TTS text only (not the banner).
 *  Road-type suffixes are case-insensitive; the directionals are uppercase (as they appear in
 *  road names) and come LAST so they can't chew into a word an earlier rule expanded. */
private val SPEECH_WORDS: List<Pair<Regex, String>> = listOf(
    Regex("\\bSt\\b", RegexOption.IGNORE_CASE) to "Street",
    Regex("\\bAve\\b", RegexOption.IGNORE_CASE) to "Avenue",
    Regex("\\bBlvd\\b", RegexOption.IGNORE_CASE) to "Boulevard",
    Regex("\\bRd\\b", RegexOption.IGNORE_CASE) to "Road",
    Regex("\\bDr\\b", RegexOption.IGNORE_CASE) to "Drive",
    Regex("\\bLn\\b", RegexOption.IGNORE_CASE) to "Lane",
    Regex("\\bCt\\b", RegexOption.IGNORE_CASE) to "Court",
    Regex("\\bPkwy\\b", RegexOption.IGNORE_CASE) to "Parkway",
    Regex("\\bHwy\\b", RegexOption.IGNORE_CASE) to "Highway",
    Regex("\\bPl\\b", RegexOption.IGNORE_CASE) to "Place",
    Regex("\\bTer\\b", RegexOption.IGNORE_CASE) to "Terrace",
    Regex("\\bCir\\b", RegexOption.IGNORE_CASE) to "Circle",
    Regex("\\bSq\\b", RegexOption.IGNORE_CASE) to "Square",
    Regex("\\bTrl\\b", RegexOption.IGNORE_CASE) to "Trail",
    Regex("\\bExpy\\b", RegexOption.IGNORE_CASE) to "Expressway",
    Regex("\\bFwy\\b", RegexOption.IGNORE_CASE) to "Freeway",
    Regex("\\bNE\\b") to "Northeast",
    Regex("\\bNW\\b") to "Northwest",
    Regex("\\bSE\\b") to "Southeast",
    Regex("\\bSW\\b") to "Southwest",
    Regex("\\bN\\b") to "North",
    Regex("\\bS\\b") to "South",
    Regex("\\bE\\b") to "East",
    Regex("\\bW\\b") to "West",
)
