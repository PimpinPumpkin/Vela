package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether transit directions also look for bike-share trips (SPEC 8, Bike share in directions):
 * the "Bike share" chip among the transit options. Same reactive-holder shape as
 * [BikeShareLayer]. OFF by default: it sends each transit trip's two ends to Transitous, even
 * when Google answers the transit search, so a rider opts in. The trips are looked for by
 * [app.vela.ui.map.BikeShareTripSearch].
 */
object BikeShareTrips {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "transit_bike_share_on"
}
