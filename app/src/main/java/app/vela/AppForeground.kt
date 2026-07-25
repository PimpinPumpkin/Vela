package app.vela

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Is any Vela activity visible right now? Counted from started/stopped lifecycle callbacks
 * (started, not resumed: a dialog over the activity still counts as "the user is looking at
 * Vela"). The nav service reads this to decide whether a spoken turn also needs a visible
 * heads-up notification - in the foreground the banner card already shows the turn.
 */
object AppForeground {
    @Volatile var visible: Boolean = false
        private set

    private var started = 0

    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                visible = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                visible = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
