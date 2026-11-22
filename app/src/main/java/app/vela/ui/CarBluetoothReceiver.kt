package app.vela.ui

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A Bluetooth device disconnecting ([BluetoothDevice.ACTION_ACL_DISCONNECTED], a protected
 * broadcast only the system sends). A car's audio or hands-free unit dropping hands over to
 * [AutoParking.carBluetoothGone] when "Save where I park" and its Bluetooth option are on.
 * Headphones and speakers are other classes and are ignored. Reading the class needs the Nearby
 * devices permission on Android 12 and up; without it nothing happens.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A test of the save path on a phone, where the system broadcast cannot be sent: only with
        // `adb shell setprop debug.vela.tune.parkBtTest 1`, then an explicit broadcast with
        // TEST_ACTION to this receiver. The device check is skipped.
        if (intent.action == TEST_ACTION && AppTune.localOn("parkBtTest")) {
            val pending = goAsync()
            AutoParking.carBluetoothGone(context.applicationContext) { pending.finish() }
            return
        }
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (!AutoParking.on.value || !AutoParking.bluetooth.value) return
        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val cls = runCatching { device?.bluetoothClass?.deviceClass }.getOrNull() ?: return
        if (cls != BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO && cls != BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) return
        val pending = goAsync()
        AutoParking.carBluetoothGone(context.applicationContext) { pending.finish() }
    }

    companion object {
        const val TEST_ACTION = "app.vela.test.CAR_BLUETOOTH_GONE"
    }
}
