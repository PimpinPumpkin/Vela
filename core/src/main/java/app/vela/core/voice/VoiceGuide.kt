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

    // System-TTS FALLBACK for language mismatch: the neural (Piper) voice is a single-language
    // model, so when the nav text is generated in a language it can't speak (the user switched the
    // app/system language to one whose voice isn't downloaded) we route to Android TextToSpeech in
    // that language instead of mangling it through the wrong voice. `tts` holds EITHER the user's
    // chosen system engine (useNeural=false) OR this lazily-created default fallback (useNeural=true).
    private var systemReady = false
    private var lastSystemLang: String? = null // avoid re-running setLanguage/selectBestVoice each utterance

    /** Invoked with a language code when guidance CAN'T speak that language (no matching neural voice
     *  AND the system TTS has no voice for it) — the UI surfaces a "download a &lt;language&gt; voice"
     *  hint so nav isn't silently mute. Set by `:app`. */
    var langUnavailable: ((String) -> Unit)? = null

    /** Vela's in-process neural voice (Piper/sherpa-onnx), wired from `:app` where the native
     *  runtime lives. When the user selects [VelaPiper.ENGINE_ID], guidance goes here instead of
     *  Android TextToSpeech. Null until wired / on a build without it. */
    var neural: NeuralSynth? = null
    private var useNeural = false

    /** The language the nav text is currently GENERATED in (`NavStringsRegistry`) — the language the
     *  chosen voice must actually be able to speak. */
    private fun targetLang(): String =
        app.vela.core.i18n.NavStringsRegistry.current().locale.language.ifBlank { "en" }

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
    @Volatile private var focusHeld = false // do we currently hold audio focus? (so a new prompt during
                                            // the release-hold window doesn't needlessly re-request)
    private val focusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Abandon focus a beat AFTER the last prompt ends (see releaseFocus) rather than instantly, so the
    // driver's music stays ducked CONTINUOUSLY across closely-spaced prompts instead of snapping back to
    // full between them — the "didn't reliably duck / not ducking enough" bug.
    private val abandonFocusRunnable = Runnable {
        synchronized(focusLock) { if (activeUtterances == 0) abandonFocus() }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A phone call / VOIP taking focus must SILENCE guidance — the old request had no
        // listener at all, so Vela kept announcing turns over ringing and active calls. The
        // next scheduled prompt re-fires naturally once the call releases focus.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            tts?.stop()
            neural?.stop()
            focusHandler.removeCallbacks(abandonFocusRunnable)
            synchronized(focusLock) {
                activeUtterances = 0
                abandonFocus() // inside the lock — atomic with a racing acquire's count+request
            }
        }
    }

    private fun acquireFocus() {
        focusHandler.removeCallbacks(abandonFocusRunnable) // cancel a pending release — keep the duck continuous
        synchronized(focusLock) {
            activeUtterances += 1
            if (!focusHeld) requestFocus() // still held from the last prompt? don't re-request
        }
    }

    private fun releaseFocus() {
        synchronized(focusLock) {
            if (activeUtterances > 0) activeUtterances -= 1
            if (activeUtterances == 0) {
                // Hold focus for a short tail so a compound prompt ("In 500 ft … turn right") or an
                // interrupt flushing the previous one keeps the music ducked across the gap. A new
                // acquire within FOCUS_HOLD_MS cancels this and reuses the still-held focus.
                focusHandler.removeCallbacks(abandonFocusRunnable)
                focusHandler.postDelayed(abandonFocusRunnable, FOCUS_HOLD_MS)
            }
        }
    }

    private fun releaseAllFocus() {
        focusHandler.removeCallbacks(abandonFocusRunnable)
        synchronized(focusLock) {
            activeUtterances = 0
            abandonFocus()
        }
    }

    /** Initialise, or **re-initialise** if [enginePackage] differs from the engine
     *  currently loaded — so picking a different engine in Settings actually takes
     *  effect (the old idempotent guard ignored later picks). */
    fun init(enginePackage: String? = null) {
        // One of Vela's own in-process neural voices (vela.piper) — no Android TextToSpeech
        // involved. The right synth is wired into [neural] by MapViewModel first. Do NOT shut the
        // system `tts` down here — it stays as the fallback for languages the neural voice can't
        // speak (see speakViaSystem); an unused instance is cheap.
        if (enginePackage != null && enginePackage.startsWith("vela.")) {
            if (useNeural && enginePackage == currentEngine) return
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
        systemReady = false
        lastSystemLang = null
        tts = if (enginePackage != null) {
            TextToSpeech(context, this, enginePackage)
        } else {
            TextToSpeech(context, this)
        }
        attachTtsListener()
    }

    private fun attachTtsListener() {
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
            systemReady = false
            if (!useNeural) working = false // the PRIMARY engine failed to start
            return
        }
        // A measured pace + neutral pitch reads more like a real nav voice than the engine default.
        // The LANGUAGE is set per-utterance now (speakViaSystem), keyed on the nav-text language —
        // so a mid-drive app/system-language change is honoured and the engine never reads a
        // language it has no voice for.
        t.setSpeechRate(speechRate)
        t.setPitch(1.0f)
        systemReady = true
        lastSystemLang = null // force setLanguage on the first utterance
        if (!useNeural) { ready = true; working = true } // this system engine is the PRIMARY voice
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
        val t = targetLang()
        val n = neural
        // Use the neural voice ONLY when it can actually speak the target language. A single-
        // language Piper model reading another language's text is gibberish (the "English voice
        // read Russian" bug) — voiceLanguage==null means unknown → trust it (old behaviour).
        if (useNeural && n != null && n.voiceLanguage.let { it == null || it == t }) {
            // The neural synth fires onDone exactly ONCE per speak() (including aborted/
            // interrupted utterances — PiperSynth's finally), so the refcount balances without
            // any interrupt special-casing. Do NOT reset the count here: the interrupted
            // utterance's own onDone is still in flight and a reset would double-count it,
            // abandoning focus while the interrupting prompt speaks.
            acquireFocus()
            n.speak(forSpeech(text), interrupt) { releaseFocus() }
            return
        }
        speakViaSystem(text, interrupt, t)
    }

    /** Speak through Android TextToSpeech in language [t] — the user's chosen system engine, or a
     *  lazily-created default engine when the neural voice can't cover [t]. If the system TTS has no
     *  voice for [t] either, stay SILENT (never read [t]'s text with a non-[t] voice) and surface the
     *  download hint. */
    private fun speakViaSystem(text: String, interrupt: Boolean, t: String) {
        val engine = tts ?: run { ensureSystemTts(); null }
        if (engine == null || !systemReady) {
            pending.addLast(text to interrupt) // drained by onInit once the fallback engine is ready
            return
        }
        if (t != lastSystemLang) {
            val avail = runCatching { engine.setLanguage(Locale(t)) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (avail < TextToSpeech.LANG_AVAILABLE) {
                working = false
                langUnavailable?.invoke(t) // "download a <language> voice" — don't mangle it through the wrong one
                return
            }
            selectBestVoice(engine, Locale(t))
            engine.setSpeechRate(speechRate)
            engine.setPitch(1.0f)
            lastSystemLang = t
            working = true
        }
        // A FLUSH stops the current utterance + drops the queue; their onStop callbacks
        // decrement, so just acquire for the new utterance.
        acquireFocus()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(forSpeech(text), mode, null, "vela-${text.hashCode()}")
    }

    /** Lazily create the DEFAULT system TTS engine as the neural-mismatch fallback, without touching
     *  the neural selection (`useNeural`/`currentEngine` stay put). Its `onInit` sets [systemReady]. */
    private fun ensureSystemTts() {
        if (tts != null) return
        systemReady = false
        lastSystemLang = null
        tts = TextToSpeech(context, this)
        attachTtsListener()
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
        systemReady = false
        lastSystemLang = null
        neural?.stop()
        releaseAllFocus()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        // ONE request object reused for the burst (see acquire/releaseFocus) — building a fresh request
        // per utterance is what leaked the previous one. GAIN_TRANSIENT_MAY_DUCK, NOT plain GAIN_TRANSIENT:
        // MAY_DUCK is OS-managed — the system ducks the driver's music AND auto-restores it when we abandon,
        // bulletproof. Plain TRANSIENT PAUSES the media, and many players don't reliably auto-resume when
        // focus is handed back ("Vela paused the music and didn't restart it"). The real cause of the
        // earlier "not ducking enough" was the FLAPPING (focus dropped between every prompt → music popped
        // back to full mid-turn), which the release-hold above fixes — so the duck is now continuous, which
        // is what actually reads as "ducked", without the pause-and-never-resume risk. (Duck DEPTH is set by
        // the OS/player and isn't tunable via the focus API; pause is the only thing deeper, and its resume
        // is unreliable — so continuous MAY_DUCK is the safe answer.)
        val req = focusRequest ?: run {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, focusHandler)
                .build()
                .also { focusRequest = it }
        }
        // Track whether we actually got focus — a FAILED request means the media app never ducked
        // (Vela used to speak over full-volume audio and never know); GRANTED or DELAYED both hold.
        focusHeld = am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        focusHeld = false
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        // Keep audio focus this long after the last prompt so back-to-back prompts don't flap the
        // driver's music on/off between them. Short enough that music resumes promptly after a cluster.
        const val FOCUS_HOLD_MS = 1500L
    }
}
// (Road-abbreviation → spoken-form expansion moved to EnNavStrings.expandForSpeech in core/i18n, so it's
//  English-scoped and opt-in — other languages leave road names untouched.)
