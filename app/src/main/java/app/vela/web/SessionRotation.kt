package app.vela.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * How long one Google session lives (2026-09-23, Settings > Privacy, pref `google_session_rotate`).
 *
 * A saved Google cookie is a pseudonymous ID: everything an install asks for while it lasts can be
 * linked into one history. Google also gives a NEW session a limited view (five reviews, popular
 * times missing on busy places), which is why the per-place requests ride the WebView's saved
 * session (core AgedSession). This caps the history instead of choosing one side: the session is
 * thrown away on a schedule and a new one starts.
 *
 * - WEEK (default): a week of history at most; a day or so of the limited view after each reset.
 * - DAY: at most a day.
 * - LAUNCH: a new session every time the process starts. The most private, and every session is
 *   a new one, so the limited view is the normal state.
 *
 * A rotation clears the WebView's cookies and site storage (Google keeps identifiers in both),
 * Cronet's disk cache (only when the engine has not started yet, at process start), and, the next
 * time a Google WebView is built, the WebView HTTP cache ([consumeCacheClear]); "Start a new session
 * now" also empties the app's in-memory jar. The WebView store holds only Google: no other site is
 * loaded in a WebView.
 */
object SessionRotation {
    const val WEEK = "week"
    const val DAY = "day"
    const val LAUNCH = "launch"

    val mode = mutableStateOf(WEEK)

    @Volatile private var cacheClearPending = false
    /** The app's in-memory jar, set by VelaApp, emptied by [startNewNow]. */
    @Volatile var appJar: app.vela.core.di.ResettableCookieJar? = null

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private fun periodMs(m: String): Long = when (m) {
        LAUNCH -> 0L
        DAY -> 24L * 3600_000
        else -> 7L * 24 * 3600_000
    }

    /** At process start, before anything talks to Google: read the setting and rotate when due. */
    fun init(context: Context) {
        val p = prefs(context)
        mode.value = p.getString(KEY_MODE, WEEK) ?: WEEK
        val started = p.getLong(KEY_STARTED, 0L)
        val now = System.currentTimeMillis()
        if (started == 0L) { p.edit().putLong(KEY_STARTED, now).apply(); return } // first run: the session starts now
        if (mode.value == LAUNCH || now - started >= periodMs(mode.value)) {
            rotate(context, engineStarted = false)
            p.edit().putLong(KEY_STARTED, now).apply()
            android.util.Log.i("VelaSession", "new Google session (${mode.value}, the last one began ${(now - started) / 3600_000} h ago)")
        }
    }

    /** When the current session began (0 before the first run stamps it). */
    fun sessionStarted(context: Context): Long = prefs(context).getLong(KEY_STARTED, 0L)

    fun setMode(context: Context, value: String) {
        mode.value = value
        prefs(context).edit().putString(KEY_MODE, value).apply()
    }

    /** "Start a new Google session now". */
    fun startNewNow(context: Context) {
        rotate(context, engineStarted = true)
        appJar?.reset()
        prefs(context).edit().putLong(KEY_STARTED, System.currentTimeMillis()).apply()
        android.util.Log.i("VelaSession", "new Google session (by hand)")
    }

    /** True once after a rotation: the first Google WebView built afterwards clears its HTTP cache. */
    fun consumeCacheClear(wv: WebView) {
        if (!cacheClearPending) return
        cacheClearPending = false
        runCatching { wv.clearCache(true) }
    }

    private fun rotate(context: Context, engineStarted: Boolean) {
        cacheClearPending = true
        GoogleStanding.reset(context)
        // Cronet's cache (cacheDir/cronet) is only safe to delete before the engine opens it.
        if (!engineStarted) runCatching { File(context.cacheDir, "cronet").deleteRecursively() }
        // CookieManager loads the WebView library the first time it is touched, which is not
        // something to do on the startup thread; it takes calls from any thread. WebStorage wants
        // the main thread, so it goes to the next idle moment there. Google traffic starts seconds
        // after launch, well after both.
        Thread {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }.start()
        android.os.Looper.getMainLooper().queue.addIdleHandler {
            runCatching { WebStorage.getInstance().deleteAllData() }
            false
        }
    }

    private const val KEY_MODE = "google_session_rotate"
    private const val KEY_STARTED = "google_session_started"
}
