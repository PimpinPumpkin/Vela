package app.vela

import android.app.Application
import app.vela.ui.Units
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Units.init(this)
    }
}
