package app.vela.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Navigation "Show the drive on the lock screen": while a drive runs and is not paused,
 * Vela shows over the lock screen, so the power button wakes the phone to the route instead of the
 * PIN prompt. On by default, as in Organic Maps. Paused or ended, the lock screen covers Vela
 * again, and Settings asks for the unlock first ([unlockThen]): saved places and trips are there.
 *
 * "Wake the screen before each turn" ([wake], on by default, shown under [on]): with the screen
 * off during a drive, the screen lights as a turn comes near ([turnNear]) and goes dark at the
 * phone's own timeout, as OsmAnd's "Turn screen on" does. A screen lit that way shows the glance
 * screen ([glance], `nav/TurnGlance`) until a touch, a key or the power button brings back the
 * whole drive, as Apple Maps does. Not on foot (a phone in a pocket) or with a car screen up.
 */
object LockScreenNav {
    val on = mutableStateOf(true)
    val wake = mutableStateOf(true)
    /** The screen is lit by a turn wake and nobody has touched it: `nav/TurnGlance` shows. */
    val glance = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
        wake.value = prefs(context).getBoolean(WAKE_KEY, true)
    }

    fun setWake(context: Context, value: Boolean) {
        wake.value = value
        prefs(context).edit().putBoolean(WAKE_KEY, value).apply()
    }

    /** Whether a turn [toTurnM] ahead is near enough to light the screen: under [WAKE_LEAD_S] at
     *  the route's own pace (what is left over the time left), from [WAKE_MIN_M] to [WAKE_MAX_M]. */
    fun turnNear(toTurnM: Double, remainingM: Double, remainingS: Double): Boolean {
        val pace = if (remainingM > 0.0 && remainingS > 0.0) remainingM / remainingS else 0.0
        return toTurnM <= (pace * WAKE_LEAD_S).coerceIn(WAKE_MIN_M, WAKE_MAX_M)
    }

    private var lock: PowerManager.WakeLock? = null
    private var wokenStep = -1
    private var keepOnCleared = false
    private var wokeAtMs = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val release = Runnable { lock?.let { if (it.isHeld) it.release() }; lock = null }

    /**
     * The drive's step whose turn is near, or -1 ([MainActivity] collects it). The first time a
     * step comes near with the screen off, the screen lights; a near step while it is still lit
     * from a wake keeps it lit; -1 lets it go dark [WAKE_AFTER_MS] later, at the system's own
     * screen timeout. The window's keep-awake flag comes off for the wake, or the screen would
     * stay on for the rest of the drive, and goes back when the user touches the screen or
     * presses the power button ([userActive]).
     */
    fun turnStep(activity: Activity, step: Int) {
        if (step < 0) {
            wokenStep = -1
            main.removeCallbacks(release)
            if (lock?.isHeld == true) main.postDelayed(release, WAKE_AFTER_MS)
            return
        }
        if (step == wokenStep) return
        wokenStep = step
        if (!wake.value || !on.value || app.vela.car.CarBridge.live) return
        val pm = activity.getSystemService(PowerManager::class.java) ?: return
        val lit = lock?.isHeld == true
        if (pm.isInteractive && !lit) return
        val w = activity.window
        if (w.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepOnCleared = true
        }
        main.removeCallbacks(release)
        lock?.let { if (it.isHeld) it.release() }
        if (!lit) {
            wokeAtMs = SystemClock.elapsedRealtime()
            glance.value = true
        }
        @Suppress("DEPRECATION")
        lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "vela:turn")
            .apply { setReferenceCounted(false); acquire(WAKE_MAX_MS) }
    }

    /** A touch, a key, or the screen turned on by someone other than [turnStep]: the glance gives
     *  way to the whole drive, and the drive's keep-awake flag goes back while [driving]. */
    fun userActive(activity: Activity, driving: Boolean, screenOn: Boolean = false) {
        if (screenOn && SystemClock.elapsedRealtime() - wokeAtMs < OWN_WAKE_MS) return
        glance.value = false
        if (!keepOnCleared) return
        keepOnCleared = false
        if (driving) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** A tap on the glance screen. [userActive] runs for the same touch and does the rest. */
    fun endGlance() { glance.value = false }

    /** The drive ended or paused: no flag to give back, and nothing left lit. */
    fun driveStopped() {
        glance.value = false
        keepOnCleared = false
        wokenStep = -1
        main.removeCallbacks(release)
        release.run()
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    /** Show [activity] over the lock screen, or stop. */
    fun show(activity: Activity, show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) activity.setShowWhenLocked(show)
        else if (show) activity.window.addFlags(@Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else activity.window.clearFlags(@Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    /** Run [action] now, or once the user unlocks when Vela is showing over a locked phone. */
    fun unlockThen(context: Context, action: () -> Unit) {
        val activity = context.findActivity()
        val km = activity?.getSystemService(KeyguardManager::class.java)
        if (activity == null || km == null || !km.isKeyguardLocked) { action(); return }
        km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        return c as? Activity
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "nav_on_lock_screen"
    private const val WAKE_KEY = "nav_wake_for_turns"

    /** The screen lights this long before a turn at the route's pace (a city turn about 300 m
     *  out, a freeway exit about 750 m), within [WAKE_MIN_M] and [WAKE_MAX_M]. */
    const val WAKE_LEAD_S = 25.0
    const val WAKE_MIN_M = 200.0
    const val WAKE_MAX_M = 1000.0
    /** It stays lit this long after the turn is passed, then the system timeout darkens it. */
    const val WAKE_AFTER_MS = 4_000L
    /** Never held longer than this, whatever the drive does. */
    const val WAKE_MAX_MS = 120_000L
    /** A screen-on this soon after a wake was the wake itself. */
    private const val OWN_WAKE_MS = 2_000L
}
