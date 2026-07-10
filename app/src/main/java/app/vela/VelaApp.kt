package app.vela

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import app.vela.core.diag.DiagLog
import app.vela.diag.AnrWatchdog
import app.vela.diag.CrashCatcher
import app.vela.diag.DiagTree
import app.vela.diag.ExitInfoReader
import app.vela.ui.AppLocale
import app.vela.ui.Onboarding
import app.vela.ui.Traffic
import app.vela.ui.TransitLayer
import app.vela.ui.Units
import app.vela.ui.theme.AppTheme
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.concurrent.thread
import timber.log.Timber

@HiltAndroidApp
class VelaApp : Application() {
    @Inject lateinit var diag: DiagLog
    @Inject lateinit var exitInfo: ExitInfoReader

    /** StrictMode violation signatures already breadcrumbed this process. The startup SharedPreferences
     *  reads fire the same few main-thread-I/O violations repeatedly; deduping here keeps them from
     *  flooding the 300-cap DiagLog ring (StrictMode's penaltyLog still logs every one to Logcat). */
    private val seenStrictMode = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Apply the persisted in-app language to the Application context too (no-op when following the
     *  system), so `getString` from the ViewModel/nav-notification also localizes — resolved at launch
     *  from the saved pref (an in-session change re-reads it on next launch). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate() // Hilt field-injects diag/exitInfo here; must precede any use of them.
        if (BuildConfig.DEBUG) installStrictMode() // installed before the inits so init-time I/O is caught

        // Timber: DiagTree (all builds) forwards WARN/ERROR into the opt-in diagnostics ring so logs
        // become breadcrumbs; the Logcat-facing DebugTree is debug-only.
        Timber.plant(DiagTree(diag))
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        Units.init(this)
        AppTheme.init(this)
        AppLocale.init(this) // resolve the app language (system default) → drives the nav-text locale
        Traffic.init(this)
        TransitLayer.init(this)
        app.vela.ui.SimLocation.init(this)
        app.vela.ui.LiveReviews.init(this)
        app.vela.ui.ShowReviews.init(this)
        app.vela.ui.LoadPhotos.init(this)
        app.vela.ui.HideAdult.init(this)
        app.vela.ui.HideExternalLinks.init(this)
        app.vela.ui.Buildings3d.init(this)
        Onboarding.init(this)
        // Persist any fatal (managed) crash so it survives the restart and can be exported from
        // Settings → Diagnostics next launch.
        CrashCatcher.install(this) { diag.snapshot() }
        // Fold ANRs / native crashes / OOM kills from the OS exit history into the same report dir
        // (API 30+). Reads the ANR trace from disk → off the main thread.
        thread(name = "vela-exit-harvest", isDaemon = true) { exitInfo.harvest() }
        // Live ANR detection in dev only (covers API 26–29 + catches stalls in the moment).
        if (BuildConfig.DEBUG) {
            AnrWatchdog { report ->
                runCatching {
                    CrashCatcher.write(this, "crash-anr-${System.currentTimeMillis()}.txt", "ANR (watchdog)", report, diag.snapshot())
                }
            }.start()
        }
    }

    /** StrictMode (debug only): surfaces main-thread disk/network I/O — the #1 ANR cause — live at
     *  dev time. `penaltyLog` (never `penaltyDeath`): the first SharedPreferences load, MapLibre
     *  native init and framework startup do a few benign main-thread reads; treat the log as a
     *  triage list, not failures. Violations also breadcrumb into DiagLog on API 28+. */
    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        penaltyListener(mainExecutor) { v ->
                            // Dedupe by (violation type + first app frame): record each DISTINCT
                            // main-thread-I/O site once so a repeated startup pref read can't flood
                            // the ring. penaltyLog above still logs every occurrence to Logcat.
                            val appFrame = v.stackTrace.firstOrNull { it.className.startsWith("app.vela") }
                                ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "?"
                            if (seenStrictMode.add("${v.javaClass.name}@$appFrame")) {
                                diag.record("strictmode", v.message ?: "thread violation", v.stackTraceToString())
                            }
                        }
                    }
                }
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }
}
