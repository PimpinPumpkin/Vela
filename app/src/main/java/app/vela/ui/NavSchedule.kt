package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Calendar

object NavSchedule {
    val enabled = mutableStateOf(false)
    val useSun = mutableStateOf(true)
    val startHour = mutableStateOf(18)
    val startMinute = mutableStateOf(0)
    val endHour = mutableStateOf(6)
    val endMinute = mutableStateOf(0)

    fun init(context: Context) {
        val p = prefs(context)
        enabled.value = p.getBoolean(KEY_ENABLED, false)
        useSun.value = p.getBoolean(KEY_USE_SUN, true)
        startHour.value = p.getInt(KEY_START_H, 18).coerceIn(0, 23)
        startMinute.value = p.getInt(KEY_START_M, 0).coerceIn(0, 59)
        endHour.value = p.getInt(KEY_END_H, 6).coerceIn(0, 23)
        endMinute.value = p.getInt(KEY_END_M, 0).coerceIn(0, 59)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled.value = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
    fun setUseSun(context: Context, value: Boolean) {
        useSun.value = value
        prefs(context).edit().putBoolean(KEY_USE_SUN, value).apply()
    }
    fun setStart(context: Context, h: Int, m: Int) {
        val hc = h.coerceIn(0, 23)
        val mc = m.coerceIn(0, 59)
        startHour.value = hc; startMinute.value = mc
        prefs(context).edit().putInt(KEY_START_H, hc).putInt(KEY_START_M, mc).apply()
    }
    fun setEnd(context: Context, h: Int, m: Int) {
        val hc = h.coerceIn(0, 23)
        val mc = m.coerceIn(0, 59)
        endHour.value = hc; endMinute.value = mc
        prefs(context).edit().putInt(KEY_END_H, hc).putInt(KEY_END_M, mc).apply()
    }

    private var initialSysDarkSeen: Boolean? = null
    private val sysDarkToggled = mutableStateOf(false)

    private var initialPhoneSysDarkSeen: Boolean? = null
    private val phoneSysDarkToggled = mutableStateOf(false)

    fun resetToggle() {
        initialSysDarkSeen = null
        sysDarkToggled.value = false
    }

    fun resetPhoneToggle() {
        initialPhoneSysDarkSeen = null
        phoneSysDarkToggled.value = false
    }

    fun onCarDarkSignal(sysDark: Boolean): Boolean {
        val first = initialSysDarkSeen
        if (first == null) {
            initialSysDarkSeen = sysDark
            return false
        }
        if (first != sysDark && !sysDarkToggled.value) {
            sysDarkToggled.value = true
            return true
        }
        return false
    }

    fun onPhoneDarkSignal(sysDark: Boolean) {
        val first = initialPhoneSysDarkSeen
        if (first == null) {
            initialPhoneSysDarkSeen = sysDark
        } else if (first != sysDark && !phoneSysDarkToggled.value) {
            phoneSysDarkToggled.value = true
        }
    }

    internal fun isNightAtMinutes(nowTime: Int, startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val startTime = startH.coerceIn(0, 23) * 60 + startM.coerceIn(0, 59)
        val endTime = endH.coerceIn(0, 23) * 60 + endM.coerceIn(0, 59)
        return if (startTime <= endTime) {
            nowTime in startTime..endTime
        } else {
            nowTime >= startTime || nowTime <= endTime
        }
    }

    fun isScheduledNight(): Boolean {
        if (useSun.value) return app.vela.ui.theme.AppTheme.night.value
        val nowTime = DemoClock.minutes() ?: Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        return isNightAtMinutes(nowTime, startHour.value, startMinute.value, endHour.value, endMinute.value)
    }

    fun carDarkOverride(sysDark: Boolean): Boolean {
        onCarDarkSignal(sysDark)
        if (enabled.value && !sysDarkToggled.value) {
            return isScheduledNight()
        }
        return sysDark
    }

    fun phoneDarkOverride(sysDark: Boolean): Boolean {
        if (enabled.value && !phoneSysDarkToggled.value) {
            return isScheduledNight()
        }
        return sysDark
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_ENABLED = "nav_schedule_enabled"
    private const val KEY_USE_SUN = "nav_schedule_sun"
    private const val KEY_START_H = "nav_schedule_sh"
    private const val KEY_START_M = "nav_schedule_sm"
    private const val KEY_END_H = "nav_schedule_eh"
    private const val KEY_END_M = "nav_schedule_em"
}
