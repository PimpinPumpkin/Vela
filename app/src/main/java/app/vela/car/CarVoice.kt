package app.vela.car

import android.content.Context
import app.vela.core.voice.VoiceGuide

/**
 * The car's voice controls. Both write the same two prefs the phone's setting does, so the
 * phone's state follows ([app.vela.ui.map.MapViewModel] listens to them) and the next launch
 * agrees. The car settings switch is on or off ([set]); the drive's speaker button steps through
 * the phone's three modes ([step], issue #842).
 */
object CarVoice {
    /** The drive's speaker button: On, then Alerts only, then Off, then On again, the order of
     *  the phone's button (`MapViewModel.toggleVoice`). */
    fun step(context: Context, voiceGuide: VoiceGuide) = when {
        !voiceGuide.muted -> write(context, voiceGuide, muted = true, alertsOnly = true)
        voiceGuide.alertsOnly -> write(context, voiceGuide, muted = true, alertsOnly = false)
        else -> write(context, voiceGuide, muted = false, alertsOnly = false)
    }

    private fun write(context: Context, voiceGuide: VoiceGuide, muted: Boolean, alertsOnly: Boolean) {
        voiceGuide.alertsOnly = alertsOnly
        voiceGuide.muted = muted
        app.vela.ui.VoiceAlertsOnly.on.value = alertsOnly
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("spoken_directions", !muted)
            .putBoolean("spoken_alerts_only", alertsOnly)
            .apply()
    }

    /** On or off, leaving Alerts only: the car settings switch. */
    fun set(context: Context, voiceGuide: VoiceGuide, on: Boolean) {
        voiceGuide.alertsOnly = false
        voiceGuide.muted = !on
        app.vela.ui.VoiceAlertsOnly.on.value = false
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("spoken_directions", on)
            .putBoolean("spoken_alerts_only", false)
            .apply()
    }
}
