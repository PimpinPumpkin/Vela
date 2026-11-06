package app.vela.vehicle

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import app.vela.core.vehicle.FcePropertyWriter

/**
 * Writes integer vehicle properties through the FCE misc HAL (`fce_misc_service_hal_property`,
 * `com.fce.misc.proxy.hardware.property.ICarProperty`), the Binder a few BYD head units' own
 * dual-screen service uses for the cluster's navigation view (SPEC 10.9). The service does not
 * check the caller, so an ordinary app can write it with no root and no ADB. Head units without
 * the service are the normal case: [available] is false there and nothing is attempted.
 *
 * The wire format is `setProperty` (transaction 5) taking a CarPropertyValue written as a non-null
 * marker, property id, area 0, status 0, timestamp 0, the value's class name and the value. Vela
 * writes only the integer properties the cluster reads, so the class is always Integer. All of it
 * is wrapped: a missing service, a denied transaction or an unexpected reply degrades to false and
 * never reaches navigation.
 */
class FceCarPropertyTransport : FcePropertyWriter {
    private var binder: IBinder? = null

    override fun setInt(propertyId: Int, value: Int): Boolean {
        val remote = binder?.takeIf { it.isBinderAlive } ?: lookup("getService")?.also { binder = it } ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1) // the CarPropertyValue is non-null
            data.writeInt(propertyId)
            data.writeInt(AREA_GLOBAL)
            data.writeInt(STATUS_AVAILABLE)
            data.writeLong(0L) // timestamp
            data.writeString(Integer::class.java.name)
            data.writeValue(Integer.valueOf(value))
            remote.transact(TRANSACTION_SET_PROPERTY, data, reply, 0)
            reply.readException() // throws on a HAL-reported failure
            true
        } catch (e: Throwable) {
            if (e is android.os.DeadObjectException) binder = null
            Log.w(TAG, "setProperty 0x${Integer.toHexString(propertyId)} failed: ${e.javaClass.simpleName}")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "VelaFce"
        private const val SERVICE_NAME = "fce_misc_service_hal_property"
        private const val DESCRIPTOR = "com.fce.misc.proxy.hardware.property.ICarProperty"
        private const val TRANSACTION_SET_PROPERTY = 5
        private const val AREA_GLOBAL = 0
        private const val STATUS_AVAILABLE = 0

        /** True when this head unit offers the service. A plain presence check, no write. */
        fun available(): Boolean = lookup("checkService") != null

        private fun lookup(method: String): IBinder? = runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod(method, String::class.java)
                .invoke(null, SERVICE_NAME) as IBinder?
        }.getOrNull()
    }
}
