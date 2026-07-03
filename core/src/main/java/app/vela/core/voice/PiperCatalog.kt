package app.vela.core.voice

/** Voice quality tier (Piper trains low / medium / high variants of the same speaker). */
enum class VoiceQuality { LOW, MEDIUM, HIGH }

/** MULTI = a multi-speaker pack (libritts_r=904, vctk=109) — audition variants in the playground. */
enum class VoiceGender { FEMALE, MALE, NEUTRAL, MULTI }

enum class VoiceAccent { US, GB }

/**
 * One browsable Piper voice. [id] is simultaneously the sherpa asset id, the on-disk dir name, and
 * the `.onnx` file stem (`<id>.onnx`) — the single source of truth. Pure data (no Android types) so
 * it lives in `:core` and is unit-tested.
 */
data class PiperVoice(
    val id: String, // e.g. "en_US-lessac-medium"
    val displayName: String, // "Lessac"
    val accent: VoiceAccent,
    val gender: VoiceGender,
    val quality: VoiceQuality,
    val sizeMb: Int, // full-precision archive size — download label + progress estimate
    val numSpeakers: Int, // 1 = single-speaker; 904 = libritts_r; …
    val note: String? = null, // one short "sounds like" line for the row
    val recommended: Boolean = false, // the handful of best nav voices — floated to the top of a group
    val novelty: Boolean = false, // sinks to the bottom (GLaDOS)
) {
    val sizeBytes: Long get() = sizeMb * 1_000_000L
    val multiSpeaker: Boolean get() = numSpeakers > 1
}

/**
 * The curated catalog of downloadable Piper voices, all packaged on the sherpa-onnx `tts-models`
 * GitHub release as `vits-piper-<id>.tar.bz2`. Compiled-in for v1; a future bet is to fold this into
 * the signed `calibration.json` so new voices ship without an app release (would also require pinning
 * the download host, `github.com`, in the calibration allowlist — see ROADMAP).
 */
object PiperCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    /** Download URL derived purely from the id — matches the sherpa asset naming scheme. */
    fun downloadUrl(id: String): String = "${BASE}vits-piper-$id.tar.bz2"

    /** Curated en_US + en_GB voices worth offering for spoken navigation. Sizes + speaker counts are
     *  the real sherpa asset values. `recommended` = the best few for a maps voice (incl. the ones the
     *  user flagged as Google-like: lessac + hfc_female + the libritts_r default). */
    val ALL: List<PiperVoice> = listOf(
        // — US English —
        PiperVoice("en_US-libritts_r-medium", "LibriTTS-R", VoiceAccent.US, VoiceGender.MULTI, VoiceQuality.MEDIUM, 82, 904, "Vela default · 904 variants to pick from", recommended = true),
        PiperVoice("en_US-lessac-medium", "Lessac", VoiceAccent.US, VoiceGender.FEMALE, VoiceQuality.MEDIUM, 67, 1, "Clear, neutral US female — the classic maps voice", recommended = true),
        PiperVoice("en_US-hfc_female-medium", "HFC Female", VoiceAccent.US, VoiceGender.FEMALE, VoiceQuality.MEDIUM, 67, 1, "Bright, friendly — the most Google-like read", recommended = true),
        PiperVoice("en_US-hfc_male-medium", "HFC Male", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Even, neutral US male — clean maps-style guidance", recommended = true),
        PiperVoice("en_US-ryan-high", "Ryan (HQ)", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.HIGH, 115, 1, "Warm, confident US male at top quality", recommended = true),
        PiperVoice("en_US-amy-medium", "Amy", VoiceAccent.US, VoiceGender.FEMALE, VoiceQuality.MEDIUM, 67, 1, "Soft, calm US female — easy on long drives"),
        PiperVoice("en_US-lessac-high", "Lessac (HQ)", VoiceAccent.US, VoiceGender.FEMALE, VoiceQuality.HIGH, 115, 1, "The neutral maps voice, richer top quality"),
        PiperVoice("en_US-ryan-medium", "Ryan", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Warm US male at the lighter size"),
        PiperVoice("en_US-kristin-medium", "Kristin", VoiceAccent.US, VoiceGender.FEMALE, VoiceQuality.MEDIUM, 67, 1, "Gentle, measured US female — relaxed and clear"),
        PiperVoice("en_US-joe-medium", "Joe", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Laid-back US male — casual, conversational"),
        PiperVoice("en_US-john-medium", "John", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Steady, plain US male — no-nonsense"),
        PiperVoice("en_US-kusal-medium", "Kusal", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Mellow US male with a subtle accent"),
        PiperVoice("en_US-norman-medium", "Norman", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Deeper US male — grounded, authoritative"),
        PiperVoice("en_US-sam-medium", "Sam", VoiceAccent.US, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Bright, upbeat US male — energetic turn cues"),
        PiperVoice("en_US-ljspeech-high", "LJSpeech (HQ)", VoiceAccent.US, VoiceGender.FEMALE, VoiceQuality.HIGH, 115, 1, "Polished audiobook-style US female"),
        PiperVoice("en_US-arctic-medium", "Arctic", VoiceAccent.US, VoiceGender.MULTI, VoiceQuality.MEDIUM, 80, 18, "18-voice US pack — variety in one download"),
        // — British English —
        PiperVoice("en_GB-alba-medium", "Alba", VoiceAccent.GB, VoiceGender.FEMALE, VoiceQuality.MEDIUM, 67, 1, "Scottish-tinged UK female — warm and characterful"),
        PiperVoice("en_GB-jenny_dioco-medium", "Jenny", VoiceAccent.GB, VoiceGender.FEMALE, VoiceQuality.MEDIUM, 67, 1, "Friendly UK female — clear, natural British read"),
        PiperVoice("en_GB-cori-high", "Cori (HQ)", VoiceAccent.GB, VoiceGender.FEMALE, VoiceQuality.HIGH, 115, 1, "Crisp UK female at top quality"),
        PiperVoice("en_GB-northern_english_male-medium", "Northern Male", VoiceAccent.GB, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Northern-English UK male — down-to-earth"),
        PiperVoice("en_GB-alan-medium", "Alan", VoiceAccent.GB, VoiceGender.MALE, VoiceQuality.MEDIUM, 67, 1, "Steady UK male — classic British navigation tone"),
        PiperVoice("en_GB-vctk-medium", "VCTK", VoiceAccent.GB, VoiceGender.MULTI, VoiceQuality.MEDIUM, 80, 109, "109-voice UK pack — a huge range of accents"),
        // — Novelty —
        PiperVoice("en_US-glados-high", "GLaDOS", VoiceAccent.US, VoiceGender.NEUTRAL, VoiceQuality.HIGH, 115, 1, "Portal's GLaDOS — deadpan robotic, for fun", novelty = true),
    )

    fun byId(id: String): PiperVoice? = ALL.firstOrNull { it.id == id }
}
