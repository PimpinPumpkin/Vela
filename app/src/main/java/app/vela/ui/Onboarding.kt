package app.vela.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import app.vela.core.voice.VelaPiper

/**
 * First-run welcome + a *tasteful* one-time donation prompt.
 *
 * Donation-prompt etiquette (so it never reads as nagware): it appears **once**,
 * only **after the app has earned it** (a week since first launch), is trivially
 * dismissed with no guilt, and never blocks anything. A permanent "Support Vela"
 * entry in Settings is the path for anyone who wants to give on their own.
 *
 * Process-wide reactive holder, same shape as [Units]/[Traffic]; `init()`-ed in
 * `VelaApp`, persisted to `vela_onboarding`.
 */
object Onboarding {
    /** False until the user has seen the welcome screen once. */
    val welcomeDone = mutableStateOf(true)

    /** True for the single session where the one-time donate prompt should show. */
    val showDonatePrompt = mutableStateOf(false)

    /** True for the single session where the one-time location RATIONALE should show — a plain-words
     *  "here's why, you can say no" screen BEFORE Android's raw system dialog, offered first thing
     *  after the welcome. Suppressed once location is granted or the user has answered. The map no
     *  longer fires the system dialog on its own; this owns the first ask (a denial still leaves
     *  search/browse working, and the locate button re-asks later). */
    val showLocationPrompt = mutableStateOf(false)

    /** True for the single session where the one-time notification ask should fire — right after the
     *  location step, because turn-by-turn keeps your next turn in a notification and asking during
     *  setup means the first navigation just works. Android 13+ only; suppressed once granted or
     *  answered. A denial gets one plain-words are-you-sure in VelaRoot, then the app moves on. */
    val showNotifPrompt = mutableStateOf(false)

    /** True for the single session where the one-time "download the Vela neural voice?" prompt should
     *  show — offered right after the notification step so the best voice is one tap away. Suppressed
     *  once the model is present or the user has answered. */
    val showVoicePrompt = mutableStateOf(false)

    // Forks: point this at your own funding page.
    const val DONATE_URL = "https://buymeacoffee.com/PimpinPumpkin"

    private const val PREFS = "vela_onboarding"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        welcomeDone.value = p.getBoolean("welcome_done", false)
        var firstMs = p.getLong("first_ms", 0L)
        if (firstMs == 0L) {
            firstMs = System.currentTimeMillis()
            p.edit().putLong("first_ms", firstMs).apply()
        }
        val donatePromptDone = p.getBoolean("donate_prompt_done", false)
        showDonatePrompt.value = welcomeDone.value && !donatePromptDone &&
            (System.currentTimeMillis() - firstMs) >= WEEK_MS

        // Location rationale: show once, unless already granted or answered. Granted counts as done
        // (never nag someone who allowed it). On a brand-new install welcomeDone is still false here →
        // completeWelcome arms it right after the welcome screen instead.
        val locationPromptDone = p.getBoolean("location_prompt_done", false)
        showLocationPrompt.value = welcomeDone.value && !locationPromptDone && !hasLocation(context)

        // Voice prompt: offer the neural voice once, unless it's already downloaded or answered. On a
        // brand-new install welcomeDone is still false here (welcome not seen yet) → completeWelcome
        // arms the location step, whose dismissal arms this one.
        val voicePromptDone = p.getBoolean("voice_prompt_done", false)
        showVoicePrompt.value = welcomeDone.value && !voicePromptDone && !VelaPiper.isReady(context)
    }

    private fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Notifications need no runtime grant before Android 13. */
    private fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Mark the location step handled (whatever the user chose) so it never shows again, and arm the
     *  next step: notifications where the OS needs an ask, else straight to the voice offer. */
    fun dismissLocationPrompt(context: Context) {
        showLocationPrompt.value = false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean("location_prompt_done", true).apply()
        if (!p.getBoolean("notif_prompt_done", false) && !hasNotifications(context)) {
            showNotifPrompt.value = true
        } else {
            showVoicePrompt.value = !p.getBoolean("voice_prompt_done", false) && !VelaPiper.isReady(context)
        }
    }

    /** Mark the notification step handled so it never shows again, and arm the voice offer next. */
    fun dismissNotifPrompt(context: Context) {
        showNotifPrompt.value = false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean("notif_prompt_done", true).apply()
        showVoicePrompt.value = !p.getBoolean("voice_prompt_done", false) && !VelaPiper.isReady(context)
    }

    /** Mark the voice prompt handled so it never shows again. It's the last onboarding step. */
    fun dismissVoicePrompt(context: Context) {
        showVoicePrompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("voice_prompt_done", true).apply()
    }

    fun completeWelcome(context: Context) {
        welcomeDone.value = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean("welcome_done", true).apply()
        // First-run order: location → notifications → voice. Arm location here; each step's
        // dismissal arms the next. If a step is somehow already granted/answered, skip past it.
        if (!p.getBoolean("location_prompt_done", false) && !hasLocation(context)) {
            showLocationPrompt.value = true
        } else if (!p.getBoolean("notif_prompt_done", false) && !hasNotifications(context)) {
            showNotifPrompt.value = true
        } else {
            showVoicePrompt.value = !p.getBoolean("voice_prompt_done", false) && !VelaPiper.isReady(context)
        }
    }

    /** Mark the one-time prompt as handled so it never shows again. */
    fun dismissDonatePrompt(context: Context) {
        showDonatePrompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("donate_prompt_done", true).apply()
    }
}
