package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.vela.core.VelaConfig
import app.vela.core.data.google.parse.TransitParser
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches public-transit directions through a hidden [WebView] — the same trick
 * as [WebPhotoFetcher], for the same reason: Google's transit routing serves a
 * real itinerary set **only to a genuine browser engine**. A plain HTTP client
 * (OkHttp/curl) asking `/maps/preview/directions` with the transit mode flag
 * gets silently downgraded to a *driving* reply (TLS-fingerprint bot-detection,
 * not headers — verified on-device).
 *
 * A WebView is Chromium, so it loads the desktop `maps/dir/…/!3e3` page as an
 * anonymous, no-login session — exactly like a logged-out browser, which does
 * get transit — and the first transit result set is server-rendered into
 * `window.APP_INITIALIZATION_STATE`. We read that nested state out, hand the raw
 * Google response string back over a JS bridge, and parse it with the keyless
 * [TransitParser]. Best-effort: any failure/timeout returns empty.
 *
 * Per-stop drill-down (intermediate stops + the ridden polyline) is a separate,
 * token-gated `sv1Drc` batchexecute that fires when you expand a trip — a future
 * layer; this returns the results board (times, duration, line badges, agency).
 */
@Singleton
class WebDirectionsFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    // One WebView, one navigation at a time — each request replaces the page.
    private val mutex = Mutex()
    // Per-request id (WebPhotoFetcher's pattern): a previous, timed-out page's still-running poller
    // (the EXTRACT setTimeout loop runs up to ~7 s) must NOT complete a NEWER request's deferred with
    // the old route's payload. The script bakes its request id in, and only that id's deferred is
    // completed (audit 2026-07-06).
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var currentId: String = "" // the id of the page being loaded now — baked into its EXTRACT
    @Volatile private var webView: WebView? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload) // a stale page's id is already gone → no-op
        }
    }

    /** The transit results board for [origin]→[destination], or empty on any
     *  failure/timeout (the caller then offers drive/walk/bike instead). */
    suspend fun transit(origin: LatLng, destination: LatLng): List<TransitItinerary> = mutex.withLock {
        val url = "https://www.google.com/maps/dir/" +
            "${origin.lat},${origin.lng}/${destination.lat},${destination.lng}" +
            "/data=!4m2!4m1!3e3?hl=en&gl=us"
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
        if (raw.isNullOrEmpty()) emptyList()
        else runCatching { TransitParser.parse(raw) }.getOrDefault(emptyList())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, id: String) = withContext(Dispatchers.Main) {
        val wv = webView ?: WebView(context).also {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            // Desktop UA → desktop web Maps (mobile UA deep-links to intent://).
            it.settings.userAgentString = VelaConfig.USER_AGENT
            it.addJavascriptInterface(Bridge(), "VelaBridge")
            it.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme
                    return scheme != null && scheme != "https" && scheme != "http"
                }
                override fun onPageFinished(view: WebView?, u: String?) {
                    // Bake THIS page's request id into the extractor so its (possibly late) poller can only
                    // complete its own deferred, never a newer request's.
                    val idNow = currentId
                    main.postDelayed({ view?.evaluateJavascript(extract(idNow), null) }, SETTLE_MS)
                }
            }
            webView = it
        }
        currentId = id
        wv.loadUrl(url)
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 1_800L

        /** Pull the directions response string out of APP_INITIALIZATION_STATE —
         *  the `)]}'`-guarded array Google embeds under slot [3] (minified key).
         *  Two such strings live there: a ~1.7 KB stub and the real ~165 KB
         *  itinerary payload, so we take the LONGEST and require it to be
         *  substantial (the stub appears first). The SPA fills it a beat after
         *  page-finish, so we poll for up to ~7 s. */
        fun extract(id: String) = """
            (function(){
              var tries = 0;
              function findBest(){
                var s = window.APP_INITIALIZATION_STATE, best = "";
                function scan(x, d){
                  if (d > 6 || x == null) return;
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
