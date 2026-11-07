package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Warning before the posted limit on the route drops sharply (issue #857). Off by default. [on]
 * shows the card and the road-ahead bar's sign; [voice] also says it out loud, through the nav
 * voice and its mute. [level] picks how large a drop counts ([KMH] or [MPH], by the unit setting).
 * Where the drops are and when one is due lives in :core [app.vela.core.nav.LimitDrops].
 */
object LimitDropAlert {
    val on = mutableStateOf(false)
    val voice = mutableStateOf(true)
    val level = mutableIntStateOf(DEFAULT_LEVEL)

    /** The drops offered, in each unit. The same index in both, so a unit switch keeps the pick. */
    val KMH = listOf(20, 30, 40)
    val MPH = listOf(10, 20, 25)

    /** The smallest drop that counts, in km/h. An mph road is stored in km/h rounded (35 mph is
     *  56 km/h), so the mph thresholds give a kilometer an hour of slack. */
    fun thresholdKmh(): Double {
        val i = level.intValue.coerceIn(0, KMH.lastIndex)
        return if (Units.imperial.value) MPH[i] * 1.609344 - 1.0 else KMH[i].toDouble()
    }

    fun init(context: Context) {
        val p = prefs(context)
        on.value = p.getBoolean(KEY_ON, false)
        voice.value = p.getBoolean(KEY_VOICE, true)
        level.intValue = p.getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(0, KMH.lastIndex)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY_ON, value).apply()
    }

    fun setVoice(context: Context, value: Boolean) {
        voice.value = value
        prefs(context).edit().putBoolean(KEY_VOICE, value).apply()
    }

    fun setLevel(context: Context, value: Int) {
        level.intValue = value.coerceIn(0, KMH.lastIndex)
        prefs(context).edit().putInt(KEY_LEVEL, level.intValue).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_ON = "limit_drop_alert"
    private const val KEY_VOICE = "limit_drop_voice"
    private const val KEY_LEVEL = "limit_drop_level"
    private const val DEFAULT_LEVEL = 1
}
