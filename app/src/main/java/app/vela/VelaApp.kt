package app.vela

import android.app.Application
import app.vela.core.diag.DiagLog
import app.vela.diag.CrashCatcher
import app.vela.ui.Onboarding
import app.vela.ui.Traffic
import app.vela.ui.Units
import app.vela.ui.theme.AppTheme
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VelaApp : Application() {
    @Inject lateinit var diag: DiagLog

    override fun onCreate() {
        super.onCreate()
        Units.init(this)
        AppTheme.init(this)
        Traffic.init(this)
        Onboarding.init(this)
        // Persist any fatal crash (stack trace + breadcrumbs) so it survives the
        // restart and can be exported from Settings → Diagnostics next launch.
        CrashCatcher.install(this) { diag.snapshot() }
    }
}
