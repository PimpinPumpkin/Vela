package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.LatLng

/** A map view: the point at the middle of the screen and the zoom. */
data class StartCamera(val center: LatLng, val zoom: Double) {
    fun encode(): String = "${center.lat},${center.lng},$zoom"

    companion object {
        /** A view from raw numbers, or null when they are not a place on the map. The longitude
         *  is wrapped: a world view panned past the date line reports one beyond 180. */
        fun of(lat: Double, lng: Double, zoom: Double): StartCamera? {
            if (lat.isNaN() || lng.isNaN() || zoom.isNaN() || lat < -90.0 || lat > 90.0 || lng.isInfinite()) return null
            return StartCamera(LatLng(lat, ((lng + 180.0).mod(360.0)) - 180.0), zoom.coerceIn(0.0, 22.0))
        }

        /** Reads [encode]'s text. */
        fun parse(s: String?): StartCamera? {
            val p = s?.split(",") ?: return null
            if (p.size != 3) return null
            return of(p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null, p[2].toDoubleOrNull() ?: return null)
        }
    }
}

/**
 * Where the map opens. A null [center] is the map's own world view and a null [zoom] the usual
 * street zoom. [hold] keeps the map there instead of following the phone's position until the
 * locate button is tapped, or, with [untilFix], until the first fix arrives.
 */
data class Start(val center: LatLng?, val zoom: Double? = null, val hold: Boolean = false, val untilFix: Boolean = false)

/**
 * The view the map starts on (Settings > Map, "Where the map opens"), for people who keep
 * location off and do not want the map to open on a stale position.
 *
 * - [HERE] (default): the phone's position. Without one it stands in with the view the map was
 *   left on and moves to the first fix.
 * - [LAST]: the view the map was left on.
 * - [HOME]: the saved Home.
 * - [PLACE]: a view the user picked.
 *
 * The choice and the picked view are settings (`vela_settings`, backed up). The view the map was
 * left on sits beside the last known position in `vela_location`, which no backup carries.
 */
object StartView {
    const val HERE = "here"
    const val LAST = "last"
    const val HOME = "home"
    const val PLACE = "place"

    /** Home opens at the street zoom a start on a fix uses. */
    const val HOME_ZOOM = 15.5

    val mode = mutableStateOf(HERE)

    /** The view picked for [PLACE]. */
    val place = mutableStateOf<StartCamera?>(null)

    /** The view the map is showing now, written by the map each time it settles. Memory only. */
    @Volatile var live: StartCamera? = null

    fun init(context: Context) {
        val p = settings(context)
        mode.value = p.getString(KEY_MODE, HERE) ?: HERE
        place.value = StartCamera.parse(p.getString(KEY_PLACE, null))
    }

    fun set(context: Context, value: String) {
        mode.value = value
        settings(context).edit().putString(KEY_MODE, value).apply()
    }

    /** Picks [PLACE] and makes the view on the map now the one it opens on. False when the map
     *  has not settled yet, and nothing changes. */
    fun useLiveView(context: Context): Boolean {
        val view = live ?: return false
        place.value = view
        mode.value = PLACE
        settings(context).edit().putString(KEY_PLACE, view.encode()).putString(KEY_MODE, PLACE).apply()
        return true
    }

    /** The view the map was left on, from an earlier run. */
    fun last(context: Context): StartCamera? = StartCamera.parse(location(context).getString(KEY_LAST, null))

    /** Keeps [live] for the next launch. Called when the app leaves the screen, not per pan. */
    fun saveLast(context: Context) {
        val text = live?.encode() ?: return
        val p = location(context)
        if (p.getString(KEY_LAST, null) != text) p.edit().putString(KEY_LAST, text).apply()
    }

    private fun settings(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private fun location(c: Context) = c.getSharedPreferences("vela_location", Context.MODE_PRIVATE)
    private const val KEY_MODE = "start_view"
    // "loc" in the name keeps the picked view out of SettingsDump's line.
    private const val KEY_PLACE = "start_view_loc"
    private const val KEY_LAST = "last_view"
}

/** The option the settings page shows as picked: a choice whose target is gone reads as "Where I
 *  am", which is what [startFor] does with it. */
fun shownStartMode(mode: String, homeSet: Boolean, placeSet: Boolean): String = when (mode) {
    StartView.LAST -> StartView.LAST
    StartView.HOME -> if (homeSet) StartView.HOME else StartView.HERE
    StartView.PLACE -> if (placeSet) StartView.PLACE else StartView.HERE
    else -> StartView.HERE
}

/**
 * Where the map opens for [mode]. Null when [taken] says the camera already belongs to something:
 * an intent that opened its own place or route (a link from another app, a shared place, a pinned
 * trip), or a drive that is running.
 *
 * [locationOn] is whether fixes can arrive (permission granted, a provider switched on) and
 * [fix] the last known position, which may be old. [last] is the view the map was left on,
 * [home] the saved Home and [place] the picked view.
 *
 * A choice whose target is missing (Home removed, nothing saved yet) falls back to "Where I am".
 * That opens on [fix] and follows it. With location off, or with no fix yet, it opens on [last]
 * and waits there for the first fix.
 */
fun startFor(
    mode: String,
    taken: Boolean,
    locationOn: Boolean,
    fix: LatLng?,
    last: StartCamera?,
    home: LatLng?,
    place: StartCamera?,
): Start? {
    if (taken) return null
    val picked = when (mode) {
        StartView.LAST -> last
        StartView.HOME -> home?.let { StartCamera(it, StartView.HOME_ZOOM) }
        StartView.PLACE -> place
        else -> null
    }
    if (picked != null) return Start(picked.center, picked.zoom, hold = true)
    val standIn = last?.let { Start(it.center, it.zoom, hold = true, untilFix = true) }
    return if (locationOn) fix?.let { Start(it) } ?: standIn ?: Start(null)
    else standIn ?: Start(fix)
}
