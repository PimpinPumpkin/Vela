package app.vela.core.di

import android.content.Context
import app.vela.core.VelaConfig
import app.vela.core.data.GraphHopperRouteEngine
import app.vela.core.data.MapDataSource
import app.vela.core.data.MockMapDataSource
import app.vela.core.data.RouteEngine
import app.vela.core.data.google.GoogleMapsDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS) // bound a single hung scrape so it can't stall a fan-out
            .dispatcher(
                Dispatcher().apply {
                    // The ambient-POI load fires ~13 parallel google.com requests; OkHttp's default
                    // 5-per-host serialises them into ~3 rounds - the "POIs take ~10 s to load" report
                    // (3 rounds × a slow connection). Let them all go at once → one round.
                    maxRequestsPerHost = 24
                    maxRequests = 64
                },
            )
            .build()

    /**
     * The live data source. Defaults to the mock so the app is fully usable
     * before any Google calibration; flip [VelaConfig.USE_GOOGLE_SOURCE] once
     * the scraper shapes are pinned.
     */
    @Provides
    @Singleton
    fun mapDataSource(
        mock: MockMapDataSource,
        google: GoogleMapsDataSource,
    ): MapDataSource = if (VelaConfig.USE_GOOGLE_SOURCE) google else mock

    /**
     * The on-device routing engine (offline fallback / future always-snap). Reads downloaded per-region
     * CH graphs from **internal** storage (`filesDir/graphs/<id>/` + `index.json`) - fast MMAP; FUSE-mapped
     * external storage was measured I/O-bound for routing's random access. [RoutingGraphStore] (`:app`)
     * downloads graphs + maintains the index here. When no region covers a trip [GraphHopperRouteEngine]
     * returns empty, so `directions()` keeps using OSRM.
     */
    @Provides
    @Singleton
    fun routeEngine(@ApplicationContext context: Context): RouteEngine =
        GraphHopperRouteEngine(File(context.filesDir, "graphs"))
}

/**
 * Minimal per-host cookie store so session cookies from the bootstrap GET ride
 * along on later requests - enough to behave like one browser. Deliberately
 * tiny; swap for a persistent jar if cookies need to survive process death.
 *
 * Pre-seeds Google's **consent** cookies (`SOCS` + `CONSENT`) for google.com so a
 * fresh, cookieless session in the EU/EEA isn't bounced to the
 * `consent.google.com` interstitial before search/directions can run. US sessions
 * are unaffected. Best-effort and the lightest-touch bypass - if Google overwrites
 * `CONSENT` with a `PENDING` value, a consent-redirect could still occur; the full
 * form-POST handshake is the follow-up if reports show the wall persisting.
 */
private class InMemoryCookieJar : CookieJar {
    private val store = HashMap<String, List<Cookie>>()

    init {
        val consent = listOf(
            // "consent recorded" - presence + valid form is what the wall checks.
            Cookie.Builder().domain("google.com").name("SOCS").value("CAESHAgBEhIaAB").path("/").build(),
            Cookie.Builder().domain("google.com").name("CONSENT").value("YES+").path("/").build(),
        )
        for (host in listOf("www.google.com", "google.com", "consent.google.com")) {
            store[host] = consent
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // Don't let Google downgrade our pre-seeded consent to a PENDING value.
        val incoming = cookies.filterNot { it.name == "CONSENT" && !it.value.startsWith("YES") }
        val merged = (store[url.host].orEmpty().associateBy { it.name } +
            incoming.associateBy { it.name }).values.toList()
        store[url.host] = merged
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}
