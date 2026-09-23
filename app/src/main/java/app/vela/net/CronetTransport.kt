package app.vela.net

import android.content.Context
import android.util.Log
import app.vela.ui.AppTune
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cronet, Chromium's own network stack, for Vela's Google requests (2026-09-23). OkHttp's TLS
 * handshake and HTTP/2 settings identify OkHttp whatever the user agent claims; Cronet's are
 * Chrome's (SPEC 3.6: the same ClientHello as desktop Chromium, minus the newest signature
 * algorithms until the Cronet build tracks Chrome's release). One lazily built engine, shared by
 * the API transport ([Transport], behind calibration `useCronet`) and the WebView proxy
 * (`app.vela.web.WebProxy`, behind `webProxy`). If the engine cannot be built the requests stay on
 * OkHttp; nothing depends on Cronet being there.
 */
object CronetHolder {
    @Volatile private var engine: CronetEngine? = null
    @Volatile private var failed = false
    private lateinit var appContext: Context

    fun init(context: Context) { appContext = context.applicationContext }

    /** The shared engine, built on first use (off the main thread: callers are network threads). */
    fun engine(): CronetEngine? {
        engine?.let { return it }
        if (failed || !::appContext.isInitialized) return null
        return synchronized(this) {
            engine ?: runCatching {
                val cache = File(appContext.cacheDir, "cronet").apply { mkdirs() }
                CronetEngine.Builder(appContext)
                    .enableHttp2(true).enableQuic(true).enableBrotli(true)
                    .setStoragePath(cache.absolutePath)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 64L * 1024 * 1024)
                    .build()
            }.onSuccess { Log.i(TAG, "engine ${it.versionString}") }
                .onFailure { failed = true; Log.w(TAG, "engine unavailable, staying on OkHttp", it) }
                .getOrNull()
                .also { engine = it }
        }
    }

    private const val TAG = "VelaCronet"
}

/** The API transport: an OkHttp interceptor that sends the request through Cronet, installed as
 *  `GoogleTransport.interceptor` (google.com hosts only). Cookies stay in OkHttp's jar, redirects
 *  are followed like OkHttp does, Cronet decodes gzip/brotli. Throws IOException before answering
 *  when Cronet is off or broken, which GoogleTransport turns into a plain OkHttp request. */
class CronetTransport(
    private val appCookies: CookieJar,
    /** The WebView's Google session, for requests tagged [app.vela.core.net.AgedSession]. */
    private val agedCookies: CookieJar? = null,
) : Interceptor {
    private val logged = java.util.concurrent.atomic.AtomicInteger()
    private val agedLogged = java.util.concurrent.atomic.AtomicInteger()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "cronet-cb").apply { isDaemon = true } }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!AppTune.on("useCronet", true)) throw IOException("cronet off")
        val engine = CronetHolder.engine() ?: throw IOException("cronet unavailable")
        val req = chain.request()
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var info: UrlResponseInfo? = null
        var error: CronetException? = null
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, i: UrlResponseInfo, newLocationUrl: String) { request.followRedirect() }
            override fun onResponseStarted(request: UrlRequest, i: UrlResponseInfo) { info = i; request.read(ByteBuffer.allocateDirect(64 * 1024)) }
            override fun onReadCompleted(request: UrlRequest, i: UrlResponseInfo, buf: ByteBuffer) {
                buf.flip(); val b = ByteArray(buf.remaining()); buf.get(b); out.write(b); buf.clear(); request.read(buf)
            }
            override fun onSucceeded(request: UrlRequest, i: UrlResponseInfo) { info = i; done.countDown() }
            override fun onFailed(request: UrlRequest, i: UrlResponseInfo?, e: CronetException) { info = i; error = e; done.countDown() }
            override fun onCanceled(request: UrlRequest, i: UrlResponseInfo?) { done.countDown() }
        }
        val b = engine.newUrlRequestBuilder(req.url.toString(), callback, executor).setHttpMethod(req.method)
        req.headers.forEach { (k, v) -> b.addHeader(k, v) }
        val aged = agedCookies != null && req.tag(app.vela.core.net.AgedSession::class.java) != null
        val cookies = if (aged) agedCookies!! else appCookies
        val jar = cookies.loadForRequest(req.url)
        if (jar.isNotEmpty() && req.header("Cookie") == null) b.addHeader("Cookie", jar.joinToString("; ") { "${it.name}=${it.value}" })
        req.body?.let { body ->
            val buf = okio.Buffer(); body.writeTo(buf)
            body.contentType()?.let { if (req.header("Content-Type") == null) b.addHeader("Content-Type", it.toString()) }
            b.setUploadDataProvider(UploadDataProviders.create(buf.readByteArray()), executor)
        }
        val urlReq = b.build()
        urlReq.start()
        // The OkHttp call's own deadline (12 s on the shared client) still bounds the wait.
        val limitMs = chain.call().timeout().timeoutNanos().takeIf { it > 0 }?.let { it / 1_000_000 } ?: 30_000L
        var waited = 0L
        while (!done.await(250, TimeUnit.MILLISECONDS)) {
            waited += 250
            if (chain.call().isCanceled() || waited >= limitMs) { urlReq.cancel(); throw IOException(if (waited >= limitMs) "cronet timeout" else "canceled") }
        }
        error?.let { throw IOException("cronet: ${it.message}", it) }
        val i = info ?: throw IOException("cronet: no response")
        val headers = okhttp3.Headers.Builder().apply {
            i.allHeadersAsList.forEach { (k, v) -> if (!k.equals("content-encoding", true) && !k.equals("content-length", true)) add(k, v) }
        }.build()
        okhttp3.Cookie.parseAll(req.url, headers).takeIf { it.isNotEmpty() }?.let { cookies.saveFromResponse(req.url, it) }
        if ((if (aged) agedLogged else logged).getAndIncrement() < 5) Log.i("VelaCronet", "google over cronet: ${req.url.encodedPath.take(40)} ${i.negotiatedProtocol} ${i.httpStatusCode}${if (aged) " (aged session)" else ""}")
        val proto = when {
            i.negotiatedProtocol.startsWith("h3") || i.negotiatedProtocol.contains("quic") -> Protocol.QUIC
            i.negotiatedProtocol == "h2" -> Protocol.HTTP_2
            else -> Protocol.HTTP_1_1
        }
        return Response.Builder().request(req).protocol(proto).code(i.httpStatusCode).message(i.httpStatusText ?: "")
            .headers(headers)
            .body(out.toByteArray().toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }
}
