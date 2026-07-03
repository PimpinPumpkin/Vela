package app.vela.core.voice

import android.content.Context
import java.io.File

/**
 * Vela's on-device neural voice ENGINE — one Piper VITS engine ([ENGINE_ID]) that can hold ANY of
 * several downloadable Piper models. The *engine* is what shows in the Settings engine radios; the
 * *model* (voice) is chosen in the "Voice library" browser and persisted under [PREF_MODEL].
 *
 * Each downloaded voice lives in its own subdir `filesDir/piper/<voiceId>/` containing
 * `<voiceId>.onnx`, `tokens.txt`, `espeak-ng-data/` (the exact layout of a `vits-piper-<id>` sherpa
 * archive). The **installed set is derived from the filesystem, never a mutable pref** — so a
 * crashed/partial download self-heals (an incomplete dir simply isn't "ready").
 *
 * Voices come from the sherpa-onnx `tts-models` release (see [PiperCatalog]); the runtime is the
 * bundled sherpa-onnx (arm64). Kept UI-agnostic in `:core`; `:app`'s `PiperSynth` loads [resolved].
 */
object VelaPiper {
    const val ENGINE_ID = "vela.piper" // STABLE — do not change (VoiceGuide routing + the voice_engine pref)
    const val LABEL = "Vela voice"

    /** The fleet default voice (its speaker is what [Calibration.defaultVoiceSpeaker] tunes). */
    const val DEFAULT_VOICE_ID = "en_US-libritts_r-medium"

    /** The single voice the old single-model build shipped — flat under `filesDir/piper` before the browser. */
    const val LEGACY_ID = "en_US-libritts_r-medium"

    const val PREF_MODEL = "voice_model" // String: the selected Piper voice id
    private const val PREFS = "vela_settings"

    fun rootDir(context: Context): File = File(context.filesDir, "piper") // the "voices home"
    fun modelDirFor(context: Context, id: String): File = File(rootDir(context), id)
    fun onnxName(id: String): String = "$id.onnx"

    /** A voice dir is complete iff it has `<id>.onnx` + `tokens.txt` + `espeak-ng-data/`. */
    fun isVoiceReady(context: Context, id: String): Boolean {
        val d = modelDirFor(context, id)
        return File(d, onnxName(id)).exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data").isDirectory
    }

    /** The filesystem IS the registry: scan subdirs, keep only complete ones, sorted. Self-heals. */
    fun installedVoiceIds(context: Context): List<String> =
        rootDir(context).listFiles { f -> f.isDirectory }.orEmpty()
            .map { it.name }.filter { isVoiceReady(context, it) }.sorted()

    /** Raw pref value (may name a deleted / never-installed voice), or null. */
    fun selectedVoicePref(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_MODEL, null)

    fun setSelectedVoiceId(context: Context, id: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_MODEL, id).apply()

    fun clearSelectedVoice(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_MODEL).apply()

    /** The voice to actually LOAD: the pref if it's installed, else the first installed, else null.
     *  NEVER returns an un-downloaded id (so the synth can't point at missing files). */
    fun effectiveVoiceId(context: Context): String? {
        val sel = selectedVoicePref(context)
        if (sel != null && isVoiceReady(context, sel)) return sel
        return installedVoiceIds(context).firstOrNull()
    }

    /** Concrete file paths the synth loads for the current selection, or null if nothing usable. */
    fun resolved(context: Context): Resolved? {
        val id = effectiveVoiceId(context) ?: return null
        val d = modelDirFor(context, id)
        return Resolved(id, File(d, onnxName(id)).path, File(d, "tokens.txt").path, File(d, "espeak-ng-data").path)
    }

    data class Resolved(val voiceId: String, val model: String, val tokens: String, val dataDir: String)

    /** ANY complete voice present → the neural engine is offerable (replaces the old MODEL-based check). */
    fun isReady(context: Context): Boolean = effectiveVoiceId(context) != null

    /** Per-voice speaker pref key (multi-speaker voices: libritts_r=904, vctk=109); single-speaker → unused. */
    fun speakerKey(id: String): String = "voice_speaker_$id"

    // ---- one-time migration: flat filesDir/piper/* install → filesDir/piper/<LEGACY_ID>/ ----
    /**
     * Move the pre-browser single-voice install (flat `filesDir/piper/{<LEGACY_ID>.onnx, tokens.txt,
     * espeak-ng-data/}`) into the per-voice subdir, in place — **no copy of the 82 MB model, no
     * re-download**. Idempotent and **crash-safe / re-runnable**: it triggers whenever ANY flat legacy
     * member still exists (so a process killed mid-move finishes on the next launch), each move is a
     * `renameTo` with a `copy`-then-`delete` fallback when rename can't (return value is CHECKED — a
     * silent false used to strand the user's only voice), and the selection is only seeded once the
     * destination verifies complete. Same-filesystem (internal storage) so rename is normally instant.
     */
    fun migrateFlatLayoutIfNeeded(context: Context) {
        val root = rootDir(context)
        val flatOnnx = File(root, onnxName(LEGACY_ID))
        val flatTokens = File(root, "tokens.txt")
        val flatEspeak = File(root, "espeak-ng-data")
        val anyFlat = flatOnnx.exists() || flatTokens.exists() || flatEspeak.isDirectory
        if (!anyFlat) return // already migrated (or never installed)

        val dest = modelDirFor(context, LEGACY_ID).apply { mkdirs() }
        moveInto(flatOnnx, File(dest, onnxName(LEGACY_ID)))
        moveInto(flatTokens, File(dest, "tokens.txt"))
        moveDirInto(flatEspeak, File(dest, "espeak-ng-data"))

        if (isVoiceReady(context, LEGACY_ID)) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Keep their existing voice as the active selection.
            if (prefs.getString(PREF_MODEL, null) == null) prefs.edit().putString(PREF_MODEL, LEGACY_ID).apply()
            // Carry their calibrated speaker over to the per-voice key (only if unset).
            if (!prefs.contains(speakerKey(LEGACY_ID)) && prefs.contains("voice_speaker")) {
                prefs.edit().putInt(speakerKey(LEGACY_ID), prefs.getInt("voice_speaker", 0)).apply()
            }
        }
    }

    /** Move a file: no-op if absent; if the destination already has it, just drop the stray source;
     *  try `renameTo`, and on failure (cross-dir edge / FS quirk) fall back to copy-then-delete. */
    private fun moveInto(src: File, dst: File) {
        if (!src.exists()) return
        if (dst.exists()) { src.delete(); return }
        if (src.renameTo(dst)) return
        runCatching { src.copyTo(dst, overwrite = true); src.delete() }
    }

    /** Move a directory (espeak-ng-data): rename, or copyRecursively + deleteRecursively on failure. */
    private fun moveDirInto(src: File, dst: File) {
        if (!src.isDirectory) return
        if (dst.isDirectory) { src.deleteRecursively(); return }
        if (src.renameTo(dst)) return
        runCatching { src.copyRecursively(dst, overwrite = true); src.deleteRecursively() }
    }
}
