package app.vela.vehicle

import android.content.Context
import android.util.Log
import app.vela.core.nav.NavSession
import app.vela.core.vehicle.ClusterNav
import app.vela.core.vehicle.FceClusterOutput
import app.vela.core.vehicle.FcePropertyWriter
import app.vela.core.vehicle.clusterNavOutput
import app.vela.core.vehicle.VehicleSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.Executors

/**
 * Drives a BYD/FCE instrument cluster's navigation view from the live [NavSession] (SPEC 10.9):
 * one arrow and one distance per maneuver, cleared when the drive ends. It is the independent FCE
 * transport, separate from Android Auto's cluster session and from any SOME/IP windshield HUD. Off
 * unless the head unit has the service and the user turned it on; it never touches a HAL on a head
 * unit that does not have it.
 *
 * Binder writes run on one background thread, off the main thread and serialized, because a wedged
 * HAL can block a transact. State changes are read on the caller's scope and handed to that thread.
 */
class FceClusterHud(
    private val appContext: Context,
    private val navSession: NavSession,
    private val writerFactory: () -> FcePropertyWriter = { FceCarPropertyTransport() },
) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vela-fce-cluster").apply { isDaemon = true } }
    private var output: FceClusterOutput? = null
    private var job: Job? = null
    private var guidanceLogged = false

    /** Starts following navigation when the head unit has the FCE service. The setting is read live
     *  on each update, so turning it on or off mid-drive takes effect without a restart. A head unit
     *  without the service collects nothing. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        if (!FceCarPropertyTransport.available()) { support = VehicleSupport.NOT_PRESENT; return }
        output = FceClusterOutput(writerFactory())
        support = VehicleSupport.AVAILABLE
        Log.i(TAG, "FCE cluster transport ready")
        job = navSession.state.onEach { s -> worker.execute { refresh(s) } }.launchIn(scope)
    }

    /** Stops, clearing the cluster back to its idle state. */
    fun stop() {
        job?.cancel()
        job = null
        val out = output
        output = null
        worker.execute { runCatching { out?.stop() } }
        guidanceLogged = false
    }

    private fun refresh(s: NavSession.State) {
        val out = output ?: return
        try {
            val plan = clusterNavOutput(
                enabled = app.vela.ui.FceClusterHudPref.on.value,
                navigating = s.navigating,
                paused = s.paused,
                arrived = s.arrived,
                maneuver = s.route?.maneuvers?.getOrNull(s.nav.stepIndex),
                distanceToNextM = s.nav.distanceToNextManeuver,
            )
            when (plan) {
                is ClusterNav.Stop -> {
                    out.stop()
                    support = VehicleSupport.AVAILABLE
                }
                is ClusterNav.Standby -> out.standby()
                is ClusterNav.Guide -> {
                    out.update(plan.icon, plan.distanceM)
                    support = if (out.active) VehicleSupport.ACTIVE else VehicleSupport.FAILED
                    if (support == VehicleSupport.ACTIVE && !guidanceLogged) {
                        guidanceLogged = true
                        Log.i(TAG, "FCE cluster guidance active")
                    }
                }
            }
        } catch (t: Throwable) {
            support = VehicleSupport.FAILED
            Log.w(TAG, "FCE cluster write failed", t)
        }
    }

    private companion object { const val TAG = "VelaFce" }
}
