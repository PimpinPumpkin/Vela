package app.vela.core.net

import okhttp3.Interceptor
import java.io.IOException

/**
 * Where Google-host requests are sent from. `:core` builds the one shared OkHttpClient (CoreModule)
 * and cannot see Cronet, which lives in `:app`; the app installs [interceptor] at startup and this
 * hook hands it ONLY requests to google.com hosts. Everything else (OSRM, Nominatim, Photon,
 * Transitous, tiles, images) stays on OkHttp, where its honest Vela user agent belongs.
 *
 * Why: OkHttp's TLS handshake and HTTP/2 settings say "OkHttp" whatever the user agent claims
 * (SPEC 3.6, measured); Chrome's own network stack says Chrome. Null [interceptor], or a transport
 * that throws an IOException before answering, means plain OkHttp, so a Cronet failure degrades to
 * the old behavior instead of failing the request.
 */
/** Request tag (2026-09-23): send this Google request with the WebView's Google session, not the
 *  app's. The app's session is new every launch and Google gives new sessions a limited view (5
 *  reviews, and popular times missing on busy places like a big-box store); the WebView's has aged.
 *  The Cronet transport honors it; without Cronet the request keeps the app's session. */
object AgedSession

object GoogleTransport {
    @Volatile var interceptor: Interceptor? = null

    /** The hosts the transport carries. */
    fun carries(host: String): Boolean = host == "google.com" || host.endsWith(".google.com")

    val hook = Interceptor { chain ->
        val t = interceptor
        val request = chain.request()
        if (t == null || !carries(request.url.host)) return@Interceptor chain.proceed(request)
        try {
            t.intercept(chain)
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            chain.proceed(request) // the transport failed before answering: OkHttp carries this one
        }
    }
}
