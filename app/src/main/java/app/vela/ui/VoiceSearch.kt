package app.vela.ui

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.runtime.mutableStateOf

/**
 * Voice search for the search bar: tap a mic, an installed voice-input app (FUTO Voice Input,
 * Sayboard, Google's recognizer on GMS phones, …) captures speech and hands back text, which
 * fills the query and runs the search. Vela never records audio itself for this - the provider
 * does, so no microphone permission is needed here.
 *
 * The mic only appears when it can actually do something: the toggle is on AND some provider
 * resolves the recognize-speech intent. When nothing can service it the button is hidden, never
 * shown dead. (A later branch adds an on-device Whisper model as a second path; this same holder
 * will report that too, so the mic shows when EITHER is available.)
 *
 * Process-wide reactive holder, same shape as [Units]/[DynamicColor]; `init()`-ed in [VelaApp].
 */
object VoiceSearch {
    /** User toggle (Settings). On by default - it only renders when a provider exists anyway, so
     *  "on" costs nothing to someone with no voice app. */
    val enabled = mutableStateOf(true)

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        enabled.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** Is there an app that can handle voice input right now? Cheap PackageManager query;
     *  providers don't install mid-session, so callers can treat it as stable per launch. */
    fun hasProvider(context: Context): Boolean = runCatching {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "voice_search_button"
}
