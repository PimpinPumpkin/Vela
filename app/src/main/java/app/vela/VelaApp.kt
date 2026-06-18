package app.vela

import android.app.Application
import app.vela.ui.Units
import app.vela.ui.theme.AppTheme
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Units.init(this)
        AppTheme.init(this)
    }
}
