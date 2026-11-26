package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** Whether the turn card displays written instructions in addition to its glyphs and shields. */
object TurnInstructionText {
    val show = mutableStateOf(true)

    fun init(context: Context) {
        show.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        show.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY = "turn_instruction_text"
}
