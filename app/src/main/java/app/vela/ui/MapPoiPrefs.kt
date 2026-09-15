package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Map-POI visibility + sizing preferences (user 2026-07-15). Same reactive-object pattern as
 * [Buildings3d]: Compose state the map layer reads live, persisted in vela_settings.
 *
 * - [showPois]: master switch for the ambient place layer AND the OSM fallback business POIs.
 *   Off = a clean basemap; searched results, transit stops and traffic controls still show
 *   (they have their own switches/behaviour).
 * - [showTransit]: the canonical GTFS stop icons (and their per-viewport fetch).
 * - [showCivic]: parks, schools and civic places inside the ambient pool - the "not really a
 *   business" tier; off = the ambient layer shows businesses only.
 * - [iconScale]: multiplies the POI icon/label sizes on the map. Exists for low-density screens
 *   (a 1024x600 car head unit renders the fixed-px bitmaps physically huge); phones stay at 1.0.
 */
object MapPoiPrefs {
    val showPois = mutableStateOf(true)
    val showTransit = mutableStateOf(true)
    val showCivic = mutableStateOf(true)
    val iconScale = mutableFloatStateOf(1.0f)
    /** Where the businesses on the browse map come from, one of [SOURCE_OPEN] (the Overture places
     *  layer where a region file covers the view, Google only on tap, works offline), [SOURCE_GOOGLE]
     *  (the ambient fan-out on every pan, nothing offline) or [SOURCE_BOTH] (the open layer draws the
     *  map and one Google fetch per settled view fills in what it lacks). Outside any region file all
     *  three behave like Google. The user picks; each option's costs are stated in Settings. */
    val placesSource = mutableStateOf(SOURCE_OPEN)
    /** The open places layer is on the map (open data or both). */
    val openPlaces: Boolean get() = placesSource.value != SOURCE_GOOGLE
    /** The open places layer alone owns the map's businesses where it covers the view. */
    val openPlacesOnly: Boolean get() = placesSource.value == SOURCE_OPEN
    /** A region download also pulls the Vela places archive covering it (on by default), so the
     *  map's businesses draw offline. Off keeps places streaming-only, which is free when online. */
    val placesWithDownloads = mutableStateOf(true)

    fun init(context: Context) {
        val p = prefs(context)
        showPois.value = p.getBoolean(KEY_POIS, true)
        showTransit.value = p.getBoolean(KEY_TRANSIT, true)
        showCivic.value = p.getBoolean(KEY_CIVIC, true)
        iconScale.floatValue = p.getFloat(KEY_SCALE, 1.0f)
        placesSource.value = p.getString(KEY_PLACES_SOURCE, null) ?: SOURCE_OPEN
        placesWithDownloads.value = p.getBoolean(KEY_PLACES_WITH_DOWNLOADS, true)
    }

    fun setPlacesWithDownloads(context: Context, value: Boolean) {
        placesWithDownloads.value = value
        prefs(context).edit().putBoolean(KEY_PLACES_WITH_DOWNLOADS, value).apply()
    }

    fun setPlacesSource(context: Context, value: String) {
        placesSource.value = value
        prefs(context).edit().putString(KEY_PLACES_SOURCE, value).apply()
    }

    fun setShowPois(context: Context, value: Boolean) {
        showPois.value = value
        prefs(context).edit().putBoolean(KEY_POIS, value).apply()
    }

    fun setShowTransit(context: Context, value: Boolean) {
        showTransit.value = value
        prefs(context).edit().putBoolean(KEY_TRANSIT, value).apply()
    }

    fun setShowCivic(context: Context, value: Boolean) {
        showCivic.value = value
        prefs(context).edit().putBoolean(KEY_CIVIC, value).apply()
    }

    fun setIconScale(context: Context, value: Float) {
        iconScale.floatValue = value
        prefs(context).edit().putFloat(KEY_SCALE, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_POIS = "map_show_pois"
    private const val KEY_TRANSIT = "map_show_transit_stops"
    private const val KEY_CIVIC = "map_show_civic_pois"
    private const val KEY_SCALE = "map_poi_icon_scale"
    private const val KEY_PLACES_SOURCE = "map_places_source"
    private const val KEY_PLACES_WITH_DOWNLOADS = "offline_places_with_downloads"
    const val SOURCE_OPEN = "open"
    const val SOURCE_GOOGLE = "google"
    const val SOURCE_BOTH = "both"
}
