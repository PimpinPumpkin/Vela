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
    private val stash = ConcurrentHashMap<String, Pair<String?, ByteArray>>()

    /** The shim's bridge name: random for each process, so the page cannot look for a known one. */
    private val bridgeName: String = "_" + (1..10).map { "abcdefghijklmnopqrstuvwxyz"[kotlin.random.Random.nextInt(26)] }.joinToString("")
    /** The URL parameter the shim tags a POST with, random for the same reason. */
    private val tagParam: String = "_" + (1..6).map { "abcdefghijklmnopqrstuvwxyz"[kotlin.random.Random.nextInt(26)] }.joinToString("")

    private class Bridge {
        @JavascriptInterface
        fun put(id: String, contentType: String?, body: String) = keep(id, contentType, body.toByteArray())

        /** A binary body (Blob, ArrayBuffer, typed array), base64 over the bridge. */
        @JavascriptInterface
        fun putB64(id: String, contentType: String?, b64: String) {
            runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }.getOrNull()?.let { keep(id, contentType, it) }
        }

        /** A Google POST the shim could not hand over (a body type it does not read). */
        @JavascriptInterface
        fun miss(what: String) {
            if (passed.add("miss $what")) android.util.Log.i("VelaWebProxy", "untagged POST body: ${what.take(120)}")
        }

        private fun keep(id: String, contentType: String?, body: ByteArray) {
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
        // Every request a Google page makes after it loads, proxy on or off (Settings > Privacy >
        // Requests to Google). Preflights are the browser's own and not counted.
        if (app.vela.core.net.GoogleUsage.isGoogle(host) && !req.method.equals("OPTIONS", true)) {
            app.vela.core.net.GoogleUsage.record("page resources")
        }
        // Telemetry blocking is its own choice (GoogleTelemetry, default off) and works with the
        // proxy off too; the dial overrides it when set.
        if (isGoogle(host) && blockTelemetry() && isTelemetry(host, path)) {
            if (passed.add("blocked $path")) android.util.Log.i("VelaWebProxy", "answers locally: ${req.method} $host$path")
            return empty(req)
        }
        if (!on()) return null
        val s = stream ?: CronetHolder.engine()?.let { WebStreamProxy(it, jar).also { p -> stream = p } } ?: return null
        if (req.method.equals("GET", true)) return runCatching { s.fetch(req) }.getOrNull()
        // CORS preflights go out the same way, so the WebView never asks Google anything itself.
        if (req.method.equals("OPTIONS", true) && isGoogle(host)) {
            val r = runCatching { s.fetch(req, method = "OPTIONS") }.getOrNull()
            if (r != null) {
                if (passed.add("proxied OPTIONS $path")) android.util.Log.i("VelaWebProxy", "carries: OPTIONS $host$path")
                // An intercepted 204 lost its CORS headers on a 4a (see empty()), so answer 200.
                if (r.statusCode == 204) r.setStatusCodeAndReasonPhrase(200, "OK")
                return r
            }
        }
        if (req.method.equals("POST", true)) {
            val id = url.getQueryParameter(tagParam)
            val body = id?.let { stash.remove(it) }
            if (body != null) {
                val clean = url.buildUpon().clearQuery().apply {
                    url.queryParameterNames.filter { it != tagParam }.forEach { k -> url.getQueryParameters(k).forEach { v -> appendQueryParameter(k, v) } }
                }.build().toString()
                if (passed.add("proxied POST $path")) android.util.Log.i("VelaWebProxy", "carries: POST $host$path")
                // The page's own review-feed request, saved beside the app's replies when the adb-only
                // feedDump switch is on, so the two can be compared byte for byte.
                if (clean.contains("rpcids=qv9Egd")) app.vela.core.data.google.ReviewFeedDebug.sink?.invoke(
                    "PAGE REQUEST\n$clean\n" + req.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" } +
                        "\ncontent-type: ${body.first}\n\n${String(body.second)}",
                )
                val resp = runCatching { s.fetch(req, clean, body.second, body.first) }.getOrNull()
                val sink = app.vela.core.data.google.ReviewFeedDebug.sink
                if (resp != null && sink != null && clean.contains("rpcids=qv9Egd")) {
                    // Debug only: read the reply through so it can be saved, then hand the page a copy.
                    val bytes = runCatching { resp.data?.readBytes() }.getOrNull() ?: ByteArray(0)
                    sink("PAGE REPLY (status ${resp.statusCode})\n" + String(bytes))
                    resp.data = ByteArrayInputStream(bytes)
                }
                return resp
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
function b64(buf){ var a=new Uint8Array(buf), s='', k=0x8000; for(var i=0;i<a.length;i+=k) s+=String.fromCharCode.apply(null,a.subarray(i,i+k)); return btoa(s); }
function tagB(u,ct,buf){ var id='v'+(++n)+'x'+Date.now(); P.putB64(id,ct||null,b64(buf)); var s=String(u); return s+(s.indexOf('?')<0?'?':'&')+'__T__='+id; }
function bytes(b){ if(b instanceof ArrayBuffer) return b; if(ArrayBuffer.isView(b)) return b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength); return null; }
function kind(b){ try{ return Object.prototype.toString.call(b); }catch(e){ return typeof b; } }
var X=XMLHttpRequest.prototype, o=X.open, sh=X.setRequestHeader, sd=X.send;
X.open=function(m,u,a){ this.__vm=String(m).toUpperCase(); this.__vu=u; this.__va=(a===undefined?true:a); this.__vh=[]; return o.apply(this,arguments); };
X.setRequestHeader=function(k,v){ if(this.__vh) this.__vh.push([k,v]); return sh.apply(this,arguments); };
X.send=function(b){ var x=this, post=(x.__vm==='POST'&&g(x.__vu)), s=post?body(b):null;
  var ct=null,h=x.__vh||[]; for(var i=0;i<h.length;i++) if(String(h[i][0]).toLowerCase()==='content-type') ct=h[i][1];
  function reopen(u){ o.call(x,'POST',u,x.__va); for(var j=0;j<h.length;j++) sh.call(x,h[j][0],h[j][1]); }
  if(s!==null){ reopen(tag(x.__vu,ct,s)); return sd.apply(x,arguments); }
  if(post&&b!=null){ var buf=bytes(b);
    if(buf){ reopen(tagB(x.__vu,ct,buf)); return sd.apply(x,arguments); }
    if(b instanceof Blob&&x.__va){ b.arrayBuffer().then(function(ab){ reopen(tagB(x.__vu,ct||b.type,ab)); sd.call(x,b); },function(){ sd.call(x,b); }); return; }
    try{ P.miss('xhr '+kind(b)); }catch(e){} }
  return sd.apply(x,arguments); };
var F=window.fetch; if(F) window.fetch=function(i,init){ var self=this; try{
  if(typeof i!=='string'&&i instanceof URL) i=i.href;
  if(i instanceof Request&&g(i.url)&&i.method==='POST'&&!init){ var rq=i;
    return rq.clone().arrayBuffer().then(function(ab){ return F.call(self,tagB(rq.url,rq.headers.get('content-type'),ab),{method:'POST',headers:rq.headers,body:ab,credentials:rq.credentials,mode:rq.mode,keepalive:rq.keepalive}); },function(){ return F.call(self,rq); }); }
  if(typeof i==='string'&&g(i)&&init&&String(init.method||'').toUpperCase()==='POST'){
  var ct=null,hd=init.headers; if(hd){ if(hd instanceof Headers) ct=hd.get('content-type'); else for(var k in hd) if(k.toLowerCase()==='content-type') ct=hd[k]; }
  var s=body(init.body);
  if(s!==null){ i=tag(i,ct||'text/plain;charset=UTF-8',s); }
  else if(init.body!=null){ var buf=bytes(init.body);
    if(buf){ i=tagB(i,ct,buf); }
    else if(init.body instanceof Blob){ var u=i, bl=init.body; return bl.arrayBuffer().then(function(ab){ return F.call(self,tagB(u,ct||bl.type,ab),init); },function(){ return F.call(self,u,init); }); }
    else { try{ P.miss('fetch '+kind(init.body)); }catch(e){} } } } }catch(e){} return F.apply(self,[i,init]); };
var B=navigator.sendBeacon; if(B) navigator.sendBeacon=function(u,d){ if(g(u)){ var s=(d===undefined||d===null)?'':body(d);
  if(s!==null) u=tag(u,'text/plain;charset=UTF-8',s);
  else { var buf=bytes(d); if(buf) u=tagB(u,'application/octet-stream',buf);
    else if(d instanceof Blob){ var uu=u; d.arrayBuffer().then(function(ab){ B.call(navigator,tagB(uu,d.type||'application/octet-stream',ab),d); },function(){ B.call(navigator,uu,d); }); return true; }
    else { try{ P.miss('beacon '+kind(d)); }catch(e){} } } }
  return B.call(navigator,u,d); };
})();
""".trimIndent()
}
