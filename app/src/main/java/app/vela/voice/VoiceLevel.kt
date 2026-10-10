package app.vela.voice

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * How loud the Vela voice plays (Settings > Voice, "Guidance volume").
 *
 * The neural voices render well under full scale: Kokoro's default voice has an RMS near 0.055
 * and peaks near 0.3, so at a gain of 1 it played about 10 dB under a voice that fills the range.
 * Each voice is brought up so its average level lands at [TARGET_RMS], and the setting scales
 * from there. What that pushes past [KNEE] is rounded off by [limit] instead of being cut flat.
 *
 * The level is the voice's long-run RMS ([Meter]), kept between sessions. A voice's loudest
 * sample keeps rising for as long as it talks (0.23 on the first phrase, 0.42 a minute later on
 * a Pixel 4a), and a gain set from that fell to nearly half over the first minute of a drive.
 * The RMS of the same voice moved 3 percent between phrases.
 */
internal object VoiceLevel {
    /** Where a voice's average level is brought to at the Normal setting. */
    const val TARGET_RMS = 0.13f

    /** The most a voice is ever raised to get there, so a near-silent render is not amplified into noise. */
    const val MAX_LIFT = 4f

    /** Below this a sample passes unchanged; above it the limiter bends it toward full scale. */
    const val KNEE = 0.8f

    /** A chunk whose loudest sample is under this is a pause, not speech, and is not measured. */
    const val MIN_PEAK = 0.02f

    /** A voice's average level over everything it has said: the sum of squares and the count of
     *  the samples in its speech chunks. Pauses are left out, or a line with long gaps would read
     *  as a quiet voice. */
    class Meter(var sumSq: Double = 0.0, var count: Long = 0L) {
        fun add(chunk: FloatArray) {
            if (peak(chunk) < MIN_PEAK) return
            var s = 0.0
            for (x in chunk) s += x * x
            sumSq += s
            count += chunk.size
        }

        /** 0 until something has been measured. */
        val rms: Float get() = if (count > 0) sqrt(sumSq / count).toFloat() else 0f

        fun encode(): String = "$sumSq|$count"

        companion object {
            fun decode(s: String?): Meter {
                val p = s?.split('|') ?: return Meter()
                val sum = p.getOrNull(0)?.toDoubleOrNull() ?: return Meter()
                val n = p.getOrNull(1)?.toLongOrNull() ?: return Meter()
                return if (sum.isFinite() && sum >= 0.0 && n > 0) Meter(sum, n) else Meter()
            }
        }
    }

    /** The gain that brings a voice whose level is [voiceRms] to [TARGET_RMS], times the user's
     *  [setting] (0.6 softer, 1 normal, 1.6 louder, 2.2 loudest). Never lowers a voice that is
     *  already loud: the lift alone is at least 1. An unmeasured voice gets the setting alone. */
    fun gain(voiceRms: Float, setting: Float): Float {
        val lift = if (voiceRms <= 0f || voiceRms.isNaN()) 1f else (TARGET_RMS / voiceRms).coerceIn(1f, MAX_LIFT)
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
