package app.vela.core.di

import app.vela.core.VelaConfig
import app.vela.core.data.MapDataSource
import app.vela.core.data.MockMapDataSource
import app.vela.core.data.google.GoogleMapsDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
}

/**
 * Minimal per-host cookie store so session cookies from the bootstrap GET ride
 * along on later requests — enough to behave like one browser. Deliberately
 * tiny; swap for a persistent jar if cookies need to survive process death.
 */
private class InMemoryCookieJar : CookieJar {
    private val store = HashMap<String, List<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val merged = (store[url.host].orEmpty().associateBy { it.name } +
            cookies.associateBy { it.name }).values.toList()
        store[url.host] = merged
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}
