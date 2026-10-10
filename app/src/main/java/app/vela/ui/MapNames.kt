package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Which name a road or place label on the map shows (issue #738). The basemap's `name:latin` is
 * OpenMapTiles' own romanization where OpenStreetMap has no English name: readable for Cyrillic
 * or Greek, a vowel-less skeleton for Hebrew that a reporter in Israel took for Turkish. A reader
 * of a non-Latin script keeps the local name whatever the choice. The map style reloads on a
 * change (the mode rides `styleKey`). The voice and the banner keep their own rule (SpokenScript).
 */
object MapNames {
    /** English where OpenStreetMap has it, else the basemap's romanized name, else the local one. */
    const val ENGLISH_LATIN = "english_latin"
    /** English where OpenStreetMap has it, else the local name in its own script: what Google shows. */
    const val ENGLISH_LOCAL = "english_local"
    /** The local name alone, English or not. */
    const val LOCAL = "local"
    /** The local name with the English one after it where OpenStreetMap has one: on a second line
     *  for a place, on the same line for a street (a label along a line cannot break). */
    const val LOCAL_ENGLISH = "local_english"
    const val DEFAULT = ENGLISH_LATIN
    val mode = mutableStateOf(DEFAULT)

    fun init(context: Context) {
        mode.value = prefs(context).getString(KEY, DEFAULT) ?: DEFAULT
    }

    fun set(context: Context, value: String) {
        mode.value = value
        prefs(context).edit().putString(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "map_names"
}
