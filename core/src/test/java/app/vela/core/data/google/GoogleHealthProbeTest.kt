package app.vela.core.data.google

import app.vela.core.config.Calibration
import app.vela.core.config.CalibrationStore
import app.vela.core.data.google.BrowserHeaders.browserHeaders
import app.vela.core.data.google.BrowserHeaders.browserXhrHeaders
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The daily Google health check (`.github/workflows/google-health.yml`), and an on-demand probe:
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*GoogleHealthProbeTest' -DvelaLive=true --rerun-tasks
 *
 * Runs the app's own request builders and parsers against the repo's `calibration.json` (the
 * bundle the fleet adopts), from the Davis fixture. Each check prints one `HEALTH|check|status|detail`
 * line: OK, BLOCKED (Google refused the client outright: a 403/429, the sorry page, a consent wall,
 * which a datacenter IP gets and a phone does not) or DRIFT (an answer came back and the parsers
 * could not read what they expect from it). Only DRIFT fails the test; BLOCKED says nothing about
 * the calibration. Answers from a CI runner are the stripped "slim" flavor (no review counts), so
 * nothing here asks for richness. Skipped without -DvelaLive=true, so the normal test run never
 * touches the network.
 */
class GoogleHealthProbeTest {
    private val live = System.getProperty("velaLive") == "true"

    @org.junit.After fun resetViewport() { BrowserViewport.set(1024, 768) }

    private val davis = LatLng(38.5449, -121.7405)
    private val sacramento = LatLng(38.5816, -121.4944)

    private class Blocked(msg: String) : Exception(msg)

