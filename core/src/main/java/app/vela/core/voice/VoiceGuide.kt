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
    // Audio focus is held for the whole speech BURST, refcounted per utterance. The old
    // per-utterance request/abandon pair broke on queued prompts: speak(B) overwrote A's
    // request (leaking it), then A's onDone abandoned B's — the driver's music snapped back
    // to full volume exactly while "Turn right onto Main St" was being spoken over it.
    private val focusLock = Any()
    private var activeUtterances = 0
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A phone call / VOIP taking focus must SILENCE guidance — the old request had no
        // listener at all, so Vela kept announcing turns over ringing and active calls. The
        // next scheduled prompt re-fires naturally once the call releases focus.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            tts?.stop()
            neural?.stop()
            synchronized(focusLock) {
                activeUtterances = 0
                abandonFocus() // inside the lock — atomic with a racing acquire's count+request
            }
        }
    }

    private fun acquireFocus() {
        synchronized(focusLock) {
            activeUtterances += 1
            if (activeUtterances == 1) requestFocus()
        }
    }

    private fun releaseFocus() {
        synchronized(focusLock) {
            if (activeUtterances > 0) activeUtterances -= 1
            if (activeUtterances == 0) abandonFocus()
        }
    }

    private fun releaseAllFocus() {
        synchronized(focusLock) {
            activeUtterances = 0
            abandonFocus()
        }
    }

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
            override fun onDone(utteranceId: String?) = releaseFocus()
            // QUEUE_FLUSH fires onStop (not onDone) for the flushed utterance — without this
            // override every interrupt stranded a refcount and focus never released.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = releaseFocus()
            @Deprecated("deprecated") override fun onError(utteranceId: String?) {
                working = false // the engine accepted text but couldn't synthesise it
                releaseFocus()
            }
        })
    }

    /** Speak a sample so the user can confirm the engine actually makes sound (the
     *  only true test on their hardware — we can't hear it for them). */
    fun test() = speak(app.vela.core.i18n.NavStringsRegistry.current().voiceTest(), interrupt = true)

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
            // The neural synth fires onDone exactly ONCE per speak() (including aborted/
            // interrupted utterances — PiperSynth's finally), so the refcount balances without
            // any interrupt special-casing. Do NOT reset the count here: the interrupted
            // utterance's own onDone is still in flight and a reset would double-count it,
            // abandoning focus while the interrupting prompt speaks.
            acquireFocus()
            n.speak(forSpeech(text), interrupt) { releaseFocus() }
            return
        }
        // A FLUSH stops the current utterance + drops the queue; their onStop callbacks
        // decrement, so just acquire for the new utterance.
        acquireFocus()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(forSpeech(text), mode, null, "vela-${text.hashCode()}")
    }

    /** Expand road abbreviations so the engine SAYS them instead of spelling them: "St" →
     *  "Street", "Pkwy" → "Parkway", "N" → "North", "I-80" → "Interstate 80". Google's markup
     *  (and so the on-screen banner) keeps the compact forms; this is for the spoken text only.
     *  Whole-word, so it never mangles a name that merely contains the letters. */
    private fun forSpeech(text: String): String =
        app.vela.core.i18n.NavStringsRegistry.current().expandForSpeech(text)

    fun stop() {
        tts?.stop()
        neural?.stop()
        releaseAllFocus()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        neural?.stop()
        releaseAllFocus()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        // ONE request object reused for the burst (see acquire/releaseFocus) — building a fresh
        // request per utterance is what leaked the previous one.
        val req = focusRequest ?: run {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, android.os.Handler(android.os.Looper.getMainLooper()))
                .build()
                .also { focusRequest = it }
        }
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
// (Road-abbreviation → spoken-form expansion moved to EnNavStrings.expandForSpeech in core/i18n, so it's
//  English-scoped and opt-in — other languages leave road names untouched.)
