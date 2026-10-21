package app.vela.vehicle

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import app.vela.car.CarMapRenderer
import app.vela.car.CarLocationAccess
import app.vela.core.location.LocationProvider
import app.vela.core.nav.NavSession
import app.vela.core.vehicle.ClusterRoute
import app.vela.core.vehicle.ClusterTargets
import app.vela.core.vehicle.VehicleSupport
import app.vela.core.vehicle.clusterRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Shows the cluster map on a BYD instrument cluster while a drive is on, and takes it down when
 * the drive ends or the display goes away (SPEC 10.9). It routes to the public presentation
 * display first; there is no ADB fallback in this build, so a head unit with no such display keeps
 * phone-only navigation and the capability reads NOT_PRESENT. Everything is gated on the user's
 * opt-in, and nothing is drawn on a display the user did not choose.
 *
 * The map is a [CarMapRenderer] reading the shared [NavSession]: one route for phone, Android Auto
 * and the cluster. The renderer is reused only while shown and released on teardown, so an idle
 * cluster never holds a native snapshotter.
 */
class ClusterMapController(
    private val appContext: Context,
    private val navSession: NavSession,
    private val locationProvider: LocationProvider,
) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set
    @Volatile var route: ClusterRoute = ClusterRoute.NONE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val displayManager get() = appContext.getSystemService(DisplayManager::class.java)
    private var presentation: ClusterMapPresentation? = null
    private var renderer: CarMapRenderer? = null
    private var navJob: Job? = null
    private var navigating = false
    private var listening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }

    fun start(scope: CoroutineScope) {
        if (navJob != null) return
        refreshSupport()
        navJob = navSession.state
            .map { it.navigating && !it.arrived }
            .distinctUntilChanged()
            .onEach { on -> navigating = on; main.post { reconcile() } }
            .launchIn(scope)
    }

    /** The settings screen calls this when the cluster toggle or the chosen display changes. */
    fun onSettingChanged() { main.post { reconcile() } }

    /** Display names Vela would offer as the cluster, for the settings picker. */
    fun targets(): List<String> = ClusterTargets.candidates(presentationNames(), anyPresentation())

    fun diagnostic(): String {
        val names = displayManager?.displays?.joinToString { "${it.displayId}:${it.name}" }.orEmpty()
        return "cluster: route=$route support=$support target=${chosenName() ?: "none"} displays=[$names]"
    }

    private fun reconcile() {
        val enabled = app.vela.ui.ClusterMapPref.on.value
        val display = if (enabled && navigating) chosenDisplay() else null
        route = clusterRoute(publicDisplay = enabled && chosenDisplay() != null, adbRoute = false)
        if (display == null) { hide(); refreshSupport(); return }
        if (presentation?.display?.displayId == display.displayId && presentation?.isShowing == true) return
        show(display)
    }

    private fun show(display: Display) {
        hide()
        CarLocationAccess.check(appContext)
        val host = app.vela.ui.TopActivity.current ?: appContext
        val r = CarMapRenderer(host, locationProvider, navSession, routeEngine = null, badges = false)
        val p = ClusterMapPresentation(host, display, r)
        val ok = runCatching { p.show() }.onFailure { Log.w(TAG, "cluster presentation refused", it) }.isSuccess
        if (!ok) { support = VehicleSupport.ACCESS_DENIED; return }
        r.follow()
        r.start()
        renderer = r
        presentation = p
        support = VehicleSupport.ACTIVE
        if (!listening) { displayManager?.registerDisplayListener(displayListener, main); listening = true }
        Log.i(TAG, "cluster map on display ${display.displayId}:${display.name}")
    }

    private fun hide() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        renderer?.let { runCatching { it.stop() } }
        renderer = null
    }

    private fun refreshSupport() {
        support = when {
            app.vela.ui.ClusterMapPref.on.value && chosenDisplay() != null -> if (presentation != null) VehicleSupport.ACTIVE else VehicleSupport.AVAILABLE
            presentationNames().any { it in ClusterTargets.candidates(presentationNames(), anyPresentation()) } -> VehicleSupport.AVAILABLE
            else -> VehicleSupport.NOT_PRESENT
        }
    }

    private fun presentationNames(): List<String> =
        displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.map { it.name } ?: emptyList()

    private fun chosenName(): String? {
        val names = presentationNames()
        val stored = app.vela.ui.ClusterMapPref.display.value
        return ClusterTargets.pick(stored, names, anyPresentation())
            ?: if (stored == null) ClusterTargets.candidates(names, anyPresentation()).firstOrNull() else null
    }

    private fun chosenDisplay(): Display? {
        val name = chosenName() ?: return null
        return displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull { it.name == name }
    }

    // An adb-only test hook: on a phone or an emulator with no BYD cluster, target any presentation
    // display (a simulated secondary screen) to exercise the pipeline. Never on in the field.
    private fun anyPresentation(): Boolean = app.vela.ui.AppTune.localOn("clusterAnyDisplay")

    private companion object { const val TAG = "VelaCluster" }
}
