package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Legacy holder for police reports toggle, synced with [WazeAlertsPrefs].
 */
object WazePolice {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        WazeAlertsPrefs.init(context)
    }

    fun set(context: Context, value: Boolean) {
        if (value && !WazeAlertsPrefs.masterOn.value) {
            WazeAlertsPrefs.setMaster(context, true)
        }
        WazeAlertsPrefs.setPolice(context, value)
    }
}
