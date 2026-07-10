package app.vela.ui

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.runtime.mutableStateOf
import app.vela.voice.AsrModel

/**
 * Voice search for the search bar. TWO ways to turn speech into a query:
 *  - **tier-1 (on-device):** Vela's own Whisper model records + transcribes on the phone
 *    ([app.vela.voice.WhisperRecognizer]); works with no third-party app and no account.
 *  - **tier-2 (provider):** an installed voice-input app (FUTO Voice Input, Google's recognizer on
 *    GMS phones, …) captures speech via the RECOGNIZE_SPEECH intent and hands back text. Vela records
 *    nothing itself for this - the provider does.
 *
 * The mic only appears when SOMETHING can service it (the resolved [Mode] is not NONE), so it's never
 * a dead button. Which one runs is the [engine] preference: **Auto** prefers on-device when the model
 * is installed, else the provider; or the user can pin On-device / System provider in Settings.
 *
 * Process-wide reactive holder, same shape as [Units]/[DynamicColor]; `init()`-ed in [VelaApp].
 */
object VoiceSearch {
    /** User toggle (Settings → Search). On by default - it only renders when a path exists anyway. */
    val enabled = mutableStateOf(true)

    /** Which speech path to use. Reactive so the Settings picker updates the mic live. */
    val engine = mutableStateOf(Engine.AUTO)

    enum class Engine { AUTO, LOCAL, SYSTEM }

    /** What the mic will actually do when tapped, given the toggle + engine pref + what's available. */
    enum class Mode { LOCAL, SYSTEM, NONE }

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY, true)
        engine.value = readEngine(prefs(context).getString(ENGINE_KEY, null))
    }

    fun set(context: Context, value: Boolean) {
        enabled.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun setEngine(context: Context, value: Engine) {
        engine.value = value
        prefs(context).edit().putString(ENGINE_KEY, value.name).apply()
    }

    /** Is a third-party voice-input APP installed (tier-2)? Cheap PackageManager query; only apps that
     *  register the RECOGNIZE_SPEECH ACTIVITY count (an IME/keyboard mic is a RecognitionService, which
     *  we can't `startActivityForResult` to). Stable per launch (providers don't install mid-session). */
    fun hasProvider(context: Context): Boolean = runCatching {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    /** Is Vela's own on-device model downloaded (tier-1)? File-existence check, no model load. */
    fun localReady(context: Context): Boolean = AsrModel.isInstalled(context)

    /** Resolve what the mic should do right now. NONE → hide the mic. */
    fun resolvedMode(context: Context): Mode {
        if (!enabled.value) return Mode.NONE
        val local = localReady(context)
        val provider = hasProvider(context)
        return when (engine.value) {
            Engine.LOCAL -> if (local) Mode.LOCAL else Mode.NONE
            Engine.SYSTEM -> if (provider) Mode.SYSTEM else Mode.NONE
            Engine.AUTO -> when {
                local -> Mode.LOCAL       // on-device wins when it's there
                provider -> Mode.SYSTEM
                else -> Mode.NONE
            }
        }
    }

    private fun readEngine(s: String?): Engine =
        runCatching { if (s == null) Engine.AUTO else Engine.valueOf(s) }.getOrDefault(Engine.AUTO)

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "voice_search_button"
    private const val ENGINE_KEY = "voice_search_engine"
}
