package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.vela.core.VelaConfig
import app.vela.core.config.CalibrationStore
import app.vela.core.data.google.SearchPb
import app.vela.core.data.google.parse.PopularTimesParser
import app.vela.core.model.Place
import app.vela.core.model.PlaceDetails
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a place's **popular-times** histogram through a hidden [WebView] — the
 * same trick as [WebPhotoFetcher], and for the same reason: the keyless `/search`
 * over OkHttp is **bot-degraded** (TLS-fingerprint) and strips the `[84]`
 * histogram, so we'd wrongly concluded popular times was login-gated. A WebView is
 * real Chromium, so its same-origin search comes back undegraded — the full
 * ~240 KB response *with* `[84]` — for an anonymous, no-login session (proven
 * on-device 2026-06-19; photos work the same way).
 *
 * Warms google.com → maps.google.com (an established anonymous session matters here
 * — a freshly-seeded one still gets a stub), then runs a same-origin `fetch` of the
 * exact search URL the app already builds and parses it with [PopularTimesParser].
 * Best-effort + lazy: any failure returns null and the sheet just omits the section.
 */
@Singleton
class WebPopularTimesFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibration: CalibrationStore,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val seq = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null
    @Volatile private var warm: CompletableDeferred<Unit>? = null

    private inner class Bridge {
        @JavascriptInterface fun onResult(id: String, payload: String) { pending.remove(id)?.complete(payload) }
    }

    /** Rich details for [place] (popular times + editorial/owner descriptions), or
     *  null on any failure / nothing extra for it. */
    suspend fun fetch(place: Place): PlaceDetails? {
        if (place.name.isBlank()) return null
        val cal = calibration.current()
        // A *specific* query (name + address) is essential — a bare-name search comes
        // back as a 20-result [64] list trimmed of [84], while name+address resolves to
        // the single focused result that keeps the histogram. Thread it through BOTH the
        // pb's !1s query block and the q= param so they agree.
        val query = specificQuery(place)
        val pb = SearchPb.build(query, place.location, cal.searchPb)
        val url = "${cal.searchEndpoint}&q=${enc(query)}&pb=${enc(pb)}"
        val id = "pt" + seq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val raw = try {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                ensureWarm()
                withContext(Dispatchers.Main) { webView?.evaluateJavascript(script(id, url), null) }
                deferred.await()
            }
        } finally { pending.remove(id) }
        return if (raw.isNullOrEmpty()) null
        else runCatching { PopularTimesParser.parse(raw, place.featureId) }.getOrNull()
    }

    /** name + address (commas dropped — the geocoder resolves the flat form most
     *  reliably), or name alone if we have no address. The address pins the query to
     *  the one place so Google returns a single focused result with `[84]`, not the
     *  histogram-less 20-result list a bare name yields. */
    private fun specificQuery(place: Place): String {
        val addr = place.address?.replace(',', ' ')?.replace(Regex("\\s+"), " ")?.trim()
        return if (addr.isNullOrBlank()) place.name else "${place.name} $addr"
    }

    /** Warm google.com → maps once. The two-step establishes a real NID; a freshly
     *  consent-seeded session still gets a degraded (histogram-less) search. */
    @SuppressLint("SetJavaScriptEnabled")
    /** Warm the session ahead of the first real fetch (e.g. on search), so popular
     *  times + the other details land faster once a place is opened. Fire-and-forget;
     *  idempotent (a warm already in progress is awaited, not restarted). */
    suspend fun prewarm() {
        runCatching { withTimeoutOrNull(MAX_WARM_MS + 2_000L) { ensureWarm() } }
    }

    private suspend fun ensureWarm() = withContext(Dispatchers.Main) {
        warm?.let { it.await(); return@withContext }
        val w = CompletableDeferred<Unit>()
        warm = w
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie("https://www.google.com", "SOCS=CAESHAgBEhIaAB; path=/; domain=.google.com")
        cm.setCookie("https://www.google.com", "CONSENT=YES+; path=/; domain=.google.com")
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = VelaConfig.USER_AGENT
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(Bridge(), "VelaBridge")
        var finishes = 0
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme
                return scheme != null && scheme != "https" && scheme != "http"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                finishes++
                when (finishes) {
                    1 -> main.postDelayed({ view?.loadUrl(calibration.current().sessionWarmUrl) }, 500)
                    else -> main.postDelayed({ if (!w.isCompleted) w.complete(Unit) }, SETTLE_MS)
                }
            }
        }
        webView = wv
        wv.loadUrl("https://www.google.com/?hl=en&gl=us")
        main.postDelayed({ if (!w.isCompleted) w.complete(Unit) }, MAX_WARM_MS)
        w.await()
    }

    private fun script(id: String, url: String): String {
        val u = jsStr(url)
        val idj = jsStr(id)
        return """
            (function(){
              try {
                fetch($u, {credentials:"include", headers:{"accept-language":"en-US,en;q=0.9"}})
                  .then(function(r){ return r.text(); })
                  .then(function(t){ VelaBridge.onResult($idj, t.slice(0, 2000000)); })
                  .catch(function(e){ VelaBridge.onResult($idj, ""); });
              } catch(e){ VelaBridge.onResult($idj, ""); }
            })();
        """.trimIndent()
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TOTAL_TIMEOUT_MS = 22_000L
        const val SETTLE_MS = 1_200L
        const val MAX_WARM_MS = 9_000L
    }
}
