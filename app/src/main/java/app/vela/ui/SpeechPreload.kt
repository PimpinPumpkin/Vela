package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Performance > "Load voice search at startup" (pref `speech_preload`, 2026-09-22).
 * On: the on-device speech model loads at launch (background priority, released after two idle
 * minutes), so a mic tapped straight from the map listens at once. Off: it loads when the search
 * box gains focus. The default follows the phone: on with ~8 GB of RAM or more
 * ([MemoryPressure.strong]), off otherwise, because the model is ~290 MB of native memory and
 * together with the rest of a cold launch it filled a 6 GB phone's swap.
 */
object SpeechPreload {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, MemoryPressure.strong)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "speech_preload"
}
