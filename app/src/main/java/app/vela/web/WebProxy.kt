package app.vela.web

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import app.vela.net.CronetHolder
import app.vela.ui.AppTune
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Behind calibration `webProxy` (default off): a Google WebView's requests are sent by the app over
 * Cronet instead of by the WebView, so they stop carrying `X-Requested-With: app.vela`, the one
 * header that names Vela in WebView traffic (SPEC 3.6). The WebView's OWN cookies go with them
 * ([WebViewCookieJar]), so the page keeps its aged session. Any failure returns null and the WebView
 * loads the request itself, as before.
 *
 * GETs are intercepted directly. `shouldInterceptRequest` never sees a POST body, so for POSTs a
 * document-start script ([SHIM], `webProxyPosts`, default 1) wraps XHR / fetch / sendBeacon on
 * Google pages: it tags the URL with a one-time id and hands the body to a JavaScript interface
 * whose NAME is random per process (a fixed name such as "VelaPost" would be visible to the page's
 * own scripts and give the app away). The tagged POST then arrives here with its body waiting.
 * Measured on a Pixel 9 (2026-09-23) before the shim: the review page's `batchexecute`, Google's
 * `play.google.com/log` and the account bar's `ogads-pa` calls were the POSTs left.
 *
 * Google's page telemetry (`play.google.com/log`, `gen_204` pings, the account bar's async data)
 * can be answered locally with an empty 200 and never sent: Settings > Privacy "Block Google's page
 * telemetry" ([GoogleTelemetry], default OFF since 2026-09-25, was always on with the proxy).
 * Nothing Vela reads depends on it, but a browser that never sends it looks less like one.
 */
object WebProxy {
    private val jar = WebViewCookieJar()
    @Volatile private var stream: WebStreamProxy? = null
    private val passed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val stash = ConcurrentHashMap<String, Pair<String?, String>>()

    /** The shim's bridge name: random for each process, so the page cannot look for a known one. */
    private val bridgeName: String = "_" + (1..10).map { "abcdefghijklmnopqrstuvwxyz"[kotlin.random.Random.nextInt(26)] }.joinToString("")
    /** The URL parameter the shim tags a POST with, random for the same reason. */
    private val tagParam: String = "_" + (1..6).map { "abcdefghijklmnopqrstuvwxyz"[kotlin.random.Random.nextInt(26)] }.joinToString("")

    private class Bridge {
        @JavascriptInterface
        fun put(id: String, contentType: String?, body: String) {
            if (stash.size > 200) stash.clear() // a page that never sent what it stashed
            stash[id] = contentType to body
        }
    }

    private fun on(): Boolean = AppTune.on("webProxy", false)

    private fun isGoogle(host: String) = host == "google.com" || host.endsWith(".google.com")

