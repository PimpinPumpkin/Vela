package app.vela.vehicle

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.vela.core.nav.NavSession
import app.vela.core.vehicle.WheelAction
import app.vela.core.vehicle.WheelKeyRouter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reads the steering wheel's voice button during a drive and uses it to repeat the current spoken
 * instruction (SPEC 10.9). A BYD head unit delivers wheel buttons to an app through its own
 * accessibility key filter before the stock handler, so this is a filtering AccessibilityService.
 * The user enables it in Android Settings; until then every other Vela feature works and no key is
 * touched. Only the voice keys are taken, and only while Vela is guiding; every other key, and
 * every voice key outside a drive, passes to the car untouched (the policy is [WheelKeyRouter]).
 *
 * The service holds no window content: `canRetrieveWindowContent` is false and the only event type
 * is window-state-changed, which the platform requires to deliver key events at all. It reads
 * nothing about what is on screen.
 */
@AndroidEntryPoint
class SteeringWheelService : AccessibilityService() {
    @Inject lateinit var navSession: NavSession

    private val router = WheelKeyRouter()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The manifest config sets the filter flag; OR it in again in case a framework reset dropped it.
        serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        Log.i(TAG, "steering wheel key filter connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val s = navSession.state.value
        val guiding = s.navigating && !s.paused && !s.arrived
        val decision = router.onKey(
            event.keyCode, down = event.action == KeyEvent.ACTION_DOWN, nowMs = SystemClock.uptimeMillis(), guiding = guiding,
        )
        if (decision.action == WheelAction.REPEAT_INSTRUCTION) {
            runCatching { navSession.repeatInstruction() }.onFailure { Log.w(TAG, "repeat failed", it) }
        }
        return decision.consume
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "VelaWheel"

        /** Whether the user has enabled the key filter in Android Settings. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, SteeringWheelService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            while (splitter.hasNext()) {
                val c = ComponentName.unflattenFromString(splitter.next()) ?: continue
                if (c == me) return true
            }
            return false
        }
    }
}
