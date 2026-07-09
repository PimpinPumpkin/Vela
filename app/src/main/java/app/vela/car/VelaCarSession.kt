package app.vela.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import app.vela.car.screen.RoutePreviewCarScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vela.car.screen.CarDeps
import app.vela.car.screen.MainCarScreen
import app.vela.core.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One car projection session. Owns a location collector that feeds [app.vela.core.nav.NavSession]
 * while the car UI is alive — so navigation runs off the car's own GPS feed, independent of the
 * phone Activity/ViewModel (which is stopped during projection).
 *
 * Phase-1 minimal-arbiter note: the phone [app.vela.ui.map.MapViewModel] is the other feeder of the
 * shared [NavSession] singleton. They don't run at once in projection (the phone UI is stopped), and
 * `NavSession.onLocation`/`state.update` are atomic, so a stray double-feed can't crash — it's just
 * redundant. A dedicated `NavCoordinator` arbiter (sole feeder/owner) is the clean follow-up.
 */
class VelaCarSession(private val deps: CarDeps) : Session(), DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var feedJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Feed live GPS into NavSession while the car session is alive. Mirrors the phone's fix gate:
        // GPS fixes with accuracy ≤ 50 m drive guidance; coarser fixes are ignored for nav.
        // Cancel any prior collector first so a re-delivered onCreate can't leak a second one.
        feedJob?.cancel()
        feedJob = scope.launch {
            deps.locationProvider.updates().collect { loc ->
                val gps = loc.provider == android.location.LocationManager.GPS_PROVIDER
                if (gps && (!loc.hasAccuracy() || loc.accuracy <= 50f)) {
                    val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
                    deps.navSession.onLocation(LatLng(loc.latitude, loc.longitude), app.vela.ui.Units.imperial.value, speed)
                }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        feedJob?.cancel()
        scope.cancel()
        deps.stopMapRenderer() // the shared map renderer lives for the session; stop its collector here
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Launched via a navigation intent (assistant "navigate to X" / another app)? Go straight to
        // the route preview for that destination; otherwise the normal landing screen.
        parseNavDest(intent)?.let { (dest, name) ->
            return RoutePreviewCarScreen(carContext, deps, name, dest)
        }
        return MainCarScreen(carContext, deps)
    }

    /** A navigation intent delivered while the app is already running (the host reuses the session). */
    override fun onNewIntent(intent: Intent) {
        val (dest, name) = parseNavDest(intent) ?: return
        carContext.getCarService(ScreenManager::class.java)
            .push(RoutePreviewCarScreen(carContext, deps, name, dest))
    }

    /** Parse an `androidx.car.app.action.NAVIGATE` intent's `geo:` URI into a destination. Handles
     *  `geo:lat,lng` and `geo:0,0?q=lat,lng(Label)`; a free-text `q=` (needs geocoding) returns null
     *  (the app just opens normally). NB `geo:` URIs are OPAQUE (non-hierarchical), so `Uri
     *  .getQueryParameter()` THROWS ("This isn't a hierarchical URI") — we parse the scheme-specific
     *  part by hand instead. */
    private fun parseNavDest(intent: Intent): Pair<LatLng, String>? {
        if (intent.action != CarContext.ACTION_NAVIGATE) return null
        val uri: Uri = intent.data ?: return null
        val ssp = uri.schemeSpecificPart ?: return null
        val coordRe = Regex("""(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*(?:\((.*)\))?""")
        // Prefer a `q=` payload (geo:0,0?q=lat,lng(Label)); else the bare `geo:lat,lng` coords.
        val qVal = ssp.substringAfter("q=", "").takeIf { it.isNotBlank() }
        val target = qVal ?: ssp.substringBefore('?')
        coordRe.find(target)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull(); val lng = m.groupValues[2].toDoubleOrNull()
            // Reject the placeholder 0,0 only when it came from the bare coords (a real q= 0,0 is unheard of).
            if (lat != null && lng != null && !(qVal == null && lat == 0.0 && lng == 0.0)) {
                return LatLng(lat, lng) to m.groupValues[3].ifBlank { "Destination" }
            }
        }
        return null
    }
}
