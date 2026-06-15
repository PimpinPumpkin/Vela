package app.carto.core.data.google

import app.carto.core.CartoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cookie warming for the per-user session.
 *
 * Calibration (2026-06-15) showed that both the search and directions endpoints
 * need NO per-user token — only ordinary cookies. So this is deliberately tiny:
 * a single GET of the maps home page so the shared OkHttp cookie jar picks up
 * Google's consent/NID cookies, after which the data requests behave like one
 * logged-out browser. No API key, no extracted token — that's what keeps Carto
 * on the NewPipe footing.
 *
 * CALIBRATE: in consent-gated regions (much of the EU) this GET may redirect to
 * a consent wall; handling that (posting the consent form to obtain SOCS) is the
 * follow-up for non-US locales.
 */
@Singleton
class GoogleSession @Inject constructor(
    private val http: OkHttpClient,
) {
    @Volatile
    private var warmed = false

    suspend fun ensure() {
        if (warmed) return
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://www.google.com/maps?hl=en&gl=us")
                    .header("User-Agent", CartoConfig.USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                http.newCall(req).execute().use { it.body?.string() }
            }
            warmed = true
        }
    }

    fun invalidate() {
        warmed = false
    }
}
