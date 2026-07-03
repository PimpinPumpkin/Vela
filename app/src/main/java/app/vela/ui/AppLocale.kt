package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.i18n.NavStringsRegistry
import java.util.Locale

/**
 * App language preference — **follow the system locale by default, or override to a specific language**
 * (like Google Maps' in-app language setting). A process-wide reactive holder + persisted pref, mirroring
 * [app.vela.ui.theme.AppTheme] / [Units]. Setting it drives everything Vela renders in the user's
 * language: the GENERATED nav text (via [NavStringsRegistry]) today, and — as they're localized — the
 * `strings.xml` UI chrome and the scrape locale (hl/gl). Resolved to an actual [Locale] by [effective].
 *
 * Set EXPLICITLY here (main thread, startup + on change) and read from the registry at the leaf, rather
 * than calling `Locale.getDefault()` deep in the nav/TTS code (which runs off the main thread).
 */
object AppLocale {
    /** "" = follow the system; otherwise a language code ("en", "fr", "de", …). */
    val language = mutableStateOf("")

    /** The languages Vela's generated nav voice is translated into (and, rolling out, the UI chrome).
     *  This is the source of truth for the in-app language picker — keep it in sync with the NavStrings
     *  table in :core. */
    val SUPPORTED = listOf("en", "fr", "de", "es", "it", "pt", "nl", "ru", "pl", "sv", "uk")

    private val ENDONYMS = mapOf(
        "en" to "English", "fr" to "Français", "de" to "Deutsch", "es" to "Español",
        "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands", "ru" to "Русский",
        "pl" to "Polski", "sv" to "Svenska", "uk" to "Українська",
    )

    /** The language's own name (endonym) — what a speaker of it expects to see in a language list. */
    fun endonym(code: String): String = ENDONYMS[code] ?: code.replaceFirstChar { it.uppercase() }

    fun init(context: Context) {
        language.value = prefs(context).getString(KEY, "") ?: ""
        apply()
    }

    fun set(context: Context, langCode: String) {
        language.value = langCode
        prefs(context).edit().putString(KEY, langCode).apply()
        apply()
    }

    /** The resolved locale — the system default when following the system, else the override. */
    fun effective(): Locale = language.value.takeIf { it.isNotBlank() }?.let { Locale(it) } ?: Locale.getDefault()

    /** Push the resolved locale into the app's locale-aware subsystems. */
    private fun apply() {
        NavStringsRegistry.setLocale(effective())
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "app_language"
}
