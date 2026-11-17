package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Legacy holder for spoken police warning, synced with [WazeAlertsPrefs].
 */
object WazePoliceWarn {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        // Handled in WazeAlertsPrefs.init()
    }

    fun set(context: Context, value: Boolean) {
        WazeAlertsPrefs.setPoliceWarn(context, value)
    }
}
