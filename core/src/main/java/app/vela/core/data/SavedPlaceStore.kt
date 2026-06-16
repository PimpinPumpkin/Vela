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

    private companion object {
        const val KEY = "places"
    }
}
