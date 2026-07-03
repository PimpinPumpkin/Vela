package app.vela.core.feedback

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.getSystemService
import app.vela.core.model.ManeuverType
import app.vela.core.model.TravelMode
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

    /** Per-travel-mode toggle. Default = [defaultFor] (on everywhere except driving), unless the user
     *  had explicitly turned the legacy global switch OFF — then it stays off until they set per-mode. */
    private fun enabled(mode: TravelMode): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val default = if (!p.getBoolean(KEY, true)) false else defaultFor(mode)
        return p.getBoolean(keyFor(mode), default)
    }

    fun cue(type: ManeuverType, approaching: Boolean, mode: TravelMode) {
        if (!enabled(mode)) return
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

    companion object {
        const val PREFS = "vela_settings"
        const val KEY = "haptics_on"
        /** Per-mode SharedPreferences key, e.g. "haptics_drive" / "haptics_bicycle". */
        fun keyFor(mode: TravelMode) = "haptics_${mode.name.lowercase()}"

        /** Default per mode: on for walk/bike/transit (helpful hands-free by feel), OFF for driving —
         *  in a car you already have the screen + spoken directions, so a buzz on every turn is noise. */
        fun defaultFor(mode: TravelMode) = mode != TravelMode.DRIVE
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
