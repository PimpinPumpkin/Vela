package app.vela.car

import android.content.Context
import app.vela.core.voice.VoiceGuide

/**
 * The car's voice control is on or off: it has no "Alerts only". Either choice from the car
 * leaves that mode, and writes the same two prefs the phone's setting does, so the phone's
 * state follows ([app.vela.ui.map.MapViewModel] listens to them) and the next launch agrees.
 */
object CarVoice {
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
