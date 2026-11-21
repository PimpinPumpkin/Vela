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
 *
 * "Also when the car's Bluetooth disconnects" ([bluetooth], off by default) adds Google's other
 * signal: a car-audio or hands-free Bluetooth device dropping ([CarBluetoothReceiver]). What Vela
 * saw of the drive decides first; with Vela not running, one fresh GPS fix does, which needs the
 * location permission for all the time. Both permissions are asked when that switch is turned on.
 */
object AutoParking {
    val on = mutableStateOf(false)
    val bluetooth = mutableStateOf(false)

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
        bluetooth.value = prefs(context).getBoolean(KEY_BT, false)
        store = app.vela.core.data.ParkingStore(context.applicationContext)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
        if (!value) synchronized(watch) { watch.reset(); _parked.value = false }
    }

    fun setBluetooth(context: Context, value: Boolean) {
        bluetooth.value = value
        prefs(context).edit().putBoolean(KEY_BT, value).apply()
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

    /** The phone left the car ([why] goes to the log). True when a spot was saved. */
    fun leave(why: String): Boolean {
        if (!on.value) return false
        val now = android.os.SystemClock.elapsedRealtimeNanos() / 1e9
        val spot = synchronized(watch) { watch.onLeave(now).also { _parked.value = watch.parked } }
        spot?.let { save(it, why) }
        return spot != null
    }

    /**
     * The car's Bluetooth dropped. What Vela saw of the drive decides first: a spot if the car
     * stood still, nothing if it was last seen moving (a glitch mid-drive). With no fix of its
     * own, a GPS fix taken now, or the system's last one if under [BT_LAST_FIX_MAX_S] old, at
     * [BT_MAX_ACC_M] or better and slower than a walk. Calls [done] once, within
     * [BT_FIX_TIMEOUT_MS].
     */
    fun carBluetoothGone(context: Context, done: () -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) done() }
        if (!on.value || !bluetooth.value || SimLocation.point.value != null) { finish(); return }
        if (leave("car Bluetooth disconnected")) { finish(); return }
        val now = android.os.SystemClock.elapsedRealtimeNanos() / 1e9
        if (synchronized(watch) { watch.movingAt(now) }) { finish(); return }
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (lm == null || !fine) { finish(); return }
        fun usable(loc: android.location.Location?): Boolean {
            if (loc == null) return false
            val ageS = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9
            return ageS <= BT_LAST_FIX_MAX_S && (!loc.hasAccuracy() || loc.accuracy <= BT_MAX_ACC_M) &&
                (!loc.hasSpeed() || loc.speed < ParkWatch.WALK_MAX_MPS)
        }
        fun take(loc: android.location.Location?, why: String) {
            if (usable(loc)) save(LatLng(loc!!.latitude, loc.longitude), why)
            finish()
        }
        @Suppress("MissingPermission")
        val last = runCatching { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) }.getOrNull()
        val freshS = last?.let { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9 } ?: Double.MAX_VALUE
        if (freshS <= BT_FRESH_S && usable(last)) { take(last, "car Bluetooth disconnected, last fix"); return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({ take(last, "car Bluetooth disconnected, last fix") }, BT_FIX_TIMEOUT_MS)
        // An explicit listener with all four callbacks: the lambda form crashes on Android 10 and below.
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) { take(location, "car Bluetooth disconnected, new fix") }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        @Suppress("MissingPermission", "DEPRECATION")
        runCatching { lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, listener, android.os.Looper.getMainLooper()) }
            .onFailure { take(last, "car Bluetooth disconnected, last fix") }
        main.postDelayed({ runCatching { lm.removeUpdates(listener) } }, BT_FIX_TIMEOUT_MS + 100)
    }

    private fun save(spot: LatLng, why: String) {
        android.util.Log.d("VelaParking", "spot saved: $why")
        store?.save(app.vela.core.model.ParkedSpot(spot.lat, spot.lng, System.currentTimeMillis()))
        _saved.tryEmit(spot)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "auto_parking"
    private const val KEY_BT = "auto_parking_bt"
    /** The system's last GPS fix this fresh is used at once; a drive with any app has one. */
    private const val BT_FRESH_S = 30.0
    /** Past this, the last fix is too old to be where the car is. */
    private const val BT_LAST_FIX_MAX_S = 120.0
    private const val BT_MAX_ACC_M = 50f
    /** A broadcast receiver has about ten seconds; the fix gets eight. */
    private const val BT_FIX_TIMEOUT_MS = 8_000L
}
