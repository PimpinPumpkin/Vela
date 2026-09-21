package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** "Try side streets around cameras" (issue #600): with the camera re-rank on, the chooser also
 *  tries a point to either side of the road at each camera cluster and keeps a route through it
 *  when it passes fewer cameras inside the same detour limit. Off by default: it multiplies route
 *  requests to a fair-use router. Nested under [FlockRouteAlert] in Settings. */
object FlockDetour {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "flock_detour"
}
