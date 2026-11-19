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

    /** [chunk] scaled by [gain] and limited, in place.
     *
     *  At high boost (above 3x user volume, where [gain] can reach 7..12 on a quiet neural voice),
     *  sample-by-sample waveshaping alone square-waves vowel peaks and exaggerates 2.5-4 kHz
     *  vocoder resonances. When [gain] pushes the chunk past [KNEE], we first remove sub-bass
     *  plosive rumble, tame harsh upper-midrange spikes, and ride a smooth peak envelope so whole
     *  waveform cycles scale together instead of flat-topping. */
    fun apply(chunk: FloatArray, gain: Float) {
        if (chunk.isEmpty()) return
        if (gain == 1f) {
            for (i in chunk.indices) if (abs(chunk[i]) > KNEE) chunk[i] = limit(chunk[i])
            return
        }
        val rawPeak = peak(chunk)
        if (rawPeak * gain <= KNEE || chunk.size < 64) {
            for (i in chunk.indices) chunk[i] = limit(chunk[i] * gain)
            return
        }
        // 1. High-pass at ~85 Hz (at 22.05 kHz) and two-band dynamic de-harshing when boosted:
        //    - Upper-mid band (~1.2-3.4 kHz): tames piercing vowel formants (especially on female voices).
        //    - High sibilance band (>3.4 kHz): de-esses sharp fricatives and vocoder buzz.
        if (gain > 3.2f) {
            val hpAlpha = 0.976f
            var prevIn = chunk[0]
            var prevHp = chunk[0]
            var lpMid = 0f
            var lpHigh = 0f
            val lpMidAlpha = 0.24f  // splits around ~1.2 kHz (female upper-mid formant band above this)
            val lpHighAlpha = 0.52f // splits around ~3.4 kHz (high sibilance band above this)
            val midDeHarsh = ((gain - 3.2f) / 6f).coerceIn(0f, 0.42f)
            val hiDeHarsh = ((gain - 3.2f) / 5f).coerceIn(0f, 0.55f)
            for (i in chunk.indices) {
                val x = chunk[i]
                val hp = hpAlpha * (prevHp + x - prevIn)
                prevIn = x
                prevHp = hp
                lpMid += lpMidAlpha * (hp - lpMid)
                lpHigh += lpHighAlpha * (hp - lpHigh)
                val low = lpMid
                val mid = lpHigh - lpMid
                val hi = hp - lpHigh
                val midAbs = abs(mid) * gain
                val midScale = if (midAbs > 0.36f) 1f - midDeHarsh * ((midAbs - 0.36f) / midAbs) else 1f
                val hiAbs = abs(hi) * gain
                val hiScale = if (hiAbs > 0.26f) 1f - hiDeHarsh * ((hiAbs - 0.26f) / hiAbs) else 1f
                chunk[i] = low + mid * midScale + hi * hiScale
            }
        }
        // 2. Smooth look-ahead peak envelope follower so loud syllables compress cleanly without
        //    clipping individual wave crests into square waves.
        val n = chunk.size
        val lookAhead = 32
        val attack = 0.12f
        val release = 0.0018f
        var env = 0f
        for (i in 0 until minOf(n, lookAhead)) {
            val a = abs(chunk[i]) * gain
            if (a > env) env = a
        }
        for (i in 0 until n) {
            val lookIdx = (i + lookAhead).coerceAtMost(n - 1)
            var localPeak = abs(chunk[i]) * gain
            val ahead = abs(chunk[lookIdx]) * gain
            if (ahead > localPeak) localPeak = ahead
            env += if (localPeak > env) attack * (localPeak - env) else release * (localPeak - env)
            val scaled = if (env > KNEE) {
                val targetEnv = KNEE + (0.96f - KNEE) * tanh((env - KNEE) / (1.35f - KNEE))
                chunk[i] * gain * (targetEnv / env)
            } else {
                chunk[i] * gain
            }
            chunk[i] = limit(scaled)
        }
    }
}
