package app.vela.voice

import kotlin.math.abs
import kotlin.math.tanh

/**
 * How loud the Vela voice plays (Settings > Voice, "Guidance volume").
 *
 * The neural voices render well under full scale: Kokoro's default voice peaks near 0.29, so at
 * a gain of 1 it played about 10 dB under a voice that fills the range, and the old Louder and
 * Loudest settings (plain gains of 1.6 and 2.2) still left it under. The voice is now brought up
 * so its measured peak lands at [TARGET_PEAK], and the setting scales from there. What that
 * pushes past [KNEE] is rounded off by [limit] instead of being cut flat.
 */
internal object VoiceLevel {
    /** Where the voice's own peak is brought to at the Normal setting. */
    const val TARGET_PEAK = 0.85f

    /** The most the voice is ever raised to get there, so a near-silent render is not amplified into noise. */
    const val MAX_LIFT = 4f

    /** Below this a sample passes unchanged; above it the limiter bends it toward full scale. */
    const val KNEE = 0.8f

    /** A measured peak under this is not speech worth leveling against. */
    const val MIN_PEAK = 0.02f

    /** The gain that brings a voice peaking at [voicePeak] to [TARGET_PEAK], times the user's
     *  [setting] (0.6 softer, 1 normal, 1.6 louder, 2.2 loudest). Never lowers a voice that is
     *  already loud: the lift alone is at least 1. */
    fun gain(voicePeak: Float, setting: Float): Float {
        val lift = if (voicePeak < MIN_PEAK || voicePeak.isNaN()) 1f else (TARGET_PEAK / voicePeak).coerceIn(1f, MAX_LIFT)
        return lift * setting
    }

    /** One sample after [gain]: linear up to [KNEE], then a smooth curve that reaches full scale
     *  only in the limit, so loud syllables round off instead of clipping. */
    fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -y else y
    }

    /** The largest absolute sample in [chunk]. */
    fun peak(chunk: FloatArray): Float {
        var p = 0f
        for (x in chunk) { val a = abs(x); if (a > p) p = a }
        return p
    }

    /** [chunk] scaled by [gain] and limited, in place. */
    fun apply(chunk: FloatArray, gain: Float) {
        if (gain == 1f) {
            for (i in chunk.indices) if (abs(chunk[i]) > KNEE) chunk[i] = limit(chunk[i])
            return
        }
        for (i in chunk.indices) chunk[i] = limit(chunk[i] * gain)
    }
}
