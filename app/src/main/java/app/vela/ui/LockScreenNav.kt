package app.vela.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Navigation "Show the drive on the lock screen": while a drive runs and is not paused,
 * Vela shows over the lock screen, so the power button wakes the phone to the route instead of the
 * PIN prompt. On by default, as in Organic Maps. Paused or ended, the lock screen covers Vela
 * again, and Settings asks for the unlock first ([unlockThen]): saved places and trips are there.
 */
object LockScreenNav {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
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
}
