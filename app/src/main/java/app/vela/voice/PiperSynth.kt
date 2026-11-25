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
 * Vela's neural voice: a Piper VITS model via sherpa-onnx [OfflineTts] → [AudioTrack]. One
 * worker renders (spoken lines and lines prepared ahead), one thread plays ([sayer]), with a
 * generation counter for interrupts. A line prepared ahead plays without waiting for the worker.
 * Sample rate is read from the generated audio (Piper=22050).
 */
@Singleton
class PiperSynth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibration: app.vela.core.config.CalibrationStore,
) : NeuralSynth {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(SPEAK_PRIORITY); r.run() }, WORKER_NAME).apply { isDaemon = true }
    }

    /** The engine's own threads (one at two synthesis threads). The runtime starts them during the
     *  load, on [worker], and they keep the name and the priority the worker had then, so a load at
     *  background priority left them at nice 10 for good. A render waits for its slowest thread: on
     *  a Pixel 4a with every core busy a 52 letter line took 25 s, against 11 to 15 s once they move
     *  with the worker ([setPriority]) and 4.6 s idle. */
    @Volatile private var poolTids = IntArray(0)

    /** Threads named like [worker], other than the caller. Read on the worker after a load. */
    private fun findPoolTids(): IntArray {
        val me = android.os.Process.myTid()
        return runCatching {
            java.io.File("/proc/self/task").listFiles().orEmpty().mapNotNull { d ->
                val tid = d.name.toIntOrNull()?.takeIf { it != me } ?: return@mapNotNull null
                tid.takeIf { runCatching { java.io.File(d, "comm").readText().trim() }.getOrNull() == WORKER_NAME }
            }.toIntArray()
        }.getOrDefault(IntArray(0))
    }

    /** Sets [pri] on the calling worker, or on [tid] when given, and on the engine's threads. */
    private fun setPriority(pri: Int, tid: Int = 0) {
        runCatching { if (tid == 0) android.os.Process.setThreadPriority(pri) else android.os.Process.setThreadPriority(tid, pri) }
        for (t in poolTids) runCatching { android.os.Process.setThreadPriority(t, pri) }
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var loadFailed = false
    @Volatile private var generation = 0

    init {
        // The Piper VITS model is the app's second-largest native holding after the ASR model.
        // CRITICAL only, deliberately narrower than the recognizer's severe trigger: dropping the
        // synth costs a reload on the next prompt, and a prompt arriving late during navigation is
        // a missed turn. TRIM_MEMORY_COMPLETE only reaches background processes, and
        // RUNNING_CRITICAL means the device is about to start killing things regardless.
        // release() posts to the piper-tts worker, so it is already serialized against an
        // in-flight synthesis and cannot free the model out from under one.
        app.vela.ui.MemoryPressure.register { level ->
            if (app.vela.ui.MemoryPressure.isCritical(level)) release()
        }
    }

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

    override fun voiceFor(lang: String): Boolean {
        val selected = VelaPiper.selectedVoiceId(context)
        val want = if (selected?.substringBefore('_') == lang) null
            else VelaPiper.installedVoiceIds(context).firstOrNull { it.substringBefore('_') == lang } ?: return false
        if (VelaPiper.languageOverride != want) {
            VelaPiper.languageOverride = want
            reloadVoice()
        }
        return true
    }

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

    /** The Guidance volume setting (Settings > Voice, issue #245): 0.6 softer, 1 normal, 1.6
     *  louder, 2.2 loudest. [VoiceLevel] turns it into the gain for this voice. */
    private fun volume(): Float =
        context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .getFloat("voice_volume", 1.0f).coerceIn(0.2f, 3.0f)

    // Each voice's average level, by voice and speaker ([VoiceLevel.Meter]), kept between sessions
    // so a voice plays at the same loudness from its first line.
    private val voiceLevels = java.util.concurrent.ConcurrentHashMap<String, VoiceLevel.Meter>()
    private fun levelPrefs() = context.getSharedPreferences("vela_voice_level", android.content.Context.MODE_PRIVATE)
    /** Render time and audio length per letter by voice, speaker and speed (StreamStart.perChar),
     *  kept for the process: a line's start waits on what the rest of it will take to render. */
    private val renderCost = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val audioCost = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private fun levelFor(key: String): VoiceLevel.Meter =
        voiceLevels.getOrPut(key) { VoiceLevel.Meter.decode(levelPrefs().getString(key, null)) }

    override fun warmUp() {
        // No `tts != null` short-circuit: ensureLoaded must be able to REBUILD when the selected voice
        // changed. It's idempotent per-voice (returns the current engine when the right voice is up), so
        // a warm-up on the already-loaded voice is a cheap no-op.
        if (loadFailed || !VelaPiper.isReady(context)) return
        // BACKGROUND priority while warming (see AsrRecognizer.warmUp: the load competed with the
        // map's render thread at launch). The worker is also the thread that speaks, so a prompt
        // queued behind a slow background load raises it back ([speak] calls [boostBackground]).
        worker.execute {
            backgroundTid = android.os.Process.myTid()
            setPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try { ensureLoaded() } finally {
                backgroundTid = 0
                setPriority(SPEAK_PRIORITY)
            }
        }
    }

    /** The worker's thread id while it runs at background priority (a warm-up or a [prepare]),
     *  else 0. */
    @Volatile private var backgroundTid = 0

    /** A prompt is waiting: the worker finishes its background work, a warm-up or the phrase a
     *  [prepare] has in hand, at the speaking priority (the default one for an urgent line: the
     *  drive's opener waited 7.5 s on a cold start on the 4a, 2026-10-03). A phrase is one engine
     *  call and cannot be cut short, so it must not also run slow. */
    private fun boostBackground(urgent: Boolean = false) {
        val tid = backgroundTid
        if (tid != 0) setPriority(if (urgent) android.os.Process.THREAD_PRIORITY_DEFAULT else SPEAK_PRIORITY, tid)
    }

    /** Bumped by every [speak]: a [prepare] still queued when another line is asked for is
     *  dropped, so prepare-ahead work never sits between Start and the opener (four queued
     *  prepares held a cold-start opener back by another 5 s on the 4a). The one already
     *  rendering stops at its next phrase. */
    @Volatile private var speaksAsked = 0
    /** The text of the latest [speak], for a [prepare] of the same line to keep going. */
    @Volatile private var lastSpeak: String? = null
    /** Counts [prepareAll] calls: a queued prepare from an older one is dropped. */
    @Volatile private var prepareRound = 0


    private fun ensureLoaded(): OfflineTts? {
        val r = VelaPiper.resolved(context) ?: return null // nothing usable installed
        val cur = tts
        if (cur != null && loadedVoiceId == r.voiceId && !loadFailed) return cur // right voice already up
        // A switch (or first load) → (re)build. Tear the old engine down first: we ARE the single worker
        // thread, so no generate() can be running concurrently → releasing here is safe (never a
        // use-after-free). loadFailed resets so a previously-bad voice doesn't block a new one.
        runCatching { cur?.release() }
        tts = null; loadedVoiceId = null; numSpeakers = 0; loadFailed = false; poolTids = IntArray(0)
        clearPieces()
        val loadStart = android.os.SystemClock.elapsedRealtime()
        // Place names said the local way: swap the voice's English dictionary before it is read.
        PlaceDictionary.install(context, r.voiceId, r.dataDir)
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
                // 0.5/0.6 helped ("seems a little better") but t-drops persisted → stepped to 0.45/0.55
                // (2026-07-06). Go lower only carefully — too calm turns the voice flat/robotic; the floor
                // of this lever is the model itself (a crisper-consonant voice from the library is the
                // next escalation, e.g. lessac/ryan-high).
                val vits = OfflineTtsVitsModelConfig(
                    model = r.model, tokens = r.tokens, dataDir = r.dataDir,
                    noiseScale = 0.45f, noiseScaleW = 0.55f,
                )
                val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = SYNTH_THREADS, debug = false))
                val engine = OfflineTts(assetManager = null, config = cfg)
                numSpeakers = engine.numSpeakers()
                runCatching { engine.generate(text = " ", sid = 0, speed = SPEED) }
                poolTids = findPoolTids()
                tts = engine
                loadedVoiceId = r.voiceId
                Log.i(TAG, "loaded ${r.voiceId} in ${android.os.SystemClock.elapsedRealtime() - loadStart} ms: sampleRate=${engine.sampleRate()} speakers=$numSpeakers")
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
        sayer.execute { runCatching { track?.pause(); track?.flush() } }
        worker.execute {
            runCatching { tts?.release() }
            tts = null; loadedVoiceId = null; numSpeakers = 0; loadFailed = false; poolTids = IntArray(0)
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

    /**
     * One line asked to be spoken: its audio, made by [renderLine] on [worker] or taken whole from
     * [prepared], and played by [sayLine] on [sayer]. The two run apart, so a line with its audio
     * ready never waits for a render, and a line that needs one renders while the line before it
     * plays.
     */
    private inner class Line(val text: String, val gen: Int, val interrupt: Boolean, val asked: Long) {
        val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        /** Opens when playback may begin: the audio is whole, or enough of it to play through. */
        val canStart = java.util.concurrent.CountDownLatch(1)
        @Volatile var sampleRate = 22050
        @Volatile var made = 0
        @Volatile var fromPrepared = false
        /** Audio [prepare] made for this line, taken when it was asked; leveled when it plays. */
        @Volatile var ready: Pair<FloatArray, Int>? = null
        @Volatile var renderStarted = 0L
        @Volatile var renderMs = -1L
        /** Phrases of a rendered line that were already stored, for the log. */
        @Volatile var storedPhrases = 0
        @Volatile var levelKey: String? = null
        private var gain = Float.NaN
        fun aborted() = gen != generation

        /** [chunk] leveled to the voice's usual loudness and queued. One gain for the whole line,
         *  from the voice's level so far (this line's first phrase when it has never spoken), so
         *  the level cannot pump between phrases. */
        fun feed(chunk: FloatArray, rate: Int, key: String, vol: Float) {
            if (chunk.isEmpty()) return
            levelKey = key
            val level = levelFor(key)
            synchronized(level) { level.add(chunk) }
            if (gain.isNaN() && level.rms > 0f) gain = VoiceLevel.gain(level.rms, vol)
            VoiceLevel.apply(chunk, if (gain.isNaN()) vol else gain)
            if (made == 0) sampleRate = rate
            made += chunk.size
            queue.put(chunk)
        }

        fun start() = canStart.countDown()

        /** Nothing more comes: the player stops at the end of the queue. */
        fun end() {
            queue.put(END)
            canStart.countDown()
        }
    }

    /** Plays lines in the order asked, one at a time, and owns [track]. */
    private val sayer = Executors.newSingleThreadExecutor { r ->
        Thread({ runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }; r.run() }, "piper-say").apply { isDaemon = true }
    }

    /** The whole of [text] from stored [pieces] with the voice, speaker and speed in use, its
     *  pauses spliced in; null when a phrase of it is not stored. */
    private fun takePrepared(text: String, sid: Int = speakerId(), spd: Float = speed()): Pair<FloatArray, Int>? {
        if (loadedVoiceId == null) return null
        val frags = SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC)
        if (!synchronized(pieces) { frags.all { pieces.containsKey(pieceKey(it.first, sid, spd)) } }) return null
        val chunks = ArrayList<FloatArray>(frags.size * 2)
        var rate = 22050
        for ((frag, gapAfter) in frags) {
            val (audio, r) = storedPiece(pieceKey(frag, sid, spd)) ?: return null
            rate = r
            if (audio.isNotEmpty()) chunks.add(audio)
            if (gapAfter > 0f) chunks.add(FloatArray(gapFrames(rate, gapAfter, spd)))
        }
        return concat(chunks) to rate
    }

    /** True when every phrase of [text] is stored. */
    private fun isPrepared(text: String, sid: Int, spd: Float): Boolean =
        SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC).let { frags ->
            synchronized(pieces) { frags.all { pieces.containsKey(pieceKey(it.first, sid, spd)) } }
        }

    override fun speak(text: String, interrupt: Boolean, onDone: () -> Unit) {
        // Counted before the raise: [prepare] relies on that order.
        lastSpeak = text
        speaksAsked++
        boostBackground(interrupt)
        val myGen = if (interrupt) ++generation else generation
        val line = Line(text, myGen, interrupt, android.os.SystemClock.elapsedRealtime())
        // Audio [prepare] made ahead of time for exactly this line plays at once, whatever the
        // worker is rendering. Otherwise the worker renders it now, while the line before plays;
        // a prepare of this very line still rendering is finished first and taken there.
        line.ready = runCatching { takePrepared(text) }.getOrNull()
        if (line.ready == null) worker.execute { renderLine(line) }
        sayer.execute { sayLine(line, onDone) }
    }

    /** Renders [line] phrase by phrase into its queue, on [worker]. Playback may start once the
     *  audio made covers the rest's expected render time (StreamStart), so a slow voice does not
     *  stop mid-line for its next phrase. */
    private fun renderLine(line: Line) {
        try {
            if (line.aborted()) return
            val engine = ensureLoaded() ?: return
            // An imminent-turn prompt (interrupt: it cuts whatever is playing) keeps the default
            // priority so a busy map cannot delay it; the far prompts and the opener yield.
            setPriority(if (line.interrupt) android.os.Process.THREAD_PRIORITY_DEFAULT else SPEAK_PRIORITY)
            val sid = speakerId()
            val spd = speed()
            val vol = volume()
            val levelKey = "$loadedVoiceId|$sid"
            takePrepared(line.text, sid, spd)?.let { ready ->
                line.fromPrepared = true
                line.feed(ready.first, ready.second, levelKey, vol)
                return
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            line.renderStarted = t0
            val frags = SpeechText.speechFragments(line.text, PAUSE_SEC, CLAUSE_PAUSE_SEC)
            val costKey = "$loadedVoiceId|$sid|$spd"
            // Phrases already stored (a distance, the rest of a line prepared at another
            // distance) cost no render, so only the others count toward when playback may start.
            val stored = synchronized(pieces) { frags.map { pieces.containsKey(pieceKey(it.first, sid, spd)) } }
            line.storedPhrases = stored.count { it }
            var charsLeft = frags.indices.filter { !stored[it] }.sumOf { frags[it].first.length }
            for ((i, f) in frags.withIndex()) {
                val (frag, gapAfter) = f
                if (line.aborted()) return
                val key = pieceKey(frag, sid, spd)
                val kept = if (stored[i]) storedPiece(key) else null
                val before = line.made
                val rate: Int
                if (kept != null) {
                    rate = kept.second
                    line.feed(kept.first, rate, levelKey, vol)
                } else {
                    val r0 = android.os.SystemClock.elapsedRealtime()
                    val made = render(engine, frag, sid, spd)
                    val fragMs = (android.os.SystemClock.elapsedRealtime() - r0).toDouble()
                    rate = made.second
                    storePiece(key, made.first, rate)
                    line.feed(made.first, rate, levelKey, vol)
                    renderCost[costKey] = app.vela.core.voice.StreamStart.perChar(renderCost[costKey], fragMs, frag.length)
                    audioCost[costKey] = app.vela.core.voice.StreamStart.perChar(audioCost[costKey], (line.made - before) * 1000.0 / rate, frag.length)
                    if (!stored[i]) charsLeft -= frag.length
                }
                if (gapAfter > 0f) line.feed(FloatArray(gapFrames(rate, gapAfter, spd)), rate, levelKey, vol)
                val renderPer = renderCost[costKey]
                val audioPer = audioCost[costKey]
                val nextChars = frags.getOrNull(i + 1)?.first?.length?.takeIf { !stored[i + 1] } ?: 0
                // With no render measured yet for this voice, stored phrases alone start the line
                // only when nothing is left to render.
                val go = if (renderPer == null || audioPer == null) charsLeft <= 0
                    else app.vela.core.voice.StreamStart.ready(line.made * 1000.0 / line.sampleRate, charsLeft, nextChars, renderPer, audioPer)
                if (go) line.start()
            }
            line.renderMs = android.os.SystemClock.elapsedRealtime() - t0
        } catch (t: Throwable) {
            Log.e(TAG, "render failed: ${t.message}", t)
        } finally {
            setPriority(SPEAK_PRIORITY)
            line.end()
        }
    }

    /** Plays [line] once the line before it is done, on [sayer]: waits for its audio to be ready
     *  to play, writes it to the track, and lets it play out before the next line. */
    private fun sayLine(line: Line, onDone: () -> Unit) {
        try {
            line.ready?.let { (audio, rate) ->
                line.fromPrepared = true
                line.feed(audio, rate, "$loadedVoiceId|${speakerId()}", volume())
                line.end()
            }
            while (!line.aborted() && !line.canStart.await(30, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit
            if (line.aborted() || line.made == 0) return
            val playAt = android.os.SystemClock.elapsedRealtime()
            val waited = playAt - line.asked
            // How long a line took to sound (queue, model load, render) and to make: a late
            // "Starting navigation" on a slow head unit is one of these (2026-10-03). Lengths only.
            val firstAudio = if (line.renderStarted > 0) playAt - line.renderStarted else 0L
            Log.i(TAG, "speak: waited $waited ms, ${if (line.fromPrepared) "prepared" else "first audio after $firstAudio ms, all rendered in ${line.renderMs.coerceAtLeast(firstAudio)} ms, ${line.storedPhrases} phrases stored"}, ${line.text.length} chars")
            app.vela.diag.TripNote.add("voice: waited $waited ms, ${if (line.fromPrepared) "ready ahead" else "first sound after $firstAudio ms"}, ${line.text.length} letters")
            val rate = line.sampleRate
            val at = ensureTrack(rate)
            val written = play(at, rate, line.queue) { line.aborted() }
            // DRAIN before finishing: the last write returns while the track buffer's tail (~1 s:
            // bufferSize is sampleRate*4 bytes = 1 s of float mono) is still PLAYING, and the NEXT
            // line's pause+flush would chop it, the "spoken directions partially stacking" bug.
            // Wait until the audio truly ends, then leave a short breath between prompts. An
            // interrupting speak bumps [generation] and this loop bails within ~30 ms; the urgent
            // prompt then flushes the tail. onDone (audio-focus release) fires at the real end.
            val deadline = android.os.SystemClock.elapsedRealtime() + 2000L
            while (!line.aborted() &&
                runCatching { at.playbackHeadPosition.toLong() }.getOrDefault(written) < written &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(30)
            }
            if (!line.aborted()) Thread.sleep(INTER_PROMPT_GAP_MS)
            // Played out: pause the track, so the audio output can go to standby between prompts
            // instead of holding a PLAYING stream (the next prompt plays it again).
            if (!line.aborted()) runCatching { at.pause() }
            val starvedMs = starvedFrames * 1000 / rate
            Log.i(TAG, "spoke ${"%.1f".format(line.made / rate.toFloat())}s audio (${if (line.fromPrepared) "prepared" else "streamed"}) in ${line.renderMs.coerceAtLeast(0)}ms, waited for audio $starvedMs ms mid-line")
            // A shared trip shows a line that stopped mid-way for its next phrase (#795).
            if (starvedMs >= STARVE_NOTE_MS) app.vela.diag.TripNote.add("voice: paused $starvedMs ms mid-line waiting for its next phrase")
            // Derived from the voice itself, so it is not in the backup rules.
            line.levelKey?.let { key ->
                val level = levelFor(key)
                runCatching { levelPrefs().edit().putString(key, synchronized(level) { level.encode() }).apply() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "speak failed: ${t.message}", t)
        } finally {
            onDone()
        }
    }

    /** Renders the phrases of [text] not stored yet, the way [speak] voices them, and stores
     *  them (as [keep] pieces when asked). Returns the line's audio seconds and the phrases it
     *  rendered, or null when [aborted] turns true mid-way; the phrases done by then stay stored. */
    private fun synthesize(engine: OfflineTts, text: String, sid: Int, spd: Float, keep: Boolean, aborted: () -> Boolean): Pair<Float, Int>? {
        // Each phrase (sentence or comma clause) is its own engine call with a spliced silence
        // after it: sherpa-onnx's `silenceScale` is a no-op for Piper/VITS, one-shot generation
        // runs sentences together, and Piper reads straight through commas. The split lives in
        // :core so it is unit-tested (SpeechText).
        val frags = SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC)
        var seconds = 0f
        var rendered = 0
        for ((frag, gapAfter) in frags) {
            if (aborted()) return null
            val key = pieceKey(frag, sid, spd)
            val have = synchronized(pieces) { pieces[key] }
            val (frames, rate) = if (have != null) {
                if (keep) storePiece(key, null, have.rate, keep = true)
                have.pcm.size to have.rate
            } else {
                val (audio, rate) = render(engine, frag, sid, spd)
                storePiece(key, audio, rate, keep)
                rendered++
                audio.size to rate
            }
            seconds += frames / rate.toFloat()
            if (gapAfter > 0f) seconds += gapFrames(rate, gapAfter, spd) / rate.toFloat()
        }
        return seconds to rendered
    }

    /** A spliced pause in frames. The pause lengths were tuned by ear at [PAUSE_TUNED_SPEED], so
     *  they stretch and shrink with the speed setting: twice as fast, half the pause. */
    private fun gapFrames(sampleRate: Int, gapSec: Float, spd: Float): Int =
        (sampleRate * gapSec * PAUSE_TUNED_SPEED / spd.coerceAtLeast(0.1f)).toInt()

    /** One phrase's audio and its sample rate. Every phrase gets terminal punctuation: a
     *  bare-ending one ("turn left") gives the model no final contour, so it trails off and
     *  swallows the last consonant ("lef", user 2026-07-06). The semicolon contour was A/B'd best
     *  on this voice (see EnNavStrings.arrived). */
    private fun render(engine: OfflineTts, frag: String, sid: Int, spd: Float): Pair<FloatArray, Int> {
        val fragText = if (frag.lastOrNull()?.isLetterOrDigit() == true) "$frag;" else frag
        val a = engine.generate(text = fragText, sid = sid, speed = spd)
        return a.samples to a.sampleRate
    }

    /** Plays queued audio until [END], on [sayer]; returns the frames written. Writes in
     *  ~200 ms pieces with an abort check between them, so an INTERRUPT (turn-now / rerouting /
     *  stop-nav bumps `generation`) takes effect within ~200 ms; on abort, pause+flush kills the
     *  buffered tail so the urgent prompt is not preceded by stale audio. When the next phrase is
     *  not rendered yet it writes [STARVE_MS] of silence, so the stream never underruns (a
     *  starved stream was the SIGABRT of the first streaming attempt). */
    private fun play(at: AudioTrack, sampleRate: Int, queue: java.util.concurrent.BlockingQueue<FloatArray>, aborted: () -> Boolean): Long {
        starvedFrames = 0L
        runCatching { at.pause(); at.flush(); at.play() }
        val writeChunk = sampleRate / 5
        val silence = FloatArray(sampleRate * STARVE_MS / 1000)
        var written = 0L
        // A lead-in of silence (issue #802): the track is paused between prompts so the output can
        // stand by, and a sink waking from standby (a Bluetooth head unit above all) dropped the
        // first syllables when the words were written the instant the stream started. The lead-in
        // plays while the sink wakes, so the words begin once it is listening.
        val lead = FloatArray((sampleRate * app.vela.core.voice.SpeechLeadIn.ms(context) / 1000).toInt())
        at.write(lead, 0, lead.size, AudioTrack.WRITE_BLOCKING); written += lead.size
        while (true) {
            if (aborted()) { runCatching { at.pause(); at.flush() }; return written }
            val chunk = queue.poll(STARVE_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (chunk === END) return written
            if (chunk == null) {
                at.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING); written += silence.size
                starvedFrames += silence.size
                continue
            }
            var off = 0
            while (off < chunk.size) {
                if (aborted()) { runCatching { at.pause(); at.flush() }; return written }
                val n = minOf(writeChunk, chunk.size - off)
                at.write(chunk, off, n, AudioTrack.WRITE_BLOCKING)
                off += n; written += n
            }
        }
    }

    /** Silence [play] wrote because the next phrase was not rendered yet, for the log. */
    @Volatile private var starvedFrames = 0L
    private val END = FloatArray(0)

    /** Synthesize [text] now, at background priority, and keep the audio for the next [speak] of
     *  exactly this line with the same voice and speed (a few lines at most). */
    override fun prepare(text: String, onDone: () -> Unit) = enqueuePrepare(text, prepareRound, false, onDone)

    /** The drive's next lines, most needed first, then the [keep] pieces. Lines an earlier call
     *  left queued are dropped when the worker reaches them; the one being rendered finishes. */
    override fun prepareAll(texts: List<String>, keep: List<String>) {
        val round = ++prepareRound
        // The line being prepared stops at its next phrase when this set wants a line it has no
        // audio for before it and no longer counts it among its first [URGENT_PREPARES]: a long
        // line for the turn after must not hold up the line at the turn in hand. One the set
        // leaves out, or still has near the top (another wording of the same line may lead this
        // time), finishes: the drive's guess can flip back a second later, and stopping it then
        // cost a whole render. Its finished phrases stay stored either way.
        val inHand = preparing
        if (inHand != null) {
            val at = texts.indexOf(inHand)
            val first = runCatching { val sid = speakerId(); val spd = speed(); texts.firstOrNull { !isPrepared(it, sid, spd) } }.getOrNull()
            if (first != null && first != inHand && at >= URGENT_PREPARES && at > texts.indexOf(first)) preempted = inHand
        }
        texts.forEach { enqueuePrepare(it, round, false) {} }
        if (keep.isNotEmpty()) worker.execute {
            // Pieces kept for another voice, speaker or speed go back to the ordinary store.
            val prefix = pieceKey("", speakerId(), speed())
            synchronized(pieces) { kept.retainAll { it.startsWith(prefix) } }
        }
        keep.forEach { enqueuePrepare(it, round, true) {} }
    }

    /** The line [enqueuePrepare] is rendering, and one a newer [prepareAll] told to stop. */
    @Volatile private var preparing: String? = null
    @Volatile private var preempted: String? = null

    private fun enqueuePrepare(text: String, round: Int, keep: Boolean, onDone: () -> Unit) {
        val asked = speaksAsked
        // A line asked to be spoken since ends this one, unless it is this very line: the render
        // here is then what the speak plays, sooner than starting it again.
        fun overtaken() = asked != speaksAsked && lastSpeak != text
        // Never stopped for a newer guess when it is the line just asked to be spoken.
        fun stopped() = overtaken() || preempted == text && !(asked != speaksAsked && lastSpeak == text)
        worker.execute {
            if (overtaken() || round != prepareRound) { onDone(); return@execute }
            backgroundTid = android.os.Process.myTid()
            setPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                // Read again with the priority down. [speak] counts a line before it raises this
                // thread, so a line asked up to here ends the work and a later one raises it.
                if (overtaken()) return@execute
                val engine = ensureLoaded() ?: return@execute
                val sid = speakerId()
                val spd = speed()
                // Another line asked to be SPOKEN while this one renders ends it at the next phrase:
                // a turn about to be missed must not wait behind audio for one that is a minute
                // away. The phrase in hand finishes first, raised by [boostBackground]. When the
                // line asked is this one, it renders on, raised, and the speak plays it. Phrases
                // already stored are only marked used, so the lines the drive still wants outlast
                // old ones.
                preparing = text
                val r0 = android.os.SystemClock.elapsedRealtime()
                val (seconds, rendered) = synthesize(engine, text, sid, spd, keep) { stopped() } ?: run {
                    Log.i(TAG, "prepare stopped after ${android.os.SystemClock.elapsedRealtime() - r0} ms${if (preempted == text) " for a newer guess" else ""}")
                    return@execute
                }
                if (rendered > 0) Log.i(TAG, "${if (keep) "kept" else "prepared"} ${"%.1f".format(seconds)}s of audio ahead in ${android.os.SystemClock.elapsedRealtime() - r0} ms, $rendered of ${SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC).size} phrases rendered")
            } catch (t: Throwable) {
                Log.w(TAG, "prepare failed: ${t.message}")
            } finally {
                preparing = null
                if (preempted == text) preempted = null
                backgroundTid = 0
                setPriority(SPEAK_PRIORITY)
                onDone()
            }
        }
    }

    /** A rendered phrase as 16-bit samples, half the memory of the float audio. */
    private class Piece(val pcm: ShortArray, val rate: Int)

    /**
     * Rendered phrases by voice, speaker, speed and text ([pieceKey]), least recently used first.
     * A line is prepared when all its phrases are here, so the wordings of one line at two
     * distances share their rest ("In 500 feet," and "In 300 feet," before the same turn), and a
     * phrase rendered once never renders again while it is kept. [kept] pieces ("Rerouting", the
     * spoken distances) stay while their voice, speaker and speed are in use; the others go oldest
     * first past [PIECE_BUDGET_SEC] of audio. Written on [worker] only.
     */
    private val pieces = LinkedHashMap<String, Piece>(64, 0.75f, true)
    private val kept = HashSet<String>()

    private fun pieceKey(frag: String, sid: Int, spd: Float) = "$loadedVoiceId|$sid|$spd|$frag"

    /** A stored phrase as fresh float audio (feeding levels it in place); marks it used. */
    private fun storedPiece(key: String): Pair<FloatArray, Int>? {
        val p = synchronized(pieces) { pieces[key] } ?: return null
        return FloatArray(p.pcm.size) { p.pcm[it] / PCM_SCALE } to p.rate
    }

    /** Stores [audio] under [key], or with [audio] null only marks the stored piece [keep]. */
    private fun storePiece(key: String, audio: FloatArray?, rate: Int, keep: Boolean = false) {
        synchronized(pieces) {
            if (audio != null) pieces[key] = Piece(ShortArray(audio.size) { (audio[it].coerceIn(-1f, 1f) * PCM_SCALE).toInt().toShort() }, rate)
            if (keep) kept.add(key)
            var loose = pieces.entries.sumOf { (k, p) -> if (k in kept) 0L else p.pcm.size.toLong() }
            val budget = (PIECE_BUDGET_SEC * rate).toLong()
            val it = pieces.entries.iterator()
            while (loose > budget && it.hasNext()) {
                val e = it.next()
                if (e.key in kept || e.key == key) continue
                loose -= e.value.pcm.size
                it.remove()
            }
        }
    }

    /** Drops every stored phrase: the voice they were rendered with is gone. */
    private fun clearPieces() = synchronized(pieces) { pieces.clear(); kept.clear() }

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
        sayer.execute { runCatching { track?.pause(); track?.flush() } }
    }

    override fun release() {
        generation++
        sayer.execute { runCatching { track?.release() }; track = null }
        worker.execute { runCatching { tts?.release() }; tts = null; clearPieces() }
    }

    private companion object {
        /**
         * The synthesizer's nice value while it SPEAKS. Measured on a Pixel 4a at a demo drive's
         * start (2026-09-28, Perfetto): at the default priority the two-thread VITS run took 8.9 s
         * of CPU in the first 17 s of the drive, more than a core at times, and the map's GL thread
         * sat runnable-but-waiting 50 to 160 ms of every second while the map ran at 2 to 30 fps.
         * Nice 8 keeps the thread in the foreground group (BACKGROUND would move it to the
         * background cgroup, where a prompt could take ten seconds) but lets the render thread and
         * the tile workers take the core first; a prompt only slows while the map is busy.
         */
        const val SPEAK_PRIORITY = android.os.Process.THREAD_PRIORITY_DEFAULT + 8
        /** Synthesis threads, read at engine load. Test dial `debug.vela.tune.synthThreads`. One
         *  thread was measured on the 4a at 3x playback (2026-10-02): prompts ~30% slower to
         *  synthesize, and the frame dips at turns no different, so two stays the default. */
        val SYNTH_THREADS: Int get() = app.vela.ui.AppTune.local("synthThreads")?.toInt()?.coerceIn(1, 4) ?: 2
        /** Audio kept in [pieces] besides the kept ones, the least recently used dropped first:
         *  a drive's opener and its first turns' prompts, or the two turns ahead with each line's
         *  possible distances (about 2.6 MB at 22050 Hz). */
        const val PIECE_BUDGET_SEC = 60
        const val PCM_SCALE = 32767f
        /** The lines at the head of a [prepareAll] set: the one being prepared is not stopped
         *  for a newer set while it is among them. */
        const val URGENT_PREPARES = 2
        const val TAG = "PiperSynth"
        const val WORKER_NAME = "piper-tts"
        const val SPEED = 1.0f
        // Silence spliced between sentences (seconds) — a natural period beat for nav prompts.
        const val PAUSE_SEC = 0.32f
        // Shorter beat spliced at commas/semicolons so clauses don't run together ("In a quarter mile, …").
        const val CLAUSE_PAUSE_SEC = 0.16f
        // Breath between BACK-TO-BACK prompts (after the drain): consecutive directions don't butt
        // against each other. Skipped when an interrupting prompt is waiting (generation moved).
        /** The speech speed [PAUSE_SEC] and [CLAUSE_PAUSE_SEC] were tuned at (the shipped default). */
        const val PAUSE_TUNED_SPEED = 0.8f
        const val INTER_PROMPT_GAP_MS = 350L
        /** Silence fed to the stream while the next phrase is still rendering. */
        const val STARVE_MS = 60
        /** Mid-line silence from this long on is noted in the trip. */
        const val STARVE_NOTE_MS = 300L
    }
}
