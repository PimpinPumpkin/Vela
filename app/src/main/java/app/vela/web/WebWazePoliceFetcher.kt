package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import app.vela.MainActivity
import app.vela.core.data.WazePoliceAlert
import app.vela.core.data.WazePoliceAlerts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches live crowdsourced police reports from Waze through a hidden [WebView].
 *
 * Direct OkHttp requests to `https://www.waze.com/live-map/api/georss` are blocked with HTTP 403
 * because Google Cloud bot mitigation requires an active browser session with an enterprise reCAPTCHA
 * action token (`X-Recaptcha-Token: <token>`).
 * Loading `https://www.waze.com/live-map/` in a hidden Chromium WebView executes reCAPTCHA Enterprise,
 * after which same-origin requests carry the token and session cookies to fetch alerts reliably.
 */
@Singleton
class WebWazePoliceFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val seq = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private var readyDeferred: CompletableDeferred<Unit>? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }

        @JavascriptInterface
        fun onHttpError(code: Int) {
            if (code == 403) {
                android.util.Log.w("VelaWazePolice", "HTTP 403 detected - auto-healing WebView session")
                resetSession()
            }
        }

        @JavascriptInterface
        fun log(msg: String) {
            android.util.Log.i("VelaWazePolice", "JS: $msg")
        }
    }

    /** Wipe cookies and reload Waze live map to clear flagged/stale session on HTTP 403. */
    fun resetSession() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pageReady = false
            readyDeferred = CompletableDeferred()
            cachedAlerts = emptyList()
            cachedBox = null
            lastFetchRealtime = 0L
            runCatching {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
                webView?.clearCache(true)
                webView?.loadUrl("https://www.waze.com/live-map/")
            }
        }
    }

    /** Invalidate alert cache so toggle flips or manual refresh force a fresh query. */
    fun invalidateCache() {
        cachedAlerts = emptyList()
        cachedBox = null
        lastFetchRealtime = 0L
    }

    /** Pre-warm the Waze web session if Waze alerts are enabled. */
    fun warmUp() {
        if (app.vela.ui.GoogleFree.on.value || !app.vela.ui.WazeAlertsPrefs.masterOn.value) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val wv = webView
            if (wv == null) {
                ensureWebViewInternal()
            } else {
                MainActivity.attachHiddenView(wv)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewInternal(): WebView {
        webView?.let { return it }
        pageReady = false
        val ready = CompletableDeferred<Unit>()
        readyDeferred = ready
        WebView.setWebContentsDebuggingEnabled(true)
        val dm = context.resources.displayMetrics
        val wv = WebView(context).apply {
            MainActivity.attachHiddenView(this)
            layout(0, 0, dm.widthPixels, dm.heightPixels)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(context)
            val cleanUa = defaultUa.replace("; wv", "").replace("Version/4.0 ", "")
            settings.userAgentString = cleanUa
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
                runCatching {
                    val major = app.vela.core.data.google.BrowserHeaders.chromeMajor(cleanUa)?.toIntOrNull() ?: 135
                    val secChUa = app.vela.core.data.google.BrowserHeaders.secChUaFor(major)
                    val brands = app.vela.core.data.google.BrowserHeaders.brands(secChUa).map { b ->
                        androidx.webkit.UserAgentMetadata.BrandVersion.Builder()
                            .setBrand(b.name).setMajorVersion(b.major).setFullVersion("${b.major}.0.0.0").build()
                    }
                    val meta = androidx.webkit.UserAgentMetadata.Builder()
                        .setBrandVersionList(brands)
                        .setFullVersion("$major.0.0.0")
                        .setFormFactors(listOf("Mobile"))
                        .setPlatform("Android")
                        .setPlatformVersion(android.os.Build.VERSION.RELEASE)
                        .setArchitecture("arm")
                        .setBitness(64)
                        .setModel(android.os.Build.MODEL)
                        .setMobile(true)
                        .setWow64(false)
                        .build()
                    androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings, meta)
                }
            }
            addJavascriptInterface(Bridge(), "VelaBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, true, false)
                }
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    android.util.Log.i("VelaWazePolice", "console [${m.messageLevel()}]: ${m.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    android.util.Log.i("VelaWazePolice", "onPageFinished: $url")
                    if (url?.contains("waze.com") == true && !pageReady) {
                        pageReady = true
                        readyDeferred?.complete(Unit)
                        val dismissTour = """
                            (function() {
                                try {
                                    var tour = document.querySelector('.waze-tour-tooltip__root button');
                                    if (tour) tour.click();
                                    var b = Array.from(document.querySelectorAll('button, a, div')).find(function(el) {
                                        return el.innerText && (el.innerText.includes('understand') || el.innerText.includes('Κατάλαβα') || el.innerText.includes('Accept') || el.innerText.includes('Got it'));
                                    });
                                    if (b) b.click();
                                    var dlClose = document.querySelector('.wz-downloadbar__close-button');
                                    if (dlClose) dlClose.click();
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(dismissTour, null)
                        view?.postDelayed({ view.evaluateJavascript(dismissTour, null) }, 1000L)
                        view?.postDelayed({ view.evaluateJavascript(dismissTour, null) }, 2500L)
                    }
                }
            }
        }
        webView = wv
        wv.loadUrl("https://www.waze.com/live-map/")
        return wv
    }

    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        ensureWebViewInternal()
    }

    private var lastFetchRealtime: Long = 0L
    private var cachedAlerts: List<WazePoliceAlert> = emptyList()
    private var cachedBox: DoubleArray? = null

    suspend fun fetchAlerts(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<WazePoliceAlert>? = mutex.withLock {
        if (app.vela.ui.GoogleFree.on.value) return null
        val allowed = app.vela.ui.WazeAlertsPrefs.enabledCategories()
        if (allowed.isEmpty()) return emptyList()

        val now = android.os.SystemClock.elapsedRealtime()
        val nowMs = System.currentTimeMillis()
        val cBox = cachedBox
        // Fix 2: tolerate 10% shrink so minor pans don't trigger a full re-fetch.
        if (cBox != null && now - lastFetchRealtime < 30_000L) {
            val latTol = (cBox[2] - cBox[0]) * 0.1
            val lngTol = (cBox[3] - cBox[1]) * 0.1
            if (south >= cBox[0] - latTol && west >= cBox[1] - lngTol &&
                north <= cBox[2] + latTol && east <= cBox[3] + lngTol) {
                return cachedAlerts.filter { !it.isExpired(nowMs) && it.category() in allowed }
            }
        }
        if (now - lastFetchRealtime < 8_000L) {
            return cachedAlerts.filter { !it.isExpired(nowMs) && it.category() in allowed }.ifEmpty { null }
        }

        // Fast path: check local development/companion bridge before touching WebView
        val bridgeAlerts = withContext(Dispatchers.IO) {
            WazePoliceAlerts.tryBridge(south, west, north, east)
        }
        if (bridgeAlerts != null) {
            lastFetchRealtime = now
            val outside = cachedAlerts.filter {
                (it.loc.lat < south || it.loc.lat > north || it.loc.lng < west || it.loc.lng > east) && !it.isExpired(nowMs)
            }
            cachedAlerts = (outside + bridgeAlerts)
                .distinctBy { it.id }
                .filter { !it.isExpired(nowMs) }
                .take(600)
            cachedBox = doubleArrayOf(south, west, north, east)
            android.util.Log.i("VelaWazePolice", "fetched ${bridgeAlerts.size} alert(s) via bridge")
            // Fix 1: filtering already applied inside parseAlerts (via tryBridge -> parseAlerts)
            return cachedAlerts.filter { it.category() in allowed }
        }

        val midLat = (south + north) / 2.0
        val midLng = (west + east) / 2.0
        val env = WazePoliceAlerts.envFor(midLat, midLng)

        val wv = ensureWebView()
        if (!pageReady) {
            val loaded = withTimeoutOrNull(15_000L) { readyDeferred?.await() }
            if (loaded == null) {
                android.util.Log.w("VelaWazePolice", "Waze live-map page load timed out")
                return cachedAlerts.ifEmpty { null }
            }
        }

        val id = seq.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        val url = String.format(
            Locale.US,
            "https://www.waze.com/live-map/api/georss?top=%.5f&bottom=%.5f&left=%.5f&right=%.5f&env=%s&types=alerts,traffic",
            north, south, west, east, env,
        )

        val js = """
            (function() {
                function log(m) {
                    try { window.VelaBridge.log(m); } catch(e) {}
                    try { console.info('[VelaWaze] ' + m); } catch(e) {}
                }
                // Fix 6: reuse cached token within the same Waze session to skip polling loop.
                function getToken() {
                    return new Promise(function(resolve) {
                        if (window._velaToken) {
                            log('reusing cached token, len=' + window._velaToken.length);
                            resolve(window._velaToken);
                            return;
                        }
                        var tries = 0;
                        function poll() {
                            var g = window.grecaptcha;
                            if (g && (g.enterprise || g.execute)) {
                                try {
                                    var runner = g.enterprise || g;
                                    var exec = function() {
                                        runner.execute('6Lf4WdUqAAAAAEUYUvzyLYIkO3PoFAqi8ZHGiDLW', { action: 'api' })
                                            .then(function(t) {
                                                log('token obtained, len=' + (t ? t.length : 0));
                                                window._velaToken = t;
                                                // Tokens expire after ~2 min; clear early to force refresh.
                                                setTimeout(function() { window._velaToken = null; }, 90000);
                                                resolve(t);
                                            })
                                            .catch(function(err) {
                                                log('execute err: ' + err);
                                                resolve('');
                                            });
                                    };
                                    if (runner.ready) {
                                        runner.ready(exec);
                                        return;
                                    } else if (runner.execute) {
                                        exec();
                                        return;
                                    }
                                } catch (e) {
                                    log('ready ex: ' + e);
                                    resolve('');
                                    return;
                                }
                            }
                            if (tries < 40) {
                                tries++;
                                setTimeout(poll, 250);
                            } else {
                                log('recaptcha timed out after 10s');
                                resolve('');
                            }
                        }
                        poll();
                    });
                }

                getToken().then(function(token) {
                    var h = {
                        'Accept': 'application/json, text/plain, */*'
                    };
                    if (token) {
                        h['X-Recaptcha-Token'] = token;
                    }
                    log('fetching georss with token=' + (token ? 'yes' : 'no') + ' url=' + '$url');
                    return fetch('$url', {
                        headers: h,
                        credentials: 'include'
                    });
                })
                .then(function(r) {
                    log('HTTP ' + r.status);
                    if (r.status === 403) {
                        window._velaToken = null;
                        try { window.VelaBridge.onHttpError(403); } catch(e) {}
                    }
                    return r.text().then(function(t) {
                        if (!r.ok) {
                            log('HTTP ' + r.status + ' body=' + t.substring(0, 150));
                            throw new Error('HTTP ' + r.status);
                        }
                        log('fetched bytes: ' + t.length);
                        window.VelaBridge.onResult('$id', t);
                    });
                })
                .catch(function(e) {
                    log('fetch fail: ' + e);
                    window.VelaBridge.onResult('$id', '');
                });
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            wv.evaluateJavascript(js, null)
        }

        val raw = try {
            withTimeoutOrNull(15_000L) { deferred.await() }
        } finally {
            pending.remove(id)
        }

        val finishTime = android.os.SystemClock.elapsedRealtime()
        lastFetchRealtime = finishTime
        if (raw.isNullOrBlank()) {
            android.util.Log.w("VelaWazePolice", "fetch returned empty/null (backing off 30s)")
            lastFetchRealtime = finishTime + 20_000L
            cachedAlerts.ifEmpty { null }
        } else {
            // Parse all categories into cache so enabling a category in settings reuses viewport data.
            val alerts = WazePoliceAlerts.parseAlerts(raw)
            android.util.Log.i("VelaWazePolice", "fetched ${alerts.size} alert(s) via web engine")
            val nowMs = System.currentTimeMillis()
            val outside = cachedAlerts.filter {
                (it.loc.lat < south || it.loc.lat > north || it.loc.lng < west || it.loc.lng > east) && !it.isExpired(nowMs)
            }
            cachedAlerts = (outside + alerts)
                .distinctBy { it.id }
                .filter { !it.isExpired(nowMs) }
                .take(600)
            cachedBox = doubleArrayOf(south, west, north, east)
            cachedAlerts.filter { !it.isExpired(nowMs) && it.category() in allowed }
        }
    }
}
