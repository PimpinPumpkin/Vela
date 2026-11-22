package app.vela.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The Activity on top, if any, for code that needs a window-bearing context from outside the UI
 * tree: the cluster-map [android.app.Presentation] is hosted from it, because a Presentation
 * created from an Activity keeps rendering when that Activity is merely stopped, while one built
 * from the application context can be refused a window token. Registered once in VelaApp. A weak
 * reference, so it never holds a destroyed Activity.
 */
object TopActivity {
    private var ref = WeakReference<Activity>(null)

    val current: Activity? get() = ref.get()

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { ref = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (ref.get() === a) ref.clear() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (ref.get() === a) ref.clear() }
        })
    }
}
