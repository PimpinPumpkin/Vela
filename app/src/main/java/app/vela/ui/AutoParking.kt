package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.location.ParkWatch
import app.vela.core.model.LatLng
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings > Map, "Save where I park": the parking spot saves itself when a drive ends with the
 * driver leaving the car ([ParkWatch]). Off by default. Fed GPS fixes by the map and the car
 * screen, steps by the map while the car stands still with Vela on screen, and leave signals by
 * the car screen disconnecting, a drive ending on foot, and Vela leaving the screen at the
 * destination. A save goes to the same store and history as the P button's, so an earlier spot
 * is never lost.
 */
object AutoParking {
    val on = mutableStateOf(false)

    private val watch = ParkWatch()
    private val _parked = MutableStateFlow(false)
    /** The car stands still after a drive: what the step feed waits for. */
    val parked: StateFlow<Boolean> = _parked.asStateFlow()
    private val _saved = MutableSharedFlow<LatLng>(extraBufferCapacity = 4)
    /** Each spot saved, for the map to show it. */
    val saved: SharedFlow<LatLng> = _saved.asSharedFlow()
    private var store: app.vela.core.data.ParkingStore? = null

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
        store = app.vela.core.data.ParkingStore(context.applicationContext)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
        if (!value) synchronized(watch) { watch.reset(); _parked.value = false }
    }

    /** A GPS fix: [tSec] on the boot clock, its speed when it has one. */
    fun onFix(tSec: Double, at: LatLng, speedMps: Double?) {
        if (!on.value || SimLocation.point.value != null) return
        synchronized(watch) {
            watch.onFix(tSec, at, speedMps)
            _parked.value = watch.parked
        }
    }

    fun onStep(tSec: Double) {
        if (!on.value) return
        val spot = synchronized(watch) { watch.onStep(tSec).also { _parked.value = watch.parked } }
        spot?.let { save(it, "walked off") }
    }

    /** The phone left the car ([why] goes to the log). */
    fun leave(why: String) {
        if (!on.value) return
        val now = android.os.SystemClock.elapsedRealtimeNanos() / 1e9
        val spot = synchronized(watch) { watch.onLeave(now).also { _parked.value = watch.parked } }
        spot?.let { save(it, why) }
    }

    private fun save(spot: LatLng, why: String) {
        android.util.Log.d("VelaParking", "spot saved: $why")
        store?.save(app.vela.core.model.ParkedSpot(spot.lat, spot.lng, System.currentTimeMillis()))
        _saved.tryEmit(spot)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "auto_parking"
}
