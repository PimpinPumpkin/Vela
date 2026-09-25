package app.vela

import android.app.Application
import android.content.Context
import app.vela.core.diag.DiagLog
import app.vela.diag.CrashCatcher
import app.vela.ui.AppLocale
import app.vela.ui.Onboarding
import app.vela.ui.Traffic
import app.vela.ui.TransitLayer
import app.vela.ui.Units
import app.vela.ui.theme.AppTheme
import app.vela.ui.theme.DynamicColor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VelaApp : Application(), coil.ImageLoaderFactory {
    @Inject lateinit var diag: DiagLog
    @Inject lateinit var http: okhttp3.OkHttpClient

    /** Coil with a HARD memory-cache cap. The default budget is ~25% of the app's heap CLASS,
     *  and largeHeap makes that class huge - on a 512 MB large heap Coil happily retains up to
     *  ~128 MB of decoded gallery bitmaps by design, which is most of the "rapid place churn
     *  runs into the ceiling" OOM (issue #182; measured: 3 gallery-bearing places grew the live
     *  Dalvik heap 14 -> 94 MB). 48 MB still holds a couple of screens of thumbnails + a hero
     *  or two; everything else re-decodes from Coil's disk cache, which is untouched. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache {
            coil.memory.MemoryCache.Builder(this)
                .maxSizeBytes(if (app.vela.ui.MemoryPressure.lowRam) 16 * 1024 * 1024 else 48 * 1024 * 1024)
                .build()
        }
        .build()

    /** Apply the persisted in-app language to the Application context too (no-op when following the
     *  system), so `getString` from the ViewModel/nav-notification also localizes — resolved at launch
     *  from the saved pref (an in-session change re-reads it on next launch). */
    /**
     * Hand OS memory pressure to every holder that owns a large or native allocation (ported from
     * vela-dpad, 2026-07-23). Before this existed nothing in the app implemented
     * ComponentCallbacks2, so a TRIM_MEMORY_COMPLETE released nothing at all and the OS had no
     * option but to kill us. Coil's own cache is trimmed here; everything else releases through
     * [app.vela.ui.MemoryPressure].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        app.vela.ui.MemoryPressure.dispatch(level)
        if (app.vela.ui.MemoryPressure.isSevere(level)) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(app.vela.ui.AdaptiveDensity.wrap(base)))
    }

    override fun onCreate() {
        super.onCreate()
        // Device memory class first: the Coil cap and the eager-warm decisions read it.
        app.vela.ui.MemoryPressure.init(this)
        app.vela.ui.SpeechPreload.init(this) // after MemoryPressure: its default reads the RAM tier
        app.vela.ui.FullPlaceLoad.init(this)
        // Google-host requests over Chrome's network stack (Cronet), built lazily on the first one.
        // Calibration `useCronet` 0, or an engine that fails to build, leaves them on OkHttp.
        // Before Cronet opens its cache: a due rotation deletes it (Settings > Privacy).
        app.vela.web.SessionRotation.init(this)
        app.vela.web.GoogleStanding.init(this)
        app.vela.web.GoogleTelemetry.init(this)
        // adb-only: `setprop debug.vela.tune.feedDump 1` saves raw review-feed replies to
        // Android/data/app.vela/files/feeddump/ (ReviewFeedDebug). Never on otherwise.
        if (app.vela.ui.AppTune.on("feedDump", false)) {
            val dir = getExternalFilesDir("feeddump")
            app.vela.core.data.google.ReviewFeedDebug.sink = { raw ->
                dir?.let { java.io.File(it, "qv9Egd-${System.currentTimeMillis()}.txt").writeText(raw) }
            }
        }
        app.vela.web.SessionRotation.appJar = http.cookieJar as? app.vela.core.di.ResettableCookieJar
        app.vela.net.CronetHolder.init(this)
        app.vela.core.net.GoogleTransport.interceptor = app.vela.net.CronetTransport(http.cookieJar, app.vela.web.WebViewCookieJar())
        // Push the device class down to :core, which cannot read an :app holder (same seam as
        // CategoryFilter.enabled). Gates the ambient POI fan-out in GoogleMapsDataSource.
        app.vela.core.data.LowRamMode.enabled = app.vela.ui.MemoryPressure.lowRam
        Units.init(this)
        // The desktop window size Google's requests describe: picked once per install, then kept
        // (a size that changed per launch would be its own oddity). See BrowserViewport.
        run {
            val p = getSharedPreferences("vela_settings", MODE_PRIVATE)
            val idx = p.getInt("browser_viewport", -1).takeIf { it >= 0 }
                ?: kotlin.random.Random.nextInt(app.vela.core.data.google.BrowserViewport.CHOICES.size).also { p.edit().putInt("browser_viewport", it).apply() }
            val (w, h) = app.vela.core.data.google.BrowserViewport.choice(idx)
            app.vela.core.data.google.BrowserViewport.set(w, h)
        }
        app.vela.ui.Clock24.refresh(this) // the 12/24-hour clock setting (issue #357); MainActivity refreshes it on resume
        AppTheme.init(this)
        DynamicColor.init(this)
        AppLocale.init(this) // resolve the app language (system default) → drives the nav-text locale
        Traffic.init(this)
        TransitLayer.init(this)
        app.vela.ui.SatelliteLayer.init(this) // persisted satellite-imagery toggle
        app.vela.ui.LayersButton.init(this) // persisted show/hide of the map layers button
        app.vela.ui.Topography.init(this)
        app.vela.ui.Flock.init(this) // load the persisted surveillance-camera toggle (else it read false every launch)
        app.vela.ui.SpeedCams.init(this) // same init-or-it-reads-false trap as Flock
        app.vela.ui.SpeedCamWarn.init(this) // spoken camera warning (issue #229), off by default
        app.vela.ui.SpeedingAlert.init(this) // spoken over-the-limit alert (issue #404), off by default
        app.vela.ui.BikeSafe.init(this) // bike routes prefer lanes and quiet streets (issue #401), on by default
        // The chooser's sticky avoid toggles, for the nav session's own fetches from the first
        // drive on (a resumed drive or an Android Auto start never opens the phone's chooser).
        getSharedPreferences("vela_settings", MODE_PRIVATE).let { p ->
            app.vela.core.data.RoutingPrefs.avoidTolls = p.getBoolean("avoid_tolls", false)
            app.vela.core.data.RoutingPrefs.avoidHighways = p.getBoolean("avoid_highways", false)
            app.vela.core.data.RoutingPrefs.avoidFerries = p.getBoolean("avoid_ferries", false)
        }
        app.vela.ui.FlockRouteAlert.init(this) // load the persisted "warn about cameras on route" toggle
        app.vela.ui.FlockDetour.init(this) // side-street detours around cameras (issue #600), nested under it
        app.vela.ui.FlockNavAlert.init(this) // plate-camera card + spoken alert while navigating, off by default
        // Parse the bundled on-device ALPR/Flock camera dataset off the main thread (map layer draws
        // instantly, route counts are reliable), then refresh from the hosted manifest so the data updates
        // without an app release (weekly CI cron re-hosts a newer version; a bump swaps it in on next launch).
        CoroutineScope(Dispatchers.IO).launch {
            // The catalog regions' real boundaries (issue #599): until this lands, region picks
            // fall back to their boxes, which is what they always were.
            app.vela.offline.RegionPolys.ensureLoaded(this@VelaApp)
            app.vela.data.FlockCameras.ensureLoaded(this@VelaApp)
            app.vela.data.FlockCameras.refresh(this@VelaApp, app.vela.BuildConfig.FLOCK_MANIFEST_URL)
        }
        app.vela.ui.SimLocation.init(this)
        app.vela.ui.UiScale.init(this)
        app.vela.ui.AppFont.init(this) // user-supplied UI font (issue #252)
        app.vela.ui.MapColors.init(this)
        app.vela.ui.LiveReviews.init(this)
        app.vela.ui.ShowReviews.init(this)
        app.vela.ui.LoadPhotos.init(this)
        app.vela.ui.HideAdult.init(this)
        app.vela.ui.HideExternalLinks.init(this)
        app.vela.ui.GoogleFree.init(this) // "Use Vela without Google": mirrors into the :core NoGoogle flag
        app.vela.ui.Buildings3d.init(this)
        app.vela.ui.RouteTrail.init(this)
        app.vela.ui.RoadLabel.init(this)
        app.vela.ui.PuckStyle.init(this)
        app.vela.ui.HouseNumbers.init(this) // house-number zoom gate (issue #329)
        app.vela.ui.PreferButtons.init(this)
        app.vela.ui.PauseInBar.init(this)
        app.vela.ui.FasterRouteAuto.init(this)
        app.vela.ui.RegionUpdates.init(this)
        app.vela.ui.BuildingOverlay.init(this)
        app.vela.ui.BuildingDebug.init(this)
        app.vela.ui.MapPoiPrefs.init(this)
        // Mirrors into the :core flag NavEngine reads (issue #596).
        app.vela.ui.SpokenRoadNames.init(this)
        app.vela.ui.RoutePicker.init(this)
        app.vela.ui.VoiceSearch.init(this)
        app.vela.ui.ContactsSearch.init(this) // contacts-in-search toggle (issue #243)
        app.vela.diag.NavTrace.init(this) // opt-in nav smoothness trace (issue #251)
        app.vela.ui.map.MapFonts.init(this) // Roboto basemap glyphs (cached patched style + async refresh)
        Onboarding.init(this)
        app.vela.ui.WhatsNew.init(this)
        // Persist any fatal crash (stack trace + breadcrumbs) so it survives the
        // restart and can be exported from Settings → Diagnostics next launch.
        CrashCatcher.install(this) { diag.snapshot() }
    }
}
