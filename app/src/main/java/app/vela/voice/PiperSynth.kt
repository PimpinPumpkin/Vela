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
 * Vela's neural voice: a Piper VITS model via sherpa-onnx [OfflineTts] → [AudioTrack]. Single
 * worker thread, generation-counter interrupt, full-utterance-then-play. Piper runs at/above
 * realtime, so nav prompts are timely (the slower Kokoro/Matcha models were dropped 2026-07).
 * Sample rate is read from the generated audio (Piper=22050).
 */
@Singleton
class PiperSynth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibration: app.vela.core.config.CalibrationStore,
) : NeuralSynth {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(SPEAK_PRIORITY); r.run() }, "piper-tts").apply { isDaemon = true }
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

    /** Guidance-volume multiplier (Settings > Voice, issue #245): the neural voice was easy to
     *  bury under music because the synthesized PCM peaks well below full scale. Applied as a
     *  plain gain over the float samples, hard-clipped at full scale - speech rarely peaks
     *  there, so the boosted settings stay clean and only the loudest syllables flatten. */
    private fun volume(): Float =
        context.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .getFloat("voice_volume", 1.0f).coerceIn(0.2f, 3.0f)

    override fun warmUp() {
        // No `tts != null` short-circuit: ensureLoaded must be able to REBUILD when the selected voice
        // changed. It's idempotent per-voice (returns the current engine when the right voice is up), so
        // a warm-up on the already-loaded voice is a cheap no-op.
        if (loadFailed || !VelaPiper.isReady(context)) return
        // BACKGROUND priority while warming (see AsrRecognizer.warmUp: the load competed with the
        // map's render thread at launch). The worker is also the thread that speaks, so a prompt
        // queued behind a slow background load raises it back ([speak] calls [boostWarm]).
        worker.execute {
            warmingTid = android.os.Process.myTid()
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try { ensureLoaded() } finally {
                warmingTid = 0
                android.os.Process.setThreadPriority(SPEAK_PRIORITY)
            }
        }
    }

    @Volatile private var warmingTid = 0

    /** A prompt is waiting: finish any background warm-up at the speaking priority (the default one
     *  for an urgent line: the drive's opener waited 7.5 s on a cold start on the 4a, 2026-10-03). */
    private fun boostWarm(urgent: Boolean = false) {
        val tid = warmingTid
        if (tid != 0) runCatching { android.os.Process.setThreadPriority(tid, if (urgent) android.os.Process.THREAD_PRIORITY_DEFAULT else SPEAK_PRIORITY) }
    }

    /** Bumped by every [speak]: a [prepare] still queued when a real line is asked for is dropped,
     *  so prepare-ahead work never sits between Start and the opener (four queued prepares held a
     *  cold-start opener back by another 5 s on the 4a). */
    @Volatile private var speaksAsked = 0


    private fun ensureLoaded(): OfflineTts? {
        val r = VelaPiper.resolved(context) ?: return null // nothing usable installed
        val cur = tts
        if (cur != null && loadedVoiceId == r.voiceId && !loadFailed) return cur // right voice already up
        // A switch (or first load) → (re)build. Tear the old engine down first: we ARE the single worker
        // thread, so no generate() can be running concurrently → releasing here is safe (never a
        // use-after-free). loadFailed resets so a previously-bad voice doesn't block a new one.
        runCatching { cur?.release() }
        tts = null; loadedVoiceId = null; numSpeakers = 0; loadFailed = false
        val loadStart = android.os.SystemClock.elapsedRealtime()
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
        speaksAsked++
        boostWarm(interrupt)
        val myGen = if (interrupt) ++generation else generation
        val asked = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            val engine = ensureLoaded()
            if (engine == null || myGen != generation) { onDone(); return@execute }
            // An imminent-turn prompt (interrupt: it cuts whatever is playing) keeps the default
            // priority so a busy map cannot delay it; the far prompts and the opener yield.
            runCatching { android.os.Process.setThreadPriority(if (interrupt) android.os.Process.THREAD_PRIORITY_DEFAULT else SPEAK_PRIORITY) }
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val sid = speakerId()
                val spd = speed()
                // Audio [prepare] made ahead of time for exactly this line plays at once: the drive's
                // opener is prepared while the route preview is up, so Start does not synthesize while
                // the camera flies in (two synth threads took the 4a's fast cores from the map).
                val ready = synchronized(prepared) { prepared.remove(preparedKey(text, sid, spd)) }
                val vol = volume()
                val aborted = { myGen != generation }
                // PLAY WHILE RENDERING (2026-10-04): a line is rendered phrase by phrase (see
                // [synthesize]), and the first phrase used to wait for the last. Each phrase now goes
                // to the player as it is made, so the wait before the first word is one phrase's
                // render, not the line's. The player keeps the stream fed with silence when the next
                // phrase is not ready (a longer beat at a comma, never an underrun: see STARVE_MS).
                val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
                var sampleRate = 22050
                var player: java.util.concurrent.Future<Long>? = null
                var firstAudioMs = -1L
                var made = 0
                fun feed(chunk: FloatArray, rate: Int) {
                    if (chunk.isEmpty()) return
                    if (vol != 1.0f) for (i in chunk.indices) chunk[i] = (chunk[i] * vol).coerceIn(-1f, 1f)
                    made += chunk.size
                    queue.put(chunk)
                    if (player == null) {
                        sampleRate = rate
                        firstAudioMs = android.os.SystemClock.elapsedRealtime() - t0
                        val at = ensureTrack(rate)
                        player = playerThread.submit<Long> { play(at, rate, queue, aborted) }
                    }
                }
                if (ready != null) feed(ready.first, ready.second)
                else {
                    val frags = SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC)
                    for ((frag, gapAfter) in frags) {
                        if (aborted()) break
                        val (audio, rate) = render(engine, frag, sid, spd)
                        feed(audio, rate)
                        if (gapAfter > 0f) feed(FloatArray(gapFrames(rate, gapAfter, spd)), rate)
                    }
                }
                queue.put(END)
                val genMs = android.os.SystemClock.elapsedRealtime() - t0
                // How long a line waited (queue + model load) and took to make: a late "Starting
                // navigation" on a slow head unit is one of the two (2026-10-03). No text: lengths only.
                Log.i(TAG, "speak: waited ${t0 - asked} ms, ${if (ready != null) "prepared" else "first audio after $firstAudioMs ms, all rendered in $genMs ms"}, ${text.length} chars")
                val p = player
                if (p != null) {
                    val at = track ?: return@execute
                    // Frames the player wrote, the fed silence included: what the track must play out.
                    val written = runCatching { p.get() }.getOrDefault(0L)
                    // DRAIN before finishing: the last write returns while the track buffer's tail
                    // (~1 s — bufferSize is sampleRate*4 bytes = 1 s of float mono) is still PLAYING,
                    // and the NEXT queued prompt's pause+flush would chop it — the "spoken directions
                    // partially stacking" bug (the end of one direction swallowed as the next began,
                    // worst at nav start where the opener + first approach prompt queue back-to-back).
                    // Wait until the audio truly ends, then leave a short breath between prompts
                    // (Google finishes the sentence, beat, then speaks the next). INTERRUPTS STAY
                    // INSTANT: an interrupting speak bumps [generation] and this loop bails within
                    // ~30 ms — the urgent prompt then flushes the tail exactly as before. onDone
                    // (audio-focus release) also now fires at the REAL end of audio, so music no
                    // longer un-ducks over the last words.
                    val deadline = android.os.SystemClock.elapsedRealtime() + 2000L
                    while (myGen == generation &&
                        runCatching { at.playbackHeadPosition.toLong() }.getOrDefault(written) < written &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                    ) {
                        Thread.sleep(30)
                    }
                    if (myGen == generation) Thread.sleep(INTER_PROMPT_GAP_MS)
                    // Played out: pause the track, so the audio output can go to standby between
                    // prompts instead of holding a PLAYING stream (the next prompt plays it again).
                    if (myGen == generation) runCatching { at.pause() }
                }
                Log.i(TAG, "spoke ${"%.1f".format(made / sampleRate.toFloat())}s audio (${if (ready != null) "prepared" else "streamed"}) in ${genMs}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed: ${t.message}", t)
            } finally {
                onDone()
            }
        }
    }

    /** Synthesize [text] the way [speak] voices it: phrase fragments, each with terminal
     *  punctuation, and a spliced silence after each. Null when [aborted] turns true mid-way. */
    private fun synthesize(engine: OfflineTts, text: String, sid: Int, spd: Float, aborted: () -> Boolean): Pair<FloatArray, Int>? {
        // Synthesize each sentence on its own and splice a fixed silence gap between them, so
        // periods get a real, controllable beat. sherpa-onnx's own `silenceScale` config is a
        // no-op for this Piper/VITS path (measured on-device: 0.2 vs 1.4 gave identical audio
        // length), and one-shot generation runs sentences together, so we do the pausing here.
        // Break into phrase fragments (sentences + comma/semicolon clauses), synth each on its
        // own, and splice the tagged silence after it: a firm beat at periods, a shorter one at
        // commas (Piper reads straight through commas otherwise, running "In a quarter mile, turn
        // right" together). The split lives in :core so it's unit-tested (see SpeechText).
        val frags = SpeechText.speechFragments(text, PAUSE_SEC, CLAUSE_PAUSE_SEC)
        var sampleRate = 22050
        val chunks = ArrayList<FloatArray>(frags.size * 2)
        for ((frag, gapAfter) in frags) {
            if (aborted()) return null
            // Every fragment gets TERMINAL PUNCTUATION before synthesis: a bare-ending fragment
            // ("turn left") gives the model no final prosody contour, so it trails off and swallows
            // the last consonant, the real-drive "lef" instead of "left" (user 2026-07-06). The
            // semicolon contour was A/B'd best on this voice (see EnNavStrings.arrived).
            val (audio, rate) = render(engine, frag, sid, spd)
            sampleRate = rate
            if (audio.isNotEmpty()) chunks.add(audio)
            if (gapAfter > 0f) chunks.add(FloatArray(gapFrames(sampleRate, gapAfter, spd))) // spliced silence
        }
        return concat(chunks) to sampleRate
    }

    /** A spliced pause in frames. The pause lengths were tuned by ear at [PAUSE_TUNED_SPEED], so
     *  they stretch and shrink with the speed setting: twice as fast, half the pause. */
    private fun gapFrames(sampleRate: Int, gapSec: Float, spd: Float): Int =
        (sampleRate * gapSec * PAUSE_TUNED_SPEED / spd.coerceAtLeast(0.1f)).toInt()

    /** One phrase's audio and its sample rate. */
    private fun render(engine: OfflineTts, frag: String, sid: Int, spd: Float): Pair<FloatArray, Int> {
        val fragText = if (frag.lastOrNull()?.isLetterOrDigit() == true) "$frag;" else frag
        val a = engine.generate(text = fragText, sid = sid, speed = spd)
        return a.samples to a.sampleRate
    }

    /** Plays queued audio until [END], on [playerThread]; returns the frames written. Writes in
     *  ~200 ms pieces with an abort check between them, so an INTERRUPT (turn-now / rerouting /
     *  stop-nav bumps `generation`) takes effect within ~200 ms; on abort, pause+flush kills the
     *  buffered tail so the urgent prompt is not preceded by stale audio. When the next phrase is
     *  not rendered yet it writes [STARVE_MS] of silence, so the stream never underruns (a
     *  starved stream was the SIGABRT of the first streaming attempt). */
    private fun play(at: AudioTrack, sampleRate: Int, queue: java.util.concurrent.BlockingQueue<FloatArray>, aborted: () -> Boolean): Long {
        runCatching { at.pause(); at.flush(); at.play() }
        val writeChunk = sampleRate / 5
        val silence = FloatArray(sampleRate * STARVE_MS / 1000)
        var written = 0L
        while (true) {
            if (aborted()) { runCatching { at.pause(); at.flush() }; return written }
            val chunk = queue.poll(STARVE_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (chunk === END) return written
            if (chunk == null) {
                at.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING); written += silence.size
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

    private val playerThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "piper-play") }
    private val END = FloatArray(0)

    /** Synthesize [text] now, at background priority, and keep the audio for the next [speak] of
     *  exactly this line with the same voice and speed (a few lines at most). */
    override fun prepare(text: String, onDone: () -> Unit) {
        val asked = speaksAsked
        worker.execute {
            if (asked != speaksAsked) { onDone(); return@execute }
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                val engine = ensureLoaded() ?: return@execute
                val sid = speakerId()
                val spd = speed()
                val key = preparedKey(text, sid, spd)
                if (synchronized(prepared) { prepared.containsKey(key) }) return@execute
                val made = synthesize(engine, text, sid, spd) { false } ?: return@execute
                synchronized(prepared) {
                    prepared[key] = made
                    while (prepared.size > MAX_PREPARED) prepared.remove(prepared.keys.first())
                }
                Log.i(TAG, "prepared ${"%.1f".format(made.first.size / made.second.toFloat())}s of audio ahead")
            } catch (t: Throwable) {
                Log.w(TAG, "prepare failed: ${t.message}")
            } finally {
                runCatching { android.os.Process.setThreadPriority(SPEAK_PRIORITY) }
                onDone()
            }
        }
    }

    private val prepared = LinkedHashMap<String, Pair<FloatArray, Int>>()
    private fun preparedKey(text: String, sid: Int, spd: Float) = "$loadedVoiceId|$sid|$spd|$text"

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
        /** Prepared lines kept at once: a drive's opener and its first turns' prompts (up to 7). */
        const val MAX_PREPARED = 8
        const val TAG = "PiperSynth"
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
    }
}
