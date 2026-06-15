package app.carto.core.data.google

import app.carto.core.CartoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-user session bootstrap — the load-bearing piece, Carto's analogue of
 * NewPipe's player-token extraction.
 *
 * We do one cold GET of the maps home page, keep its cookies (via the shared
 * OkHttp [CookieJar][okhttp3.CookieJar] configured in DI), and scrape the
 * session parameters Google embeds in the page — historically in a
 * `window.APP_INITIALIZATION_STATE = [...]` blob plus a few inline keys like the
 * build label (`cfb2h`). Every later request reuses these, so each install
 * behaves like exactly one browser. No static API key is ever embedded: that is
 * what keeps Carto on the right side of "a user scraped from their own IP".
 *
 * CALIBRATE: the regexes below are plausible but must be verified against a live
 * page capture (devtools → the maps document response). Expect to revisit this
 * every time the home page markup shifts.
 */
@Singleton
class GoogleSession @Inject constructor(
    private val http: OkHttpClient,
) {
    private val params = ConcurrentHashMap<String, String>()

    @Volatile
    private var ready = false

    suspend fun ensure(): Map<String, String> {
        if (ready) return params
        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://www.google.com/maps?hl=en&gl=us")
                .header("User-Agent", CartoConfig.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                // CALIBRATE: pull the real session params out of `body` here.
                BUILD_LABEL.find(body)?.let { params["bl"] = it.groupValues[1] }
                FDR.find(body)?.let { params["fdrfje"] = it.groupValues[1] }
                ready = params.isNotEmpty()
            }
            params
        }
    }

    fun invalidate() {
        ready = false
        params.clear()
    }

    private companion object {
        val BUILD_LABEL = Regex("\"cfb2h\":\"([^\"]+)\"")
        val FDR = Regex("\"FdrFJe\":\"(-?\\d+)\"")
    }
}
