package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.Route

/**
 * The arrows drawn on the route at its turns while navigating (issue #804): whether they are
 * wanted (Settings > Navigation, on by default) and which route and step they are for.
 * `NavController` writes [update] per nav tick, change-gated on the route and the step;
 * `VelaMapView` reads [input] directly and cuts the arrows from the line it draws
 * (`TurnArrowGeometry.arrows`), so `MapScreen` carries no new parameter.
 */
object TurnArrows {
    val on = mutableStateOf(true)

    /** The route and the step in hand the arrows are for. */
    class Input(val route: Route, val step: Int)

    /** Null outside a drive or with the switch off. */
    val input = mutableStateOf<Input?>(null)

    private var route: Route? = null
    private var step = -1

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
        route = null // recomputed on the next tick
    }

    fun update(route: Route?, stepIndex: Int) {
        if (route === this.route && stepIndex == step) return
        this.route = route
        step = stepIndex
        input.value = if (route == null || !on.value) null else Input(route, stepIndex)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "turn_arrows_on"
}
