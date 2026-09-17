package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Heads-up for license-plate (Flock / ALPR) cameras coming up on the route while navigating.
 *
 * Two separate opt-ins, both OFF by default: [card] shows the heads-up card, [voice] says
 * "License plate camera ahead" through the normal nav voice (so the spoken-directions mute
 * applies). Neither depends on the map layer ([Flock]): the bundled camera dataset is in memory
 * either way. Only cameras that face along the route count ([app.vela.core.nav.CameraFacing]).
 * Timing is the speed-camera warning's, shared in :core [app.vela.core.nav.CameraAlerts].
 */
object FlockNavAlert {
    val card = mutableStateOf(false)
    val voice = mutableStateOf(false)

    val any: Boolean get() = card.value || voice.value

    fun init(context: Context) {
        val p = prefs(context)
        card.value = p.getBoolean(KEY_CARD, false)
        voice.value = p.getBoolean(KEY_VOICE, false)
    }

    fun setCard(context: Context, value: Boolean) {
        card.value = value
        prefs(context).edit().putBoolean(KEY_CARD, value).apply()
    }

    fun setVoice(context: Context, value: Boolean) {
        voice.value = value
        prefs(context).edit().putBoolean(KEY_VOICE, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_CARD = "flock_nav_alert_card"
    private const val KEY_VOICE = "flock_nav_alert_voice"
}
