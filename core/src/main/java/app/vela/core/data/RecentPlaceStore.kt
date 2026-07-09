package app.vela.core.data

import android.content.Context
import app.vela.core.model.SavedPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Recently-opened places (most-recent first, deduped by id, capped) — so the
 *  search page can offer one-tap return to a place you just looked at. */
@Singleton
class RecentPlaceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_recent_places", Context.MODE_PRIVATE)

    // ignoreUnknownKeys: a newer build's extra field must not fail the decode here,
    // or the getOrDefault(empty) wipes the data on the next write (see PlaceListStore).
    private val json = Json { ignoreUnknownKeys = true }

    fun recent(): List<SavedPlace> =
        runCatching { json.decodeFromString<List<SavedPlace>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    /** Record [place] as most-recent (moving it up if already present). */
    fun add(place: SavedPlace) {
        if (place.name.isBlank()) return
        val updated = (listOf(place) + recent().filterNot { it.id == place.id }).take(CAP)
        prefs.edit().putString(KEY, json.encodeToString(updated)).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "places"
        const val CAP = 8
    }
}
