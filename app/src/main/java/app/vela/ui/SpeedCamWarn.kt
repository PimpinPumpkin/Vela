package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Radar / speed camera alerts during navigation (issue #229).
 *
 * Two independent opt-ins matching [FlockNavAlert]: [card] shows the heads-up banner,
 * [voice] says "Speed camera ahead" through the nav voice, plus customizable lead distance
 * ([distanceMeters] = 0 for auto / 12 seconds, or specific meters e.g. 200, 400, 600, 800, 1000).
 */
object SpeedCamWarn {
    val card = mutableStateOf(false)
    val voice = mutableStateOf(false)
    val distanceMeters = mutableIntStateOf(0) // 0 = Auto (12s depending on speed)

    val any: Boolean get() = card.value || voice.value

    fun init(context: Context) {
        val p = prefs(context)
        val legacyVoice = p.getBoolean(KEY_LEGACY, false)
        card.value = p.getBoolean(KEY_CARD, false)
        voice.value = p.getBoolean(KEY_VOICE, legacyVoice)
        distanceMeters.intValue = p.getInt(KEY_DISTANCE, 0)
    }

    fun setCard(context: Context, value: Boolean) {
        card.value = value
        prefs(context).edit().putBoolean(KEY_CARD, value).apply()
    }

    fun setVoice(context: Context, value: Boolean) {
        voice.value = value
        prefs(context).edit().putBoolean(KEY_VOICE, value).putBoolean(KEY_LEGACY, value).apply()
    }

    fun setDistanceMeters(context: Context, meters: Int) {
        distanceMeters.intValue = meters
        prefs(context).edit().putInt(KEY_DISTANCE, meters).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_LEGACY = "speed_cam_warn"
    private const val KEY_CARD = "speed_cam_warn_card"
    private const val KEY_VOICE = "speed_cam_warn_voice"
    private const val KEY_DISTANCE = "speed_cam_warn_distance"
}
