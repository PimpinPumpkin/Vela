package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether rail lines are highlighted on the map, and which kinds of line and stop are shown.
 * Process-wide reactive holder like [Traffic] / [Units], flipped from Settings and persisted.
 *
 * The lines are OFF by default (2026-07-10): unlabeled purple lines over the map read as "what
 * is this?" rather than useful, the people who ride rail can flip it on in Settings > Map, and
 * anyone who already toggled it keeps their saved choice. Since 2026-10-02 (discussion #648) a
 * line wears its own color where the open transit data has it (each subway line its own), the
 * plain two-color highlight from the map tiles fills in elsewhere, and the kinds can be picked:
 * [metro] (subway, tram, light rail) and [trains] for the lines, [stopBus] / [stopMetro] /
 * [stopTrain] for the stop icons. All five default on, which is what the map drew before.
 */
object TransitLayer {
    val on = mutableStateOf(false)
    val metro = mutableStateOf(true)
    val trains = mutableStateOf(true)
    val stopBus = mutableStateOf(true)
    val stopMetro = mutableStateOf(true)
    val stopTrain = mutableStateOf(true)

    fun init(context: Context) {
        val p = prefs(context)
        on.value = p.getBoolean(KEY, false)
        metro.value = p.getBoolean(KEY_METRO, true)
        trains.value = p.getBoolean(KEY_TRAINS, true)
        stopBus.value = p.getBoolean(KEY_STOP_BUS, true)
        stopMetro.value = p.getBoolean(KEY_STOP_METRO, true)
        stopTrain.value = p.getBoolean(KEY_STOP_TRAIN, true)
    }

    fun set(context: Context, value: Boolean) = put(context, on, KEY, value)
    fun setMetro(context: Context, value: Boolean) = put(context, metro, KEY_METRO, value)
    fun setTrains(context: Context, value: Boolean) = put(context, trains, KEY_TRAINS, value)
    fun setStopBus(context: Context, value: Boolean) = put(context, stopBus, KEY_STOP_BUS, value)
    fun setStopMetro(context: Context, value: Boolean) = put(context, stopMetro, KEY_STOP_METRO, value)
    fun setStopTrain(context: Context, value: Boolean) = put(context, stopTrain, KEY_STOP_TRAIN, value)

    /** Does a stop served by these modes (the transit service's names) pass the stop filter?
     *  A stop with no known mode is shown: it was drawn before there was a filter. */
    fun showsStop(modes: List<String>): Boolean {
        val kinds = modes.mapNotNull { app.vela.core.data.transit.Transitous.kindOf(it) }
        if (kinds.isEmpty()) return true
        return kinds.any {
            when (it) {
                app.vela.core.data.transit.Transitous.Kind.BUS -> stopBus.value
                app.vela.core.data.transit.Transitous.Kind.METRO -> stopMetro.value
                app.vela.core.data.transit.Transitous.Kind.TRAIN -> stopTrain.value
            }
        }
    }

    private fun put(c: Context, state: androidx.compose.runtime.MutableState<Boolean>, key: String, value: Boolean) {
        state.value = value
        prefs(c).edit().putBoolean(key, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "transit_layer_on"
    private const val KEY_METRO = "transit_lines_metro"
    private const val KEY_TRAINS = "transit_lines_trains"
    private const val KEY_STOP_BUS = "transit_stops_bus"
    private const val KEY_STOP_METRO = "transit_stops_metro"
    private const val KEY_STOP_TRAIN = "transit_stops_train"
}
