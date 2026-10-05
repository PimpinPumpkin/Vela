package app.vela.diag

import android.content.Context

/**
 * The user's settings as one line, for a crash report and a recorded drive: a bug that only
 * happens with a certain mix of switches is otherwise guesswork. Switches, numbers and short
 * option names only. Anything that could be a place, a name or a file is left out: free text, and
 * every key that holds a position, an address, a contact, a provider or a font.
 */
object SettingsDump {
    private val SKIP = Regex("lat|lng|lon|loc|home|work|addr|font|provider|contact|name|query|recent|dismiss|seen|stamp|_ms$|_at$|token|key|session", RegexOption.IGNORE_CASE)
    private val OPTION = Regex("^[A-Za-z0-9_.:-]{1,24}$")

    fun line(context: Context): String = runCatching {
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).all.entries
            .filter { (k, v) -> !SKIP.containsMatchIn(k) && (v is Boolean || v is Int || v is Long || v is Float || (v is String && OPTION.matches(v))) }
            .sortedBy { it.key }
            .joinToString(" ") { (k, v) -> "$k=" + if (v is Boolean) (if (v) "1" else "0") else v }
    }.getOrDefault("")
}