    private fun calibration(): Calibration {
        val f = listOf(File("../calibration.json"), File("calibration.json")).first { it.exists() }
        return CalibrationStore.parseBundle(f.readText()) ?: error("calibration.json did not parse")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            // The consent cookies the app's own jar seeds (CoreModule), so an EU runner is not
            // bounced to consent.google.com.
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

    private fun get(cal: Calibration, url: String): String {
        val req = Request.Builder().url(url).browserXhrHeaders(cal.userAgent, cal.secChUa, MAPS_REFERER).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val finalUrl = r.request.url.toString()
            if (r.code == 403 || r.code == 429 || "/sorry/" in finalUrl || "consent.google" in finalUrl) {
                throw Blocked("HTTP ${r.code} ${r.request.url.encodedPath}")
            }
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} ${r.request.url.encodedPath}")
            return body
        }
    }

    private fun check(name: String, results: MutableList<String>, block: () -> String) {
        val line = try {
            "HEALTH|$name|OK|${block()}"
        } catch (e: Blocked) {
            "HEALTH|$name|BLOCKED|${e.message}"
        } catch (e: Throwable) {
            "HEALTH|$name|DRIFT|${e.javaClass.simpleName}: ${e.message}"
        }
        println(line)
        results += line
    }

    @Test
    fun googleEndpointsAnswerInTheShapeTheParsersRead() {
        assumeTrue("set -DvelaLive=true to reach Google", live)
        val cal = calibration()
        BrowserViewport.set(1920, 945) // what a phone sends now: a real desktop window, not the captured 1024x768
        println("HEALTH|calibration|OK|version ${cal.version}, ${cal.userAgent.substringAfter("Chrome/").substringBefore(' ')}")
        // The session warm the app does first (GoogleSession): a document navigation to Maps.
        runCatching {
            http.newCall(Request.Builder().url(cal.sessionWarmUrl).browserHeaders(cal.userAgent, cal.secChUa).build()).execute().use { it.body?.string() }
        }
        val results = mutableListOf<String>()

        check("search", results) {
            val q = "coffee"
            val url = "${cal.searchEndpoint}&q=${enc(q)}&pb=${enc(SearchPb.build(q, davis, cal.searchPb))}"
            val places = SearchParser.parse(q, GoogleResponse.parse(get(cal, url)), davis, cal.paths).places
            val named = places.filter { it.name.isNotBlank() && it.location.distanceTo(davis) < 30_000 }
            check(named.size >= 5) { "only ${named.size} of ${places.size} results named and near Davis" }
            "${places.size} results, ${named.size} named within 30 km"
        }

        var plainKm = 0.0
        check("directions", results) {
            val url = "${cal.directionsEndpoint}&pb=${enc(DirectionsPb.build(davis, sacramento, TravelMode.DRIVE, cal.directionsPb))}"
            val routes = DirectionsParser.parse(GoogleResponse.parse(get(cal, url)), cal.directionsPaths)
            val top = routes.firstOrNull() ?: error("no routes")
            plainKm = top.distanceMeters / 1000
            val min = top.durationSeconds / 60
            check(plainKm in 15.0..45.0) { "distance $plainKm km" }
            check(min in 10.0..90.0) { "duration $min min" }
            "${routes.size} routes, ${"%.1f".format(plainKm)} km, ${"%.0f".format(min)} min, traffic=${top.durationInTrafficSeconds != null}"
        }

        check("directions-avoid-highways", results) {
            val url = "${cal.directionsEndpoint}&pb=${enc(DirectionsPb.build(davis, sacramento, TravelMode.DRIVE, cal.directionsPb, avoidHighways = true))}"
            val top = DirectionsParser.parse(GoogleResponse.parse(get(cal, url)), cal.directionsPaths).firstOrNull() ?: error("no routes")
            val km = top.distanceMeters / 1000
            // The flag rides the pb's feature block (DirectionsPb.withAvoid). Honored, the trip leaves
            // I-80 for the river road, several kilometers longer; ignored, it is the same route.
            if (plainKm > 0) check(km > plainKm + 3) { "avoid ignored: $km km vs $plainKm km plain" }
            "${"%.1f".format(km)} km (plain ${"%.1f".format(plainKm)})"
        }

        check("suggest", results) {
            val q = "1451 W Covell"
            val pb = "!2i5!4m12!1m3!1d20000!2d${davis.lng}!3d${davis.lat}!2m3!1f0!2f0!3f0!3m2!1i${BrowserViewport.width}!2i${BrowserViewport.height}!4f13.1" +
                "!7i20!10b1!12m6!1m2!18b1!30b1!2m2!1i203!2i100!19m4!1m3!1i1!2i1!3i1!20m1!1e1"
            val url = "https://www.google.com/s?tbm=map&gs_ri=maps&suggest=p&authuser=0&hl=en&gl=us&pb=${enc(pb)}&q=${enc(q)}&tch=1&ech=1"
            val r = SuggestParser.parse(get(cal, url))
            check(r.places.isNotEmpty()) { "no place rows (${r.queries.size} query rows)" }
            "${r.places.size} places, first: ${r.places.first().name.take(40)}"
        }

        // The batchexecute RPCs a place tap uses: the review feed and the photo gallery. Both need
        // Calibration.rpcContext; a changed value shows up here as an empty answer.
        val coop = "0x8085299fd6f41a23:0x10d37b4cae550f0" // Davis Food Co-op, the fixture business
        fun rpc(id: String, inner: String): String {
            val freq = "[[[\"$id\",${kotlinx.serialization.json.JsonPrimitive(inner)},null,\"generic\"]]]"
            val req = Request.Builder()
                .url("https://www.google.com/maps/_/MapsWizUi/data/batchexecute?rpcids=$id&source-path=%2Fmaps&hl=en&gl=us&_reqid=${(1000..99999).random()}&rt=c")
                .post(okhttp3.RequestBody.Companion.run { "f.req=${enc(freq)}&".toRequestBody(okhttp3.MediaType.Companion.run { "application/x-www-form-urlencoded;charset=UTF-8".toMediaType() }) })
                .browserXhrHeaders(cal.userAgent, cal.secChUa, MAPS_REFERER)
                .header("X-Same-Domain", "1")
                .apply { if (cal.rpcContext.isNotBlank()) header("x-maps-diversion-context-bin", cal.rpcContext) }
                .build()
            http.newCall(req).execute().use { r ->
                if (r.code == 403 || r.code == 429) throw Blocked("HTTP ${r.code} $id")
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} $id")
                return r.body?.string().orEmpty()
            }
        }
        check("review-feed", results) {
            // A new session's first answer is often stripped (the app retries for the same reason), so
            // one empty reply is not drift: it failed the daily run once, 2026-09-25, and passed on rerun.
            fun ask() = app.vela.core.data.google.parse.ReviewFeedParser.parse(rpc("qv9Egd", cal.reviewFeedProto.replace("{FID}", coop).replace("{TOKEN}", "")))
            val feed = ask()?.takeIf { it.reviews.isNotEmpty() } ?: run { Thread.sleep(3_000); ask() }
                ?: error("unreadable reply")
            check(feed.reviews.isNotEmpty()) { "empty feed (rpcContext no longer opens it?)" }
            "${feed.reviews.size} reviews${if (feed.end && feed.reviews.size < 10) " (end after a short list: Google's limited view)" else ""}"
        }
        check("photos", results) {
            val photos = app.vela.core.data.google.parse.PhotosParser.parse(rpc("hspqX", cal.photosProto.replace("{FID}", coop).replace("{COUNT}", "20")))
            check(photos.isNotEmpty()) { "no photos (rpcContext no longer opens it?)" }
            "${photos.size} photos, ${photos.count { it.postedText != null }} dated"
        }

        val drift = results.filter { "|DRIFT|" in it }
        assertTrue("Google answered in a shape the parsers could not read:\n" + drift.joinToString("\n"), drift.isEmpty())
    }
}
