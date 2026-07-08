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
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VelaApp : Application() {
    @Inject lateinit var diag: DiagLog

    /** Apply the persisted in-app language to the Application context too (no-op when following the
     *  system), so `getString` from the ViewModel/nav-notification also localizes — resolved at launch
     *  from the saved pref (an in-session change re-reads it on next launch). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        Units.init(this)
        AppTheme.init(this)
        AppLocale.init(this) // resolve the app language (system default) → drives the nav-text locale
        Traffic.init(this)
        TransitLayer.init(this)
        app.vela.ui.LiveReviews.init(this)
        Onboarding.init(this)
        // Persist any fatal crash (stack trace + breadcrumbs) so it survives the
        // restart and can be exported from Settings → Diagnostics next launch.
        CrashCatcher.install(this) { diag.snapshot() }
    }
}
