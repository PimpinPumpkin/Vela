package app.vela.core.data.google

import app.vela.core.config.Calibration
import app.vela.core.config.CalibrationStore
import app.vela.core.data.google.BrowserHeaders.browserXhrHeaders
import app.vela.core.data.google.parse.ReviewFeedParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * On-demand probe of the review feed (`qv9Egd`) from a clean machine, run by
 * `.github/workflows/review-feed-probe.yml` so nothing touches the maintainer's own phones or
 * connection (2026-09-25). The feed stays off in the app (`nativeReviewFeed` 0) because it answered
 * 0 reviews on the one full Google session tested, and the parser was only ever built from
 * limited-view replies (5 reviews). This separates the two explanations:
 *
 * - it sends the app's own request, once with the empty first-page token the app sends (`[10,""]`)
 *   and once with a null token (`[10,null]`), each twice a few seconds apart (a fresh session's
 *   first answer is often stripped);
 * - for every reply it prints `FEEDPROBE|variant|bytes|parsed=N|end=..|next=..|rawIds=M`, where
 *   `rawIds` counts review ids in the raw text independently of the parser. rawIds > parsed means
 *   a PARSE miss; both 0 means the request itself got nothing;
 * - the raw replies are saved under `core/build/feed-probe/` for the workflow to upload.
 *
 * Skipped without -DvelaFeedProbe=true.
 */
class ReviewFeedProbeTest {
    private val on = System.getProperty("velaFeedProbe") == "true"

    private fun calibration(): Calibration {
        val f = listOf(File("../calibration.json"), File("calibration.json")).first { it.exists() }
        return CalibrationStore.parseBundle(f.readText()) ?: error("calibration.json did not parse")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val store = HashMap<String, List<Cookie>>().apply {
                val consent = listOf(
                    Cookie.Builder().domain("google.com").name("SOCS").value("CAESHAgBEhIaAB").path("/").build(),
                    Cookie.Builder().domain("google.com").name("CONSENT").value("YES+").path("/").build(),
                )
                for (h in listOf("www.google.com", "google.com")) put(h, consent)
            }
            @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                store[url.host] = (store[url.host].orEmpty().associateBy { it.name } + cookies.associateBy { it.name }).values.toList()
            }
            @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
        })
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun feed(cal: Calibration, inner: String): Pair<Int, String> {
        val freq = "[[[\"qv9Egd\",${kotlinx.serialization.json.JsonPrimitive(inner)},null,\"generic\"]]]"
        val req = Request.Builder()
            .url("https://www.google.com/maps/_/MapsWizUi/data/batchexecute?rpcids=qv9Egd&source-path=%2Fmaps&hl=en&gl=us&_reqid=${(1000..99999).random()}&rt=c")
            .post("f.req=${enc(freq)}&".toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType()))
            .browserXhrHeaders(cal.userAgent, cal.secChUa, MAPS_REFERER)
            .header("X-Same-Domain", "1")
            .apply { if (cal.rpcContext.isNotBlank()) header("x-maps-diversion-context-bin", cal.rpcContext) }
            .build()
        http.newCall(req).execute().use { r -> return r.code to r.body?.string().orEmpty() }
    }

    // Review ids are base64 protobufs that start "ChZDSUhN" / "ChdDSUhN" / "ChRDSUhN" (CIHM...).
    private val reviewId = Regex("""Ch[RZd]DSUhN[A-Za-z0-9_\-]{8,}""")

    @Test
    fun reviewFeedFromACleanMachine() {
        assumeTrue("set -DvelaFeedProbe=true to reach Google", on)
        val cal = calibration()
        val out = File("build/feed-probe").apply { mkdirs() }
        val places = listOf(
            "coop" to "0x8085299fd6f41a23:0x10d37b4cae550f0", // Davis Food Co-op, the fixture business
        )
        for ((name, fid) in places) {
            val base = cal.reviewFeedProto.replace("{FID}", fid)
            val variants = listOf(
                "empty" to base.replace("{TOKEN}", ""),
                "null" to base.replace("\"{TOKEN}\"", "null"),
            )
            for ((v, inner) in variants) {
                for (attempt in 1..2) {
                    val (code, raw) = runCatching { feed(cal, inner) }.getOrElse { -1 to "ERROR ${it.javaClass.simpleName}: ${it.message}" }
                    File(out, "$name-$v-$attempt.txt").writeText(raw)
                    val parsed = runCatching { ReviewFeedParser.parse(raw) }.getOrNull()
                    val ids = reviewId.findAll(raw).map { it.value.take(24) }.toSet().size
                    println(
                        "FEEDPROBE|$name-$v-$attempt|http=$code|bytes=${raw.length}|parsed=${parsed?.reviews?.size ?: "unreadable"}" +
                            "|end=${parsed?.end}|next=${parsed?.nextToken != null}|rawIds=$ids",
                    )
                    Thread.sleep(4000)
                }
            }
        }
    }
}
