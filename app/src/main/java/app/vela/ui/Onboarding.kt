package app.vela.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

    /** True for the single session where the one-time "turn on diagnostics?" prompt
     *  should show — Vela wants opted-in diagnostics, so it asks once (after the user
     *  has settled in), clearly, off otherwise. Never shown if it's already on. */
    val showDiagPrompt = mutableStateOf(false)

    /** True for the single session where the one-time location RATIONALE should show — a plain-words
     *  "here's why, you can say no" screen BEFORE Android's raw system dialog, offered first thing
     *  after the welcome. Suppressed once location is granted or the user has answered. The map no
     *  longer fires the system dialog on its own; this owns the first ask (a denial still leaves
     *  search/browse working, and the locate button re-asks later). */
    val showLocationPrompt = mutableStateOf(false)

    /** True for the single session where the one-time "download the Vela neural voice?" prompt should
     *  show — offered right after the location step so the best voice is one tap away. Suppressed once
     *  the model is present or the user has answered. */
    val showVoicePrompt = mutableStateOf(false)

    /** True for the single session where the one-time "set up offline maps?" prompt should show — offered
     *  right after the voice prompt so people discover offline maps during onboarding instead of hitting a
     *  dead search on the road. Shown once, then never again. */
    val showOfflinePrompt = mutableStateOf(false)

    // Replace with your own funding page (Liberapay / Ko-fi / GitHub Sponsors).
    const val DONATE_URL = "https://github.com/sponsors/PimpinPumpkin"

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

        // Diagnostics prompt: ask once, from the 2nd launch on (let the user settle in
        // first), unless they've already turned it on or been asked.
        val launches = p.getInt("launches", 0) + 1
        p.edit().putInt("launches", launches).apply()
        val diagPromptDone = p.getBoolean("diag_prompt_done", false)
        val diagAlreadyOn = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getBoolean("diag_enabled", false)
        showDiagPrompt.value = welcomeDone.value && !diagPromptDone && !diagAlreadyOn && launches >= 2

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

        // Offline prompt: once the voice question is behind them (new install) or already answered
        // (existing install), offer to set up offline maps. Shown a single time.
        val offlinePromptDone = p.getBoolean("offline_prompt_done", false)
        showOfflinePrompt.value = welcomeDone.value && voicePromptDone && !offlinePromptDone
    }

    private fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Mark the location rationale handled (whatever the user chose) so it never shows again, and arm
     *  the voice prompt next. Called from both "Allow" and "Not now". */
    fun dismissLocationPrompt(context: Context) {
        showLocationPrompt.value = false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean("location_prompt_done", true).apply()
        showVoicePrompt.value = !p.getBoolean("voice_prompt_done", false) && !VelaPiper.isReady(context)
    }

    /** Mark the voice prompt handled so it never shows again, and arm the offline prompt next. */
    fun dismissVoicePrompt(context: Context) {
        showVoicePrompt.value = false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean("voice_prompt_done", true).apply()
        showOfflinePrompt.value = !p.getBoolean("offline_prompt_done", false)
    }

    /** Mark the offline prompt handled so it never shows again. */
    fun dismissOfflinePrompt(context: Context) {
        showOfflinePrompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("offline_prompt_done", true).apply()
    }

    /** Mark the diagnostics prompt as handled so it never shows again. */
    fun dismissDiagPrompt(context: Context) {
        showDiagPrompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("diag_prompt_done", true).apply()
    }

    fun completeWelcome(context: Context) {
        welcomeDone.value = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putBoolean("welcome_done", true).apply()
        // First-run order: location rationale → voice → offline. Arm location here; its dismissal
        // arms voice. If location was somehow already granted, skip straight to the voice offer.
        if (!p.getBoolean("location_prompt_done", false) && !hasLocation(context)) {
            showLocationPrompt.value = true
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
