package app.vela.core.voice

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The Guidance volume setting (Settings > Voice: 0.6 softer, 1 normal, 1.6 louder, 2.2 loudest)
 * for a voice the phone's own engine speaks (issue #979).
 *
 * The framework clips the engine's volume parameter (`KEY_PARAM_VOLUME`) to 0..1, so that only
 * turns a voice down. Above Normal, [VoiceGuide] plays the line on an audio session of its own
 * with a `LoudnessEnhancer` on it, set to [gainMb]. The steps raise the engine's voice by the
 * same factor they raise the Vela voice over its Normal level, and no further than Loudest.
 */
object SystemVoiceBoost {
    /** The highest setting the boost follows: Loudest, 2.2 times Normal (+6.8 dB). */
    const val MAX_SETTING = 2.2f

    /** The engine's own volume parameter for [setting]: the setting at Normal and below, 1 above,
     *  where the enhancer takes over. */
    fun engineVolume(setting: Float): Float = setting.coerceAtMost(1f)

    /** The enhancer's target gain in millibels for [setting]: 0 at Normal and below, where no
     *  enhancer or session is used, else 2000 log10 of the setting, capped at [MAX_SETTING].
     *  Louder (1.6) is 408, Loudest (2.2) is 685. */
    fun gainMb(setting: Float): Int {
        if (setting.isNaN() || setting <= 1f) return 0
        return (2000.0 * log10(setting.coerceAtMost(MAX_SETTING).toDouble())).roundToInt()
    }
}
