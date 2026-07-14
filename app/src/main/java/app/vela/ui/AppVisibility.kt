package app.vela.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Whether the app is on screen (MainActivity between onStart/onStop). Periodic work that only
 *  matters while the user is looking - the 30 s departure-board refresh - gates on this instead
 *  of polling from the background. Starts true so work launched before the first onStart isn't
 *  wrongly parked. */
object AppVisibility {
    val foreground = MutableStateFlow(true)
}
