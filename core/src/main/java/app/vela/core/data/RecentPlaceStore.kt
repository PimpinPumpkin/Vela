package app.vela.core.data

import android.content.Context
import app.vela.core.model.SavedPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One recently-opened place. [at] = epoch ms it was last opened, so the search page
 *  can interleave places with recent search queries in ONE chronological list
 *  (Google mixes them; the icon tells them apart). */
@Serializable
data class RecentPlace(val place: SavedPlace, val at: Long = 0)

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

    fun recent(): List<RecentPlace> {
        prefs.getString(KEY2, null)?.let { raw ->
            runCatching { json.decodeFromString<List<RecentPlace>>(raw) }.getOrNull()?.let { return it }
        }
        return migrateLegacy()
    }

    /** Record [place] as most-recent (moving it up if already present). */
    fun add(place: SavedPlace) {
        if (place.name.isBlank()) return
        val updated = (
            listOf(RecentPlace(place, System.currentTimeMillis())) +
                recent().filterNot { it.place.id == place.id }
            ).take(CAP)
        prefs.edit().putString(KEY2, json.encodeToString(updated)).apply()
    }

    /** Remove one place (the X on its row). */
    fun remove(placeId: String) =
        prefs.edit().putString(KEY2, json.encodeToString(recent().filterNot { it.place.id == placeId })).apply()

    fun clear() = prefs.edit().remove(KEY).remove(KEY2).apply()

    /** Pre-timestamp data was a bare SavedPlace list under [KEY]. Read it once, synthesize
     *  descending timestamps (a minute apart, anchored now) so old entries keep their
     *  order in the merged list, and persist under [KEY2]. [KEY] itself is left alone
     *  so a DOWNGRADED build still finds its old-format data instead of wiping it. */
    private fun migrateLegacy(): List<RecentPlace> {
        val legacy = runCatching { json.decodeFromString<List<SavedPlace>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())
        if (legacy.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val migrated = legacy.mapIndexed { i, p -> RecentPlace(p, now - i * 60_000L) }
        prefs.edit().putString(KEY2, json.encodeToString(migrated)).apply()
        return migrated
    }

    private companion object {
        const val KEY = "places" // legacy bare SavedPlaces (kept in place for downgrades)
        const val KEY2 = "places2" // timestamped entries
        const val CAP = 8
    }
}
