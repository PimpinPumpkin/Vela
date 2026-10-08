package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether place pages show reviews at all. OFF means the sheet renders no review
 * section and the app never fetches reviews for a selected place (no hidden
 * WebView scrape, no review traffic). Same process-wide reactive holder shape as
 * [LiveReviews] / [Traffic], persisted in vela_settings.
 */
object ShowReviews {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "show_reviews"
}

/**
 * Whether place pages load photos. OFF means no hero strip, no gallery, and no
 * photo fetch at all (the WebView gallery scrape is the heaviest per-place
 * request, so this is also the data-saver switch).
 */
object LoadPhotos {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "load_photos"
}

/**
 * Whether viewed places are cached for offline use. ON (default): every successful
 * online detail + review load is written to disk (tiny JSON), photos are prefetched
 * into Coil's disk cache, and opening a cached place with no network serves the
 * stored copy instead of an empty sheet. Text menus are not parsed anywhere yet,
 * so there is nothing of them to cache.
 */
object OfflinePlaces {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "offline_places"
}

/**
 * Whether to hide adult / nightlife categories (bars, clubs, casinos, liquor stores, adult, smoking,
 * gambling, …) from search results and the ambient map. OFF by default (everything shown); ON drops
 * those places at the data-source seam via [app.vela.core.data.CategoryFilter]. Matches on Google's
 * free-text CATEGORY only, never the name, so a place categorized "Restaurant" is always kept. Same
 * process-wide reactive holder shape as [ShowReviews] / [LoadPhotos], persisted in vela_settings.
 */
object HideAdult {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
        app.vela.core.data.CategoryFilter.enabled = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        app.vela.core.data.CategoryFilter.enabled = value // gate the :core data-source seam
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "hide_adult"
}

/**
 * Whether to hide links that launch arbitrary EXTERNAL web content from a place: the Website pill/row,
 * the Street View pano (opens Google externally), and the Book online / Reserve / Order online action.
 * OFF by default (everything shown); ON suppresses those so no place-detail control opens an arbitrary
 * site. Internal actions (dial, directions, share a `geo:` pin) are unaffected. Same process-wide
 * reactive holder shape as [ShowReviews] / [LoadPhotos], persisted in vela_settings.
 */
object HideExternalLinks {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "hide_external_links"
}

/** Reviews load only when the user taps "Show reviews" on a place (pref `reviews_on_tap`, off). */
object ReviewsOnTap {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "reviews_on_tap"
}

/** Routes ask Google for live traffic only after the user taps "Show traffic" (pref
 *  `route_traffic_on_tap`, off). Until then directions come from the open router alone. */
object RouteTrafficOnTap {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "route_traffic_on_tap"
}

/** Place photos load only when the user taps Show photos (pref `photos_on_tap`, off). */
object PhotosOnTap {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "photos_on_tap"
}

/** Retry a place's details while popular times are missing (pref `details_retry`, on). Off = one request. */
object DetailsRetry {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "details_retry"
}

/** Settings > Search "Find other locations automatically" (discussion #656), default ON (the
 *  owner's call: the other locations are what a name search is for). Off, a name search that
 *  lands on one place offers them on a tap instead of asking Google with every such search. Mirrored into `:core` [app.vela.core.data.OtherLocations]. */
object OtherLocationsAuto {
    val on = mutableStateOf(true)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
        app.vela.core.data.OtherLocations.auto = on.value
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        app.vela.core.data.OtherLocations.auto = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "other_locations_auto"
}

/** The place sheet's "Delete this drawing" action, set by the map's view model: the sheet is
 *  reached from MapScreen, which has no room for another callback (the verifier limit). */
/** The sheet's "Rename" for a saved place, set by the view model: a holder instead of one more
 *  PlaceSheet callback, since MapScreen cannot take another argument (the method size limit). */
object SavedActions {
    @Volatile var rename: ((place: app.vela.core.model.Place, name: String) -> Unit)? = null
}

/** The route pickers' "Avoid cameras" chip flips [FlockRouteAlert] itself and asks for the routes
 *  again through here; a holder so MapScreen takes no new callback (it is at the method limit). */
object RouteActions {
    @Volatile var camerasChanged: (() -> Unit)? = null
    /** "Add to home screen" in the route card's menu: pins the trip on screen as a shortcut. */
    @Volatile var pinTrip: ((label: String, iconKey: String, themed: Boolean) -> Unit)? = null
    /** The current trip's travel mode as a shortcut glyph key, the dialog's starting pick. */
    @Volatile var modeKey: (() -> String)? = null
}

/** The parking spot, for surfaces away from the map's own parking button: the arrival card
 *  offers to save where a drive ended. Set by the view model. */
object ParkingActions {
    /** Saves the current position as the parking spot. False with no fix. */
    @Volatile var saveHere: (() -> Boolean)? = null
    /** Was the trip that just ended a drive? Walking to a place parks nothing. */
    @Volatile var arrivedByCar: (() -> Boolean)? = null
    /** When the current spot was saved, in epoch milliseconds. 0 with none. */
    @Volatile var parkedAt: (() -> Long)? = null
}

object ShapeActions {
    @Volatile var delete: (() -> Unit)? = null
    @Volatile var edit: (() -> Unit)? = null
}

/** The results list's "Open now" chip tells the view model through here, so the search can be
 *  asked again for open places: a holder, since MapScreen cannot take another callback. */
object SearchActions {
    @Volatile var openNow: ((on: Boolean) -> Unit)? = null
}
