package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.BuildConfig
import app.vela.ui.map.plainReleaseNotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The "What's new" prompt after an update (the NewPipe / PipePipe habit): the first launch on a
 * NEW version fetches that version's own release notes and shows them once, in the same dialog
 * shape as the donate prompt. The notes are the commit subjects CI writes into every release,
 * so nothing is bundled and a canary build reads the rolling canary release.
 *
 * Rules: never on a fresh install (the welcome screen is enough for one session, and there is
 * no "before" to compare with), never while another one-time prompt is up (VelaRoot orders
 * them), and never without the notes in hand: a fetch that fails leaves the version unseen so
 * the next launch tries again. Settings > About has a row to reopen it on demand.
 */
object WhatsNew {
    /** The notes to show right now, or null. [version] is the build they belong to. */
    val notes = mutableStateOf<String?>(null)
    val version = mutableStateOf(BuildConfig.VERSION_NAME)

    private const val PREFS = "vela_onboarding"
    private const val KEY = "last_seen_version"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getString(KEY, null)
        val current = BuildConfig.VERSION_NAME
        if (seen == null) {
            // First run of a build that knows about this prompt (fresh install, or an update from
            // before it existed): nothing to compare with, start counting from here.
            p.edit().putString(KEY, current).apply()
            return
        }
        if (seen == current) return
        if (!Onboarding.welcomeDone.value) { p.edit().putString(KEY, current).apply(); return }
        show(context, force = false)
    }

    /** Fetch this build's notes and arm the prompt. [force] (the About row) shows even when
     *  already seen. */
    fun show(context: Context, force: Boolean) {
        scope.launch {
            val body = withContext(Dispatchers.IO) { fetchNotes() } ?: return@launch
            val plain = plainReleaseNotes(body)
            if (plain.isBlank()) { if (!force) markSeen(context); return@launch }
            notes.value = plain
        }
    }

    fun dismiss(context: Context) {
        notes.value = null
        markSeen(context)
    }

    private fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, BuildConfig.VERSION_NAME).apply()
    }

    /** The release page for this build: the version tag, or the rolling canary release. */
    fun releaseUrl(): String =
        if (BuildConfig.VERSION_NAME.endsWith("-canary")) "https://github.com/PimpinPumpkin/Vela/releases/tag/canary"
        else "https://github.com/PimpinPumpkin/Vela/releases/tag/v${BuildConfig.VERSION_NAME}"

    fun openRelease(context: Context) {
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(releaseUrl())))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, context.getString(app.vela.R.string.donate_no_browser), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchNotes(): String? = runCatching {
        val tag = if (BuildConfig.VERSION_NAME.endsWith("-canary")) "canary" else "v${BuildConfig.VERSION_NAME}"
        val req = Request.Builder()
            .url("https://api.github.com/repos/PimpinPumpkin/Vela/releases/tags/$tag")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            org.json.JSONObject(resp.body?.string().orEmpty()).optString("body", "").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}
