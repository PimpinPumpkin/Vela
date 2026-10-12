package app.vela.ui.settings

import java.util.Locale

/** Navigation matches routes case-insensitively: the map and its settings need separate paths. */
internal const val MAP_ROUTE = "main/map"

/** HUB is the category list; each other destination is one Settings page. QUICK_CHIPS is the
 *  one page under another (Search), so it has no hub row and Back returns to Search. */
internal enum class SettingsSection {
    HUB, APPEARANCE, MAP, PLACES, NAVIGATION, VOICE, SEARCH, OFFLINE, SAVED_PLACES,
    PRIVACY, PERFORMANCE, DIAGNOSTICS, ABOUT, QUICK_CHIPS;

    val route: String get() = "settings/${name.lowercase(Locale.ROOT)}"
}
