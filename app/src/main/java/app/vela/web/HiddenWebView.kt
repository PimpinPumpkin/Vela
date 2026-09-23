package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One hidden Chromium page for scraping a Google Maps surface the keyless HTTP path cannot get
 * (bot-degraded on TLS fingerprint): the shared lifecycle every fetcher used to copy by hand.
 *
 * What lives here, once: the view itself (JavaScript, DOM storage, the desktop user agent, the
 * `VelaBridge` result channel), a request id per page load so a slow poller from a timed-out page
 * can never complete a newer request, the idle reap that frees the renderer after [reapIdleMs]
 * and the immediate reap under memory pressure (issue #182), the sleep between fetches
 * (`onPause` / `onResume`, a live page kept its compositor busy for the whole session), the
 * non-http scheme block, and console errors logged under one tag so a broken scrape says so in
 * logcat instead of dying silently (the reviews scrape was dead for a week because only it logged).
 *
 * A fetcher is its URL, its extractor script and its parser: wrap the work in [session], start a
 * page with [request] + [load], and answer [onPageFinished] by evaluating the script for that
 * request id. See #417, refactor 2.
 */
abstract class HiddenWebView(
    protected val context: Context,
    private val tag: String,
    private val reapIdleMs: Long = 120_000L,
) {
    protected val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val seq = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var currentId: String = ""
    @Volatile protected var webView: WebView? = null
    private var reap: Runnable? = null

    init {
        // Under real memory pressure the idle timer is far too slow: the OS wants memory now and a
        // Chromium renderer is one of the largest things we hold. Reap on the main thread (WebView).
        app.vela.ui.MemoryPressure.register { level ->
            if (app.vela.ui.MemoryPressure.isSevere(level)) main.post { cancelReap(); reapNow() }
        }
    }

    /** Extra setup for a freshly created view (an offscreen viewport for virtualized pages, say). */
    protected open fun configure(view: WebView) {}

    /** The object the page sees as `VelaBridge`. A fetcher whose script reports more than one
     *  result kind returns its own object here; its `onResult` must call [deliver]. */
    protected open fun bridge(): Any = Bridge()

    /** Hand request [id] its payload (the bridge's `onResult`). A stale page's id is already
     *  gone, so a late call is a no-op. */
    protected fun deliver(id: String, payload: String) {
        pending.remove(id)?.complete(payload)
    }

    /** Whether the page may navigate to [url] (http/https only, already checked). A scrape that
     *  must stay on one host refuses the rest, so an action link cannot walk it off the page. */
    protected open fun allowNavigation(url: Uri): Boolean = true

    /** The view was destroyed (idle reap or memory pressure): drop anything tied to it, such as a
     *  warmed session, so the next fetch rebuilds it. */
    protected open fun onReaped() {}

    /** Run [js] in the current page on the main thread (no-op when there is no view). */
    protected suspend fun evaluate(js: String) = withContext(Dispatchers.Main) { webView?.evaluateJavascript(js, null) }

    /** A page finished loading for the request [requestId]: evaluate the extractor for it. */
    protected abstract fun onPageFinished(view: WebView, url: String?, requestId: String)

    /** One fetch: serialized, the view kept awake for its duration, asleep and on the reap timer after. */
    protected suspend fun <T> session(block: suspend () -> T): T = mutex.withLock {
        cancelReap()
        try {
            withContext(Dispatchers.Main) { webView?.onResume() }
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { runCatching { webView?.onPause() } }
            scheduleReap()
        }
    }

    /** Register a request id, run [start] with it (which should [load] a page), and wait for the
     *  bridge to deliver that id's payload, or null after [timeoutMs]. */
    protected suspend fun request(timeoutMs: Long, start: suspend (id: String) -> Unit): String? {
        // "Use Vela without Google": every one of these pages is google.com. A null result is the
        // fetcher's ordinary failure path, so nothing above needs to know why.
        if (app.vela.ui.GoogleFree.on.value) return null
        val id = seq.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        return try {
            withTimeoutOrNull(timeoutMs) {
                start(id)
                deferred.await()
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Load [url] in the (created on demand) view as the page for request [id]. */
    protected suspend fun load(url: String, id: String) = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        currentId = id
        wv.loadUrl(url)
    }

    /** Whether request [id] is still waiting (a late poller for a dead request is a no-op). */
    protected fun isPending(id: String): Boolean = pending.containsKey(id)

    @SuppressLint("SetJavaScriptEnabled")
    protected fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        WebViewIdentity.apply(wv.settings) // desktop UA -> desktop web Maps (mobile deep-links to intent://) + desktop client hints; X-Requested-With still goes out (unremovable, see WebViewIdentity)
        WebProxy.install(wv) // the POST shim, when the proxy is on (WebProxy)
        wv.addJavascriptInterface(bridge(), "VelaBridge")
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w("VelaWeb", "$tag: ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                }
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            // Null unless calibration `webProxy` is on: then GETs go out over Cronet with this
            // WebView's own cookies, without the X-Requested-With header (WebProxy).
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? =
                WebProxy.intercept(request)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme
                if (scheme != "https" && scheme != "http") return true
                return !allowNavigation(u)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                // Bake THIS page's request id in, so a late poller can only complete its own request.
                if (view != null) this@HiddenWebView.onPageFinished(view, url, currentId)
            }
        }
        configure(wv)
        webView = wv
        return wv
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) = deliver(id, payload)
    }

    private fun scheduleReap() {
        reap?.let(main::removeCallbacks)
        val r = Runnable { reapNow() }
        reap = r
        main.postDelayed(r, reapIdleMs)
    }

    private fun cancelReap() {
        reap?.let(main::removeCallbacks)
        reap = null
    }

    /** Destroy the view now. Main thread only. The next fetch re-creates it. */
    protected fun reapNow() {
        webView?.let { runCatching { it.loadUrl("about:blank"); it.destroy() } }
        webView = null
        onReaped()
    }
}
