package app.vela.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import app.vela.core.nav.NavSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Auto entry point. Vela registers as a NAVIGATION-category templated car
 * app (see the manifest + automotive_app_desc.xml), so it shows up in the car
 * launcher next to Google Maps. The car UI is deliberately thin: the phone app
 * stays the brain (NavSession is the same singleton the phone feeds), the car
 * screen renders the shared map + the active turn.
 *
 * Sideload note: a non-Play install only appears in AA after enabling
 * "Unknown sources" in Android Auto's developer settings, same as any
 * sideloaded car app. That also means the host is a developer-enabled one, so
 * the permissive host validator below doesn't widen anything in practice.
 */
@AndroidEntryPoint
class VelaCarAppService : CarAppService() {

    @Inject lateinit var navSession: NavSession

    // Accept whatever host the user's device runs (the real AA host, or a dev
    // head unit). Template apps expose no data a hostile "host" could pull that
    // it couldn't get from the phone screen itself.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VelaCarSession(navSession)
}

class VelaCarSession(private val navSession: NavSession) : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarMapScreen(carContext, navSession)
}
