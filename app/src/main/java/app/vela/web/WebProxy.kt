package app.vela.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.vela.net.CronetHolder
import app.vela.ui.AppTune

/**
 * Behind calibration `webProxy` (default off): a Google WebView's GET requests are sent by the app
 * over Cronet instead of by the WebView, so they stop carrying `X-Requested-With: app.vela`, the
 * one header that names Vela in WebView traffic (SPEC 3.6). The WebView's OWN cookies go with them
 * ([WebViewCookieJar]), so the page keeps its aged session. `shouldInterceptRequest` never sees a
 * POST body, so POSTs (the review feed's paging) still go out from the WebView itself. Any failure
 * returns null and the WebView loads the request itself, as before.
 */
object WebProxy {
    private val jar = WebViewCookieJar()
    @Volatile private var stream: WebStreamProxy? = null
    private val passed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        if (req.url?.scheme != "https") return null
        if (!AppTune.on("webProxy", false)) return null
        if (!req.method.equals("GET", true)) {
            // What still leaves from the WebView itself (with X-Requested-With), once per path.
            val host = req.url?.host.orEmpty()
            val path = req.url?.path.orEmpty()
            if ((host == "google.com" || host.endsWith(".google.com")) && passed.add("${req.method} $path")) {
                android.util.Log.i("VelaWebProxy", "passes through: ${req.method} $host$path")
            }
            return null
        }
        val s = stream ?: CronetHolder.engine()?.let { WebStreamProxy(it, jar).also { p -> stream = p } } ?: return null
        return runCatching { s.fetch(req) }.getOrNull()
    }
}
