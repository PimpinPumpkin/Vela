package app.vela.ui

import android.content.Context
import android.content.res.Configuration
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

    /** Set by the hosting Activity so a language change can re-create it (to re-read localized
     *  resources). Null until an Activity registers it. */
    var onLocaleChanged: (() -> Unit)? = null

    fun set(context: Context, langCode: String) {
        val changed = langCode != language.value
        language.value = langCode
        prefs(context).edit().putString(KEY, langCode).apply()
        apply()
        // Re-create the Activity so `stringResource`/`getString` re-resolve in the new language
        // (the nav voice already switched via apply()); no-op when nothing actually changed.
        if (changed) onLocaleChanged?.invoke()
    }

    /** The resolved locale — the system default when following the system, else the override. */
    fun effective(): Locale = language.value.takeIf { it.isNotBlank() }?.let { Locale(it) } ?: Locale.getDefault()

    /** Wrap a base [Context] with the chosen language so `stringResource`/`getString` resolve to it.
     *  Reads the pref directly (it runs from `attachBaseContext`, before [init]) and is a **no-op when
     *  following the system locale** — so the default path is byte-for-byte untouched. */
    fun wrap(base: Context): Context {
        val lang = prefs(base).getString(KEY, "").orEmpty()
        if (lang.isBlank()) return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Push the resolved locale into the app's locale-aware subsystems. */
    private fun apply() {
        NavStringsRegistry.setLocale(effective())
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "app_language"
}
