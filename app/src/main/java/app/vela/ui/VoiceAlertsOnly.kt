package app.vela.ui

import androidx.compose.runtime.mutableStateOf

/**
 * Whether the muted voice is in "Alerts only" (issue 735): muted for turns, with the speeding and
 * camera alerts as a chime. Set by `MapViewModel.setVoiceMode` beside the voice's own flags; read
 * by the drive's hold control, which draws a bell for it and gets no new parameter.
 */
object VoiceAlertsOnly {
    val on = mutableStateOf(false)
}
