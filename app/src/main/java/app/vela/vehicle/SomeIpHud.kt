package app.vela.vehicle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import app.vela.core.nav.NavSession
import app.vela.core.vehicle.SomeIpHudFrame
import app.vela.core.vehicle.VehicleSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives a BYD windshield HUD from the live [NavSession] over the SOME/IP gateway (SPEC 10.9): the
 * arrow picture, distance, next road and ETA while guiding, cleared when the drive ends. It is the
 * glass transport, separate from the FCE cluster HUD and from the cluster map. Off unless the head
 * unit has the gateway and the user turned it on; it binds nothing on a head unit that lacks it.
 *
 * The arrow is Vela's own maneuver glyph rendered to a transparent PNG (the head unit draws it from
 * field 8); the maneuver id in field 28 is the shared table. Reads navigation on the caller's
 * scope and hands frames to [SomeIpHudTransport], which serializes the Binder work off the main
 * thread.
 */
class SomeIpHud(
    private val appContext: Context,
    private val navSession: NavSession,
) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set

    private var transport: SomeIpHudTransport? = null
    private var job: Job? = null
    private var clearedOnce = false
    private val etaFormat = SimpleDateFormat("HH:mm", Locale.US)

    /** Follows navigation once the head unit has the gateway. The setting is read live on each
     *  update, so turning it on or off mid-drive takes effect without a restart. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        if (!SomeIpHudTransport.available(appContext)) { support = VehicleSupport.NOT_PRESENT; return }
        transport = SomeIpHudTransport(appContext)
        support = VehicleSupport.AVAILABLE
        Log.i(TAG, "SOME/IP HUD transport ready")
        job = navSession.state.onEach { refresh(it) }.launchIn(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
        transport?.stop()
        transport = null
    }

    /** The settings screen calls this when the windshield-HUD toggle changes. Turning it off blanks
     *  the glass at once (the keepalive would otherwise keep re-firing the last frame until the next
     *  navigation update, which may not come while the car is stopped). */
    fun onSettingChanged() {
        if (!app.vela.ui.SomeIpHudPref.on.value) {
            transport?.clear()
            clearedOnce = true
        }
    }

    private fun refresh(s: NavSession.State) {
        val t = transport ?: return
        if (!app.vela.ui.SomeIpHudPref.on.value) {
            if (!clearedOnce) { t.clear(); clearedOnce = true }
            support = VehicleSupport.AVAILABLE
            return
        }
        val maneuver = s.route?.maneuvers?.getOrNull(s.nav.stepIndex)
        val guiding = s.navigating && !s.paused && !s.arrived && maneuver != null
        if (!guiding) {
            if (!clearedOnce) { t.clear(); clearedOnce = true }
            return
        }
        clearedOnce = false
        val frame = runCatching {
            SomeIpHudFrame.guidance(
                f28 = SomeIpHudFrame.maneuverId(maneuver),
                distanceMeters = s.nav.distanceToNextManeuver.toInt(),
                road = maneuver!!.road ?: maneuver.ref ?: "",
                etaHhMm = etaFormat.format(Date(System.currentTimeMillis() + (s.remainingDuration * 1000).toLong())),
                png = arrowPng(maneuver),
            )
        }.getOrNull() ?: return
        t.send(frame)
        support = t.support
    }

    // Vela's own maneuver glyph on a transparent background, as PNG bytes. Null (empty field 8) if
    // it cannot be drawn; the frame still carries the maneuver id and the readouts.
    private fun arrowPng(m: app.vela.core.model.Maneuver): ByteArray? = runCatching {
        val bmp = app.vela.service.NavGlyphs.bitmap(m.type, GLYPH_PX, Color.TRANSPARENT)
        ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
    }.getOrNull()

    private companion object {
        const val TAG = "VelaSomeIp"
        const val GLYPH_PX = 128
    }
}
