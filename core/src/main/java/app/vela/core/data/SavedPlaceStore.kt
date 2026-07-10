package app.vela.core.data

import android.content.Context
import app.vela.core.model.SavedPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted favourite places (most-recently-saved first). */
@Singleton
class SavedPlaceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_saved", Context.MODE_PRIVATE)

    fun saved(): List<SavedPlace> =
        runCatching { Json.decodeFromString<List<SavedPlace>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    /** Toggle [place]; returns true if it is now saved. */
    fun toggle(place: SavedPlace): Boolean {
        val current = saved()
        val exists = current.any { it.id == place.id }
        val updated = if (exists) current.filterNot { it.id == place.id } else listOf(place) + current
        prefs.edit().putString(KEY, Json.encodeToString(updated)).apply()
        return !exists
    }

    fun isSaved(id: String): Boolean = saved().any { it.id == id }

    /** The saved list as a portable JSON document (for export / backup). */
    fun exportJson(): String = Json.encodeToString(saved())

    /** Merge a previously-exported [json] list into the saved set, de-duped by id
     * (existing entries kept, new ones appended). Returns how many were newly added;
     * 0 on a parse failure or nothing new. */
    fun importMerge(json: String): Int {
        val incoming = runCatching { Json.decodeFromString<List<SavedPlace>>(json) }.getOrNull() ?: return 0
        val current = saved()
        val existing = current.mapTo(HashSet()) { it.id }
        val added = incoming.filterNot { it.id in existing }
        if (added.isEmpty()) return 0
        prefs.edit().putString(KEY, Json.encodeToString(current + added)).apply()
        return added.size
    }

    private companion object {
        const val KEY = "places"
    }
}
