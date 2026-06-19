package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

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
    }

    /** Mark the diagnostics prompt as handled so it never shows again. */
    fun dismissDiagPrompt(context: Context) {
        showDiagPrompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("diag_prompt_done", true).apply()
    }

    fun completeWelcome(context: Context) {
        welcomeDone.value = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("welcome_done", true).apply()
    }

    /** Mark the one-time prompt as handled so it never shows again. */
    fun dismissDonatePrompt(context: Context) {
        showDonatePrompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("donate_prompt_done", true).apply()
    }
}
