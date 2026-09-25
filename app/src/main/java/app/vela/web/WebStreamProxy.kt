package app.vela.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The WebView proxy (calibration `webProxy`): a WebView's GET goes out through Cronet directly,
 * returns as soon as the HEADERS arrive and STREAMS the body into Chromium as it downloads, with
 * Cronet's own disk cache in front (the WebView's cache never sees an intercepted response, so
 * without one every Google script bundle was downloaded again on every page load). v1 buffered the
 * whole body first and read 2-3x slower on the 4a.
 */
class WebStreamProxy(private val engine: CronetEngine, private val cookies: okhttp3.CookieJar) {
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "webproxy-cb").apply { isDaemon = true } }

    private class Body(val what: String) : InputStream() {
        val q = LinkedBlockingQueue<ByteArray>()
        private var cur: ByteArray? = null
        private var pos = 0
        @Volatile var failed = false
        private var eof = false // Chromium reads again after end-of-stream; answer -1 every time
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            while (cur == null || pos >= cur!!.size) {
                val next = q.poll(30, TimeUnit.SECONDS) ?: run { android.util.Log.w("VelaCronet", "proxy body stalled: $what"); throw IOException("webproxy: body stalled") }
                if (next.isEmpty()) { if (failed) throw IOException("webproxy: body failed"); eof = true; return -1 }
                cur = next; pos = 0
            }
            val n = minOf(len, cur!!.size - pos)
            System.arraycopy(cur!!, pos, b, off, n); pos += n
            return n
        }
    }

    /** A GET as the WebView asked for it, a POST whose [postBody] the page shim handed over (the
     *  WebView never passes a POST body to shouldInterceptRequest), or a CORS preflight ([method]
     *  OPTIONS), sent to [target]. */
    fun fetch(
        req: WebResourceRequest,
        target: String = req.url.toString(),
        postBody: ByteArray? = null,
        postType: String? = null,
        method: String? = null,
    ): WebResourceResponse? {
        val url = target
        val headersReady = CountDownLatch(1)
        var info: UrlResponseInfo? = null
        val body = Body(req.url.host + req.url.path?.take(50))
        val cb = object : UrlRequest.Callback() {
            override fun onRedirectReceived(r: UrlRequest, i: UrlResponseInfo, newLocationUrl: String) { r.followRedirect() }
            override fun onResponseStarted(r: UrlRequest, i: UrlResponseInfo) {
                info = i; headersReady.countDown(); r.read(ByteBuffer.allocateDirect(32 * 1024))
            }
            override fun onReadCompleted(r: UrlRequest, i: UrlResponseInfo, buf: ByteBuffer) {
                buf.flip(); val b = ByteArray(buf.remaining()); buf.get(b); if (b.isNotEmpty()) body.q.put(b); buf.clear(); r.read(buf)
            }
            override fun onSucceeded(r: UrlRequest, i: UrlResponseInfo) { body.q.put(ByteArray(0)) }
            override fun onFailed(r: UrlRequest, i: UrlResponseInfo?, e: CronetException) { body.failed = true; body.q.put(ByteArray(0)); headersReady.countDown() }
            override fun onCanceled(r: UrlRequest, i: UrlResponseInfo?) { body.q.put(ByteArray(0)); headersReady.countDown() }
        }
        val b = engine.newUrlRequestBuilder(url, cb, executor).setHttpMethod(method ?: if (postBody != null) "POST" else "GET")
        req.requestHeaders.forEach { (k, v) ->
            if (!k.equals("X-Requested-With", true) && !(postBody != null && k.equals("Content-Type", true))) b.addHeader(k, v)
        }
        if (postBody != null) {
            b.addHeader("Content-Type", postType ?: "application/x-www-form-urlencoded;charset=UTF-8")
            b.setUploadDataProvider(org.chromium.net.UploadDataProviders.create(postBody), executor)
        }
        val hu = url.toHttpUrlOrNull()
        if (hu != null) cookies.loadForRequest(hu).takeIf { it.isNotEmpty() }?.let { jar -> b.addHeader("Cookie", jar.joinToString("; ") { "${it.name}=${it.value}" }) }
        b.build().start()
        if (!headersReady.await(30, TimeUnit.SECONDS)) return null
        val i = info ?: return null
        if (hu != null) {
            val setCookies = i.allHeadersAsList.filter { it.key.equals("set-cookie", true) }.mapNotNull { okhttp3.Cookie.parse(hu, it.value) }
            if (setCookies.isNotEmpty()) cookies.saveFromResponse(hu, setCookies)
        }
        val ct = i.allHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull() ?: "text/plain"
        val mime = ct.substringBefore(';').trim().ifEmpty { "text/plain" }
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.trim()
        val skip = setOf("content-encoding", "content-length", "content-type", "set-cookie")
        val headers = LinkedHashMap<String, String>()
        i.allHeadersAsList.forEach { if (it.key.lowercase() !in skip) headers[it.key] = it.value }
        val code = i.httpStatusCode
        // WebResourceResponse refuses 3xx (redirects were followed above) and an empty reason phrase.
        if (code in 300..399) return null
        return WebResourceResponse(mime, charset, code, i.httpStatusText.ifEmpty { "OK" }, headers, body)
    }
}
