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
import app.vela.core.config.CalibrationStore
import app.vela.core.data.google.parse.PhotosParser
import app.vela.core.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the full place photo gallery through a hidden [WebView] — Android's real
 * Chromium engine.
 *
 * Google serves the user-photo gallery (the `hspqX` RPC) **only to a genuine
 * browser session**; a plain HTTP client (OkHttp) gets a degraded, Street-View-only
 * reply — bot-detection at the TLS/fingerprint level that headers can't beat
 * (verified on-device: the `/maps` page comes back as a 162 KB token-less "lite"
 * shell). A WebView *is* Chromium, so it loads `maps.google.com` as an **anonymous,
 * no-login** session — exactly like a logged-out browser, which DOES show the
 * photos — then runs a same-origin `fetch` to the photos RPC and hands the raw
 * response back over a JS bridge. Keyless: no API key, no account.
 *
 * It's created lazily (only when a place's photos are first wanted) to keep
 * Google's JS out of the process until then, and it's strictly best-effort: any
 * failure/timeout returns empty and the caller keeps the search-preview photos.
 */
@Singleton
class WebPhotoFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibration: CalibrationStore,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val seq = AtomicInteger()
    // A headless WebView is never attached to a window, so View.postDelayed would
    // never fire — post timers straight to the main looper instead.
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null
    @Volatile private var warm: CompletableDeferred<Unit>? = null

    private inner class Bridge {
        /** Called from the WebView's JS thread when a fetch resolves. */
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }
    }

    /** The full gallery for [featureId] (`0x..:0x..`) — each [Photo] is its URL plus
     *  a "posted" date when present — or empty on any failure.
     *
     *  **RETRIED.** Google's anonymous session intermittently answers the `hspqX` RPC with a
     *  degraded (Street-View-only → filtered-empty) reply and then the *real* user gallery on
     *  a later try — observed live: a place that first showed only its preview populated its
     *  full gallery a few seconds later. A single shot therefore misses the gallery most of the
     *  time. So we re-issue the same-origin fetch up to [MAX_TRIES] times and take the first
     *  non-empty set (same flake, same cure as the reviews fetcher). Still best-effort: all
     *  tries empty → caller keeps the search-preview photo. */
    suspend fun fetch(featureId: String, count: Int = 50): List<Photo> {
        if (!featureId.contains(":")) return emptyList()
        val cal = calibration.current()
        val proto = cal.photosProto.replace("{FID}", featureId).replace("{COUNT}", count.toString())
        return (withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            ensureWarm()
            var photos = emptyList<Photo>()
            for (attempt in 0 until MAX_TRIES) {
                photos = runOnce(cal.photosEndpoint, proto)
                if (photos.isNotEmpty()) break
                delay(RETRY_DELAY_MS)
            }
            photos
        }) ?: emptyList()
    }

    /** One same-origin POST to the photos RPC inside the warmed page, parsed to [Photo]s. */
    private suspend fun runOnce(endpoint: String, proto: String): List<Photo> {
        val id = "p" + seq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val raw = try {
            withContext(Dispatchers.Main) { webView?.evaluateJavascript(script(id, endpoint, proto), null) }
            withTimeoutOrNull(PER_TRY_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
        return if (raw.isNullOrEmpty()) emptyList() else runCatching { PhotosParser.parse(raw) }.getOrDefault(emptyList())
    }

    /** Lazily create the WebView and load maps.google.com once. Concurrent callers
     *  await the same warm-up (the Main dispatcher serialises the setup). */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWarm() = withContext(Dispatchers.Main) {
        warm?.let { it.await(); return@withContext }
        val w = CompletableDeferred<Unit>()
        warm = w
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Desktop Chrome UA → Google serves the desktop web Maps and does NOT try to
        // deep-link into the native app (a mobile UA gets redirected to intent://).
        wv.settings.userAgentString = VelaConfig.USER_AGENT
        wv.addJavascriptInterface(Bridge(), "VelaBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Block intent://, market://, geo:, … so a deep-link redirect can't
                // navigate us off the real web page.
                val scheme = request?.url?.scheme
                return scheme != null && scheme != "https" && scheme != "http"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                // Let the SPA settle past the initial document before we fetch.
                main.postDelayed({ if (!w.isCompleted) w.complete(Unit) }, SETTLE_MS)
            }
        }
        wv.loadUrl(calibration.current().sessionWarmUrl)
        webView = wv
        // Fallback: proceed even if onPageFinished is slow/never fires on the SPA.
        main.postDelayed({ if (!w.isCompleted) w.complete(Unit) }, MAX_WARM_MS)
        w.await()
    }

    /** A same-origin POST to the photos RPC, run inside the page; the raw response
     *  text comes back through [Bridge.onResult]. */
    /** A JS/JSON string literal (quoted + escaped) — `:app` has no JSON lib. */
    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun script(id: String, endpoint: String, proto: String): String {
        val pj = jsStr(proto)   // proto as an escaped JS string literal
        val ej = jsStr(endpoint)
        val idj = jsStr(id)
        return """
            (function(){
              try {
                var freq = JSON.stringify([[["hspqX", $pj, null, "generic"]]]);
                fetch($ej, {method:"POST", credentials:"include",
                    headers:{"content-type":"application/x-www-form-urlencoded;charset=UTF-8"},
                    body:"f.req=" + encodeURIComponent(freq)})
                  .then(function(r){ return r.text(); })
                  .then(function(t){ VelaBridge.onResult($idj, t.slice(0, 800000)); })
                  .catch(function(e){ VelaBridge.onResult($idj, ""); });
              } catch(e){ VelaBridge.onResult($idj, ""); }
            })();
        """.trimIndent()
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val PER_TRY_TIMEOUT_MS = 6_000L
        const val MAX_TRIES = 4
        const val RETRY_DELAY_MS = 1_500L
        const val SETTLE_MS = 1_400L
        const val MAX_WARM_MS = 7_000L
    }
}
