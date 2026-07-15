package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.vela.core.VelaConfig
import app.vela.core.data.google.parse.StopDeparturesParser
import app.vela.core.model.StopDepartures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a transit stop's live departure board through a hidden [WebView] — the same
 * anonymous, no-login trick as [WebDirectionsFetcher] and [WebPhotoFetcher]. The board
 * is embedded in the station's own place-details payload (`APP_INITIALIZATION_STATE`), so
 * loading the canonical `?cid=` place page as a real browser engine and reading that state
 * out gives us the schedule with NO extra endpoint and NO account. OkHttp gets a bot-degraded
 * reply (TLS-fingerprint detection), exactly like photos/transit, so it must be a WebView.
 *
 * Verified keyless + anonymous against a live capture (2026-07-12): opening "See departure
 * board" fires no data request, and the times survive a logged-out session (unlike popular
 * times). [StopDeparturesParser] pulls the line/direction/time/headway structure out.
 * Best-effort: any failure/timeout, or a place that isn't a transit stop, returns null.
 */
@Singleton
class WebStopDeparturesFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var currentId: String = ""
    @Volatile private var webView: WebView? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload) // a stale page's id is already gone -> no-op
        }
    }

    /** The departure board for the station with [featureId] (`0x..:0x..`), or null on any
     *  failure/timeout, or when the place isn't a transit stop (no board in its payload). */
    suspend fun fetch(featureId: String): StopDepartures? = mutex.withLock {
        val cid = cidOf(featureId) ?: return null
        // hl=en to match the app's existing transit board (WebDirectionsFetcher also pins en); the
        // clock times/headway come back in 12-hour form the parser reads. gl=us keeps the schedule US-shaped.
        val url = "https://www.google.com/maps?cid=$cid&hl=en&gl=us"
        val id = seq.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val raw = try {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                load(url, id)
                deferred.await()
            }
        } finally {
            pending.remove(id)
        }
        // Debug builds keep the last raw payload on disk (filesDir/depdump.txt): the board schema is
        // positional and agency-shaped, so a "this stop parses wrong" report is only diagnosable from the
        // actual blob. Release builds never write it.
        if (app.vela.BuildConfig.DEBUG && !raw.isNullOrEmpty()) {
            runCatching { java.io.File(context.filesDir, "depdump.txt").writeText(raw) }
        }
        // One-line trace via FILE, not logcat (some devices mute a live pid's logcat after
        // `adb logcat -c`; that muting cost a full debugging session).
        dbg("raw len=${raw?.length ?: -1}")
        if (raw.isNullOrEmpty()) null
        else runCatching { StopDeparturesParser.parse(raw) }
            .onFailure { dbg("parse threw: ${it.message?.take(120)}") }
            .getOrNull()
            .also { dbg("parsed lines=${it?.lines?.size ?: -1}") }
    }

    private fun dbg(msg: String) {
        android.util.Log.i("VelaDepartures", msg)
        runCatching {
            val fdbg = java.io.File(context.filesDir, "departures_debug.txt")
            if (fdbg.length() > 64_000) fdbg.delete() // tiny rolling trace, never unbounded
            fdbg.appendText("${System.currentTimeMillis()} $msg\n")
        }
    }

    /** The WebView's sandboxed renderer process died (OOM kill on a low-RAM phone, or a Chromium
     * crash). Runs on the UI thread. Destroy the dead WebView, null the cache so the next fetch()
     * builds a fresh one, and complete every in-flight request with "" - the caller then returns
     * null, its normal failure path (the sheet just omits the board). Returning true from
     * [WebViewClient.onRenderProcessGone] keeps the APP alive - the unhandled default kills the
     * whole process (minSdk 26 == the API the override needs, so no version check). */
    private fun rendererGone(view: WebView?) {
        dbg("WebView renderer process gone; failing in-flight departures fetch")
        val wv = webView
        webView = null
        runCatching { (wv ?: view)?.destroy() }
        pending.keys.toList().forEach { id -> pending.remove(id)?.complete("") }
    }

    /** cid = LOW half of the `0xHIGH:0xLOW` feature id as unsigned decimal (the `?cid=` deep-link). */
    private fun cidOf(featureId: String): String? {
        val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
        return runCatching { BigInteger(low, 16).toString() }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, id: String) = withContext(Dispatchers.Main) {
        val wv = webView ?: WebView(context).also {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            it.settings.userAgentString = VelaConfig.USER_AGENT // desktop UA -> desktop web Maps
            it.addJavascriptInterface(Bridge(), "VelaBridge")
            it.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme
                    return scheme != null && scheme != "https" && scheme != "http"
                }
                override fun onPageFinished(view: WebView?, u: String?) {
                    // Identity-gated: a renderer crash nulls [webView] and destroys the view
                    // before this delayed block can fire - don't touch the destroyed view.
                    val idNow = currentId
                    main.postDelayed({ if (webView === view) view?.evaluateJavascript(extract(idNow), null) }, SETTLE_MS)
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    rendererGone(view)
                    return true
                }
            }
            webView = it
        }
        currentId = id
        wv.loadUrl(url)
    }

    private companion object {
        // 45s CEILING, not a wait - the deferred completes the moment the bridge fires (~5s on a fast
        // phone). Upstream's 20s was tuned on a Pixel; the fork's target feature phones need ~30s to
        // load desktop Maps in the hidden WebView (measured 30.7s on an MTK M5), so 20s ALWAYS timed
        // out there and the board never showed. Slow-device headroom costs a fast device nothing.
        const val TOTAL_TIMEOUT_MS = 45_000L
        const val SETTLE_MS = 1_600L

        /** Pull the place-details string out of APP_INITIALIZATION_STATE — the longest
         *  `)]}'`-guarded array (the place blob carrying the transit schedule). The SPA fills
         *  it a beat after page-finish, so poll up to ~7 s. Same shape as WebDirectionsFetcher. */
        fun extract(id: String) = """
            (function(){
              var tries = 0;
              function findBest(){
                var s = window.APP_INITIALIZATION_STATE, best = "";
                function scan(x, d){
                  if (d > 7 || x == null) return;
                  if (typeof x === 'string'){ if (x.indexOf(")]}'") === 0 && x.length > best.length) best = x; return; }
                  if (typeof x === 'object'){ for (var k in x) scan(x[k], d + 1); }
                }
                try { scan(s, 0); } catch(e){}
                return best;
              }
              function attempt(){
                var best = findBest();
                if (best && best.length > 5000){ VelaBridge.onResult('$id', best.slice(0, 1500000)); return; }
                if (tries++ < 12) setTimeout(attempt, 600);
                else VelaBridge.onResult('$id', best ? best.slice(0, 1500000) : "");
              }
              attempt();
            })();
        """.trimIndent()
    }
}
