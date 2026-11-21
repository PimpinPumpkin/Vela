package app.vela.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import app.vela.core.vehicle.SomeIpHudFrame
import app.vela.core.vehicle.VehicleSupport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes the windshield-HUD road-info frame to a BYD head unit's glass through the SOME/IP
 * gateway `com.ts.car.someip.service` (SPEC 10.9). No ADB and no permission: an ordinary app can
 * bind the gateway and fire events. The frame bytes come from [SomeIpHudFrame]; this does the
 * bind and the Binder transactions.
 *
 * Every transaction runs on one background thread, because a wedged gateway can block a transact
 * forever and must never reach the main thread. The session is: a TYPED bind (the intent type is
 * our own package, which the gateway requires; a typeless bind NPE-crashes the gateway process and
 * the factory AR-HUD with it on unbind), then register a callback, then start the service before
 * the first fire, then fire the frame and re-fire it on a keepalive so the glass does not blank.
 * A head unit without the gateway is the normal case; [available] is false there and nothing is
 * bound.
 */
class SomeIpHudTransport(private val appContext: Context) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vela-someip-hud").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "vela-someip-keepalive").apply { isDaemon = true } }
    private var keepalive: ScheduledFuture<*>? = null

    private var bound = false
    private var server: IBinder? = null
    private var started = false
    private var lastFrame: ByteArray? = null

    // Answers the gateway's callbacks like the AIDL stub would, so registration does not fault.
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> { reply?.writeString(CALLBACK_TOKEN); return true }
                1 -> { reply?.writeNoException(); reply?.writeInt(0); return true }                 // onSomeIpEvent
                2 -> { runCatching { data.readInt() }; reply?.writeNoException(); return true }      // onHalServiceStatus(bool)
                3 -> { reply?.writeNoException(); reply?.writeInt(0); reply?.writeInt(0); return true } // onRequest
                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = worker.execute {
            server = binder
            started = false
            registerAndStartLocked()
            lastFrame?.let { fireLocked(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) = worker.execute { server = null; started = false }
        override fun onBindingDied(name: ComponentName?) = worker.execute { server = null; started = false; support = VehicleSupport.UNAVAILABLE }
        override fun onNullBinding(name: ComponentName?) = worker.execute { support = VehicleSupport.ACCESS_DENIED }
    }

    /** Send a guidance frame and keep it refreshed on the gateway's cadence. */
    fun send(frame: ByteArray) {
        worker.execute {
            lastFrame = frame
            ensureBoundLocked()
            if (server != null) fireLocked(frame)
        }
        startKeepalive()
    }

    /** Fire one blank frame and stop refreshing. The binding is kept for the next drive. */
    fun clear() {
        stopKeepalive()
        worker.execute {
            lastFrame = null
            if (server != null && started) fireLocked(SomeIpHudFrame.clear())
        }
    }

    /** Stop the service and unbind (safe because the bind is typed). */
    fun stop() {
        stopKeepalive()
        worker.execute {
            runCatching { if (server != null && started) exec(TX_STOP, readInt = true) { it.writeLong(SomeIpHudFrame.SERVICE_ID) } }
            if (bound) runCatching { appContext.unbindService(conn) }
            bound = false; server = null; started = false
            support = VehicleSupport.AVAILABLE
        }
    }

    private fun ensureBoundLocked() {
        if (bound) return
        val resolve = Intent(ACTION).setPackage(PKG)
        val info = runCatching { appContext.packageManager.resolveService(resolve, 0) }.getOrNull()?.serviceInfo
        if (info == null) { support = VehicleSupport.NOT_PRESENT; return }
        val intent = Intent(ACTION)
            .setComponent(ComponentName(info.packageName, info.name))
            .setType(appContext.packageName) // TYPED bind with our own package: the gateway keys the
            // client on the type and crashes on unbind if it is null, so this is mandatory and must be us
        bound = runCatching { appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        support = if (bound) VehicleSupport.AVAILABLE else VehicleSupport.ACCESS_DENIED
    }

    private fun registerAndStartLocked() {
        if (server == null) return
        runCatching {
            exec(TX_REGISTER, readInt = false) { it.writeStrongBinder(callback) }
            val rc = exec(TX_START, readInt = true) { it.writeLong(SomeIpHudFrame.SERVICE_ID) }
            lastStartRc = rc
            started = rc == RC_OK || rc == RC_ALREADY_STARTED
            support = if (started) VehicleSupport.ACTIVE else VehicleSupport.FAILED
        }.onFailure { support = VehicleSupport.FAILED; Log.w(TAG, "register/start failed: ${it.javaClass.simpleName}") }
    }

    private fun fireLocked(frame: ByteArray) {
        if (server == null) return
        if (!started) registerAndStartLocked()
        if (!started) return
        runCatching {
            var rc = fireOnceLocked(frame)
            if (rc == RC_NOT_STARTED) { // the service was lost: re-offer and try once more
                started = false
                registerAndStartLocked()
                if (started) rc = fireOnceLocked(frame)
            }
            lastFireRc = rc
            support = if (rc == RC_OK) VehicleSupport.ACTIVE else VehicleSupport.FAILED
        }.onFailure { support = VehicleSupport.FAILED; Log.w(TAG, "fire failed: ${it.javaClass.simpleName}") }
    }

    private fun fireOnceLocked(frame: ByteArray): Int = exec(TX_FIRE, readInt = true) { d ->
        d.writeInt(1)                              // typed-object non-null marker
        d.writeLong(SomeIpHudFrame.TOPIC_ROAD_INFO)
        d.writeLong(System.currentTimeMillis())
        d.writeInt(frame.size)                     // SomeIpData reads an explicit size, then the array (len twice by design)
        d.writeByteArray(frame)
    }

    // One transaction with the interface token written first; reads the int result when the call
    // returns one, and always recycles both parcels. -1 when there is no server.
    private fun exec(code: Int, readInt: Boolean, write: (Parcel) -> Unit): Int {
        val s = server ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVER_TOKEN)
            write(data)
            s.transact(code, data, reply, 0)
            reply.readException()
            return if (readInt) reply.readInt() else 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun startKeepalive() {
        if (keepalive != null) return
        keepalive = ticker.scheduleWithFixedDelay({
            worker.execute { lastFrame?.let { if (server != null) fireLocked(it) } }
        }, KEEPALIVE_MS, KEEPALIVE_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopKeepalive() {
        keepalive?.cancel(false)
        keepalive = null
    }

    @Volatile var lastStartRc: Int = -1; private set
    @Volatile var lastFireRc: Int = -1; private set

    companion object {
        private const val TAG = "VelaSomeIp"
        private const val PKG = "com.ts.car.someip.service"
        private const val ACTION = "com.ts.car.someip.SomeIpServerService"
        private const val SERVER_TOKEN = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CALLBACK_TOKEN = "ts.car.someip.sdk.ISomeIpCallback"
        private const val TX_REGISTER = 1
        private const val TX_START = 4
        private const val TX_STOP = 5
        private const val TX_FIRE = 6
        private const val RC_OK = 0
        private const val RC_NOT_STARTED = 11
        private const val RC_ALREADY_STARTED = 13
        private const val KEEPALIVE_MS = 1000L

        /** True when this head unit has the gateway. A resolve, no bind. */
        fun available(context: Context): Boolean =
            runCatching { context.packageManager.resolveService(Intent(ACTION).setPackage(PKG), 0) }.getOrNull() != null
    }
}
