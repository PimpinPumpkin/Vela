package app.vela.ui

import androidx.compose.runtime.mutableStateOf

/**
 * Is the activity currently in picture-in-picture? Set by MainActivity's
 * onPictureInPictureModeChanged; MapScreen reads it to strip ALL chrome down to the bare map plus
 * a one-line turn strip - the PiP window is a few hundred dp across, and full-size chrome would
 * fill it wall to wall. Same process-wide reactive holder shape as AppTheme/Units.
 */
object PipMode {
    val active = mutableStateOf(false)
}
