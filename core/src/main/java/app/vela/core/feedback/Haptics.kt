package app.vela.core.feedback

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.getSystemService
import app.vela.core.model.ManeuverType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direction-coded haptic turn cues. A left turn buzzes differently from a right
 * one, so a biker or walker can navigate by feel — no need to look at the screen
 * or hear the TTS (handy with wind, traffic noise, or no earbuds). Fired by
 * [app.vela.core.nav.NavEngine] via `NavEvent.Haptic`. Honours the "Vibrate on
 * turns" setting (default on).
 */
@Singleton
class Haptics @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val vibrator: Vibrator? = context.getSystemService()

    private fun enabled(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun cue(type: ManeuverType, approaching: Boolean) {
        if (!enabled()) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val pattern = when {
            approaching -> longArrayOf(0, 90)                        // light "get ready" tick
            isLeft(type) -> longArrayOf(0, 240, 160, 240)            // two firm pulses = LEFT
            isRight(type) -> longArrayOf(0, 110, 90, 110, 90, 110)   // three short pulses = RIGHT
            else -> longArrayOf(0, 300)                              // one buzz = straight / other
        }
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun isLeft(t: ManeuverType) = t in LEFTS
    private fun isRight(t: ManeuverType) = t in RIGHTS

    private companion object {
        const val PREFS = "vela_settings"
        const val KEY = "haptics_on"
        val LEFTS = setOf(
            ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT,
            ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.UTURN,
        )
        val RIGHTS = setOf(
            ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT,
            ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.KEEP_RIGHT,
        )
    }
}
