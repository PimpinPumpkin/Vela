package app.vela.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * The silence before every spoken line, in the neural player and on the phone's engine alike
 * (issue #802). The voice's output is paused between prompts so it can stand by, and an output
 * waking from standby, a Bluetooth head unit or headset above all, drops whatever plays while it
 * wakes: the first syllables, when the words were written the instant the stream started.
 */
object SpeechLeadIn {
    /** The lead-in on the phone's own outputs. */
    const val MS = 300L

    /** The lead-in when navigation speech goes to a Bluetooth output, which can take longer to
     *  wake: 300 ms still cut "Starting navigation" to "tarting navigation" for one reporter. */
    const val BLUETOOTH_MS = 700L

    private val NAV_SPEECH: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** [BLUETOOTH_MS] when navigation speech is routed to a Bluetooth output (on Android 13 and
     *  up, the device the system would use for it; before that, any Bluetooth output connected),
     *  else [MS]. */
    fun ms(context: Context): Long = if (bluetoothOut(context)) BLUETOOTH_MS else MS

    private fun bluetoothOut(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val bt = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        val routed = if (Build.VERSION.SDK_INT >= 33) runCatching { am.getAudioDevicesForAttributes(NAV_SPEECH) }.getOrNull() else null
        val outs = routed ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        outs.any { it.type in bt }
    }.getOrDefault(false)
}