    /** Installs the POST shim on [wv] (call once, when the view is created). No-op when the proxy
     *  or the shim is off at that moment, or the WebView has no document-start scripts. */
    fun install(wv: WebView) {
        if (!on() || !AppTune.on("webProxyPosts", true)) return
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        wv.addJavascriptInterface(Bridge(), bridgeName)
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
            wv, SHIM.replace("__B__", bridgeName).replace("__T__", tagParam),
            setOf("https://www.google.com", "https://google.com"),
        )
    }

    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        val url = req.url ?: return null
        if (url.scheme != "https") return null
        val host = url.host.orEmpty()
        val path = url.path.orEmpty()
        // Telemetry blocking is its own choice (GoogleTelemetry, default off) and works with the
        // proxy off too; the dial overrides it when set.
        if (isGoogle(host) && blockTelemetry() && isTelemetry(host, path)) {
            if (passed.add("blocked $path")) android.util.Log.i("VelaWebProxy", "answers locally: ${req.method} $host$path")
            return empty(req)
        }
        if (!on()) return null
        val s = stream ?: CronetHolder.engine()?.let { WebStreamProxy(it, jar).also { p -> stream = p } } ?: return null
        if (req.method.equals("GET", true)) return runCatching { s.fetch(req) }.getOrNull()
        if (req.method.equals("POST", true)) {
            val id = url.getQueryParameter(tagParam)
            val body = id?.let { stash.remove(it) }
            if (body != null) {
                val clean = url.buildUpon().clearQuery().apply {
                    url.queryParameterNames.filter { it != tagParam }.forEach { k -> url.getQueryParameters(k).forEach { v -> appendQueryParameter(k, v) } }
                }.build().toString()
                if (passed.add("proxied POST $path")) android.util.Log.i("VelaWebProxy", "carries: POST $host$path")
                return runCatching { s.fetch(req, clean, body.second.toByteArray(), body.first) }.getOrNull()
            }
        }
        // What still leaves from the WebView itself (with X-Requested-With), once per path.
        if (isGoogle(host) && passed.add("${req.method} $path")) {
            android.util.Log.i("VelaWebProxy", "passes through: ${req.method} $host$path")
        }
        return null
    }

    private fun blockTelemetry(): Boolean {
        val dial = AppTune.value("webProxyBlockLogs", -1.0)
        return if (dial >= 0.0) dial >= 0.5 else GoogleTelemetry.block.value
    }

    private fun isTelemetry(host: String, path: String): Boolean =
        (host == "play.google.com" && path.startsWith("/log")) ||
            host.startsWith("ogads-pa.") ||
            path.endsWith("/gen_204")

    /** An empty answer the page's script accepts, preflight included. */
    private fun empty(req: WebResourceRequest): WebResourceResponse {
        val origin = req.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value ?: "https://www.google.com"
        val headers = mapOf(
            "Access-Control-Allow-Origin" to origin,
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to (req.requestHeaders.entries.firstOrNull { it.key.equals("Access-Control-Request-Headers", true) }?.value ?: "*"),
        )
        // 200, not 204: an intercepted 204 reached the page WITHOUT these headers on a 4a (WebView
        // 153), so every blocked log call became a CORS error in the console.
        return WebResourceResponse("text/plain", "utf-8", 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
    }

    /** Wraps XHR, fetch and sendBeacon so a POST to a Google host carries a tag the proxy can pair
     *  with its body. Bodies that are not plain text (FormData, Blob, a Request object) are left
     *  alone and go out from the WebView as before. */
    private val SHIM = """
(function(){
var P=window.__B__; if(!P) return; var n=0;
function g(u){ try{ var h=new URL(u, location.href).hostname; return h==='google.com'||/\.google\.com${'$'}/.test(h); }catch(e){ return false; } }
function body(b){ if(typeof b==='string') return b; if(b instanceof URLSearchParams) return b.toString(); return null; }
function tag(u,ct,b){ var id='v'+(++n)+'x'+Date.now(); P.put(id,ct||null,b); var s=String(u); return s+(s.indexOf('?')<0?'?':'&')+'__T__='+id; }
var X=XMLHttpRequest.prototype, o=X.open, sh=X.setRequestHeader, sd=X.send;
X.open=function(m,u,a){ this.__vm=String(m).toUpperCase(); this.__vu=u; this.__va=(a===undefined?true:a); this.__vh=[]; return o.apply(this,arguments); };
X.setRequestHeader=function(k,v){ if(this.__vh) this.__vh.push([k,v]); return sh.apply(this,arguments); };
X.send=function(b){ var s=(this.__vm==='POST'&&g(this.__vu))?body(b):null;
  if(s!==null){ var ct=null,h=this.__vh; for(var i=0;i<h.length;i++) if(String(h[i][0]).toLowerCase()==='content-type') ct=h[i][1];
    o.call(this,'POST',tag(this.__vu,ct,s),this.__va); for(var j=0;j<h.length;j++) sh.call(this,h[j][0],h[j][1]); }
  return sd.apply(this,arguments); };
var F=window.fetch; if(F) window.fetch=function(i,init){ try{ if(typeof i==='string'&&g(i)&&init&&String(init.method||'').toUpperCase()==='POST'){ var s=body(init.body);
  if(s!==null){ var ct=null,hd=init.headers; if(hd){ if(hd instanceof Headers) ct=hd.get('content-type'); else for(var k in hd) if(k.toLowerCase()==='content-type') ct=hd[k]; }
  i=tag(i,ct||'text/plain;charset=UTF-8',s); } } }catch(e){} return F.apply(this,[i,init]); };
var B=navigator.sendBeacon; if(B) navigator.sendBeacon=function(u,d){ if(g(u)){ var s=(d===undefined||d===null)?'':body(d); if(s!==null) u=tag(u,'text/plain;charset=UTF-8',s); } return B.call(navigator,u,d); };
})();
""".trimIndent()
}
