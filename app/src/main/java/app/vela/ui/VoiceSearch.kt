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
 *    GMS phones, ...) captures speech via the RECOGNIZE_SPEECH intent and hands back text. Vela records
 *    nothing itself for this - the provider does.
 *
 * The mic only appears when SOMETHING can service it (the resolved [Mode] is not NONE), so it's never
 * a dead button. Which one runs is the [engine] preference: **Auto** prefers on-device when the model
 * is installed, else the provider; or the user can pin On-device / System provider in Settings.
 *
 * Process-wide reactive holder, same shape as [Units]/[AppTheme]; `init()`-ed in [app.vela.VelaApp].
 */
object VoiceSearch {
    /** User toggle (Settings -> Search). On by default - it only renders when a path exists anyway. */
    val enabled = mutableStateOf(true)

    /** Which speech path to use. Reactive so the Settings picker updates the mic live. */
    val engine = mutableStateOf(Engine.AUTO)

    enum class Engine { AUTO, LOCAL, SYSTEM }

    /** What the mic will actually do when tapped, given the toggle + engine pref + what's available. */
    enum class Mode { LOCAL, SYSTEM, NONE }

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY, true)
        engine.value = readEngine(prefs(context).getString(ENGINE_KEY, null))
        // Legacy migration: an old explicit LOCAL pin (pre-picker) hid the mic entirely when the
        // model was later deleted, even with voice apps installed. AUTO behaves identically while
        // the model exists and degrades gracefully without it.
        if (engine.value == Engine.LOCAL) setEngine(context, Engine.AUTO)
        provider.value = prefs(context).getString(PROVIDER_KEY, null)
    }

    fun set(context: Context, value: Boolean) {
        enabled.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun setEngine(context: Context, value: Engine) {
        engine.value = value
        prefs(context).edit().putString(ENGINE_KEY, value.name).apply()
    }

    /** An installed tier-2 voice-input app: display label + the exact activity Vela launches. */
    data class Provider(val label: String, val component: android.content.ComponentName)

    /** The chosen provider's flattened ComponentName - reactive so the Settings picker updates live.
     *  Null = no explicit pick yet (the first installed provider is used). */
    val provider = mutableStateOf<String?>(null)

    /** Every installed voice-input app that can service the RECOGNIZE_SPEECH activity intent, in
     *  resolution order. With more than one installed, the old implicit intent left the pick to
     *  ANDROID (its default-app choice or a system disambiguation dialog) - the Settings picker +
     *  [chosenProvider] give the choice to the user instead. */
    fun providers(context: Context): List<Provider> = runCatching {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).map {
            Provider(
                it.loadLabel(pm).toString(),
                android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name),
            )
        }
    }.getOrDefault(emptyList())

    /** The component to PIN on the launch intent, or null to leave the intent implicit. Pinning
     *  order: the user's Settings pick wins; otherwise DEFER to Android's own default app for
     *  speech (null - the system routes it, respecting a choice made outside Vela); only when
     *  Android has no default either (its chooser would interject mid-dictation) pin the first
     *  installed app. An uninstalled Settings pick degrades down this same ladder. */
    fun launchComponent(context: Context): android.content.ComponentName? {
        val all = providers(context)
        if (all.isEmpty()) return null
        val saved = provider.value?.let(android.content.ComponentName::unflattenFromString)
        all.firstOrNull { it.component == saved }?.let { return it.component }
        if (androidDefault(context) != null) return null
        return all.first().component
    }

    /** Android's own default RECOGNIZE_SPEECH handler, or null when none is set (the resolver
     *  activity, package "android", answers when the choice is ambiguous). */
    private fun androidDefault(context: Context): android.content.ComponentName? = runCatching {
        val ri = context.packageManager.resolveActivity(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return null
        val ai = ri.activityInfo ?: return null
        if (ai.packageName == "android") null
        else android.content.ComponentName(ai.packageName, ai.name)
    }.getOrNull()

    /** What the Settings picker should show as selected: the saved pick, else Android's default,
     *  else the first installed - the same app [launchComponent] would end up launching. */
    fun chosenProvider(context: Context): Provider? {
        val all = providers(context)
        if (all.isEmpty()) return null
        val saved = provider.value?.let(android.content.ComponentName::unflattenFromString)
        all.firstOrNull { it.component == saved }?.let { return it }
        androidDefault(context)?.let { d -> all.firstOrNull { it.component == d }?.let { return it } }
        return all.first()
    }

    fun setProvider(context: Context, component: android.content.ComponentName) {
        val flat = component.flattenToString()
        provider.value = flat
        prefs(context).edit().putString(PROVIDER_KEY, flat).apply()
    }

    /** Back to "Android default": drop the explicit pick so the launch intent stays implicit
     *  (Android's own default app routes it; first-installed only when there is no default). */
    fun clearProvider(context: Context) {
        provider.value = null
        prefs(context).edit().remove(PROVIDER_KEY).apply()
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

    /** Resolve what the mic should do right now. NONE -> hide the mic. */
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
    private const val PROVIDER_KEY = "voice_search_provider"
}
