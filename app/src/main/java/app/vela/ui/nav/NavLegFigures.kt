package app.vela.ui.nav

import androidx.compose.runtime.mutableStateOf
import app.vela.core.nav.NavSession

/**
 * The drive's figures to its next stop, beside the whole trip's, mirrored from
 * [NavSession.State.nextStop] by the nav controller. The bottom bar shows the stop's as its main
 * figures and names the stop; the step list's stops row shows the whole trip. Null with no stop
 * ahead, when both keep the whole trip as before.
 *
 * A holder rather than a parameter: the bar is called from MapScreen, which is at its size limits
 * and takes no new parameter.
 */
internal object NavLegFigures {
    data class Leg(val stop: NavSession.NextStop, val tripMeters: Double, val tripSeconds: Double)

    val leg = mutableStateOf<Leg?>(null)
}
