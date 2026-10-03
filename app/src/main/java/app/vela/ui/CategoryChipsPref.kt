package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** How the quick-category shortcuts show on the map (issue #654): the full chip row, ONE chip that
 *  opens them as a menu, or none. The chips are search shortcuts, not map places, so "show places"
 *  never hid them. */
object CategoryChipsPref {
    enum class Mode { ROW, BUTTON, HIDDEN }

    val mode = mutableStateOf(Mode.ROW)

    fun init(context: Context) {
        mode.value = runCatching { Mode.valueOf(prefs(context).getString(KEY, null) ?: Mode.ROW.name) }.getOrDefault(Mode.ROW)
    }

    fun set(context: Context, value: Mode) {
        mode.value = value
        prefs(context).edit().putString(KEY, value.name).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "category_chips"
}
