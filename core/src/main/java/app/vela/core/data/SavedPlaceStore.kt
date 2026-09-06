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

    // ignoreUnknownKeys: a newer build's extra field must not fail the decode here,
    // or the getOrDefault(empty) wipes the data on the next write (see PlaceListStore).
    private val json = Json { ignoreUnknownKeys = true }

    fun saved(): List<SavedPlace> =
        runCatching { json.decodeFromString<List<SavedPlace>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    /** Toggle [place]; returns true if it is now saved. */
    fun toggle(place: SavedPlace): Boolean {
        val current = saved()
        val exists = current.any { it.id == place.id }
        val updated = if (exists) current.filterNot { it.id == place.id } else listOf(place) + current
        prefs.edit().putString(KEY, json.encodeToString(updated)).apply()
        return !exists
    }

    fun isSaved(id: String): Boolean = saved().any { it.id == id }

    /** The saved list as a portable JSON document (for export / backup). */
    fun exportJson(): String = json.encodeToString(saved())

    /** Merge a previously-exported [json] list into the saved set, de-duped by id
     *  (existing entries kept, new ones appended). Returns how many were newly added;
     *  0 on a parse failure or nothing new. */
    /**
     * Merge saved places from an exported file.
     *
     * Reports WHICH outcome happened (issue #287): a file from another app and a re-imported
     * backup both used to come back as 0, and the app could only say "nothing to import", which
     * sent people looking for a bug in the wrong place.
     */
    fun importMerge(json: String): ImportResult {
        // Vela's own export first; failing that, the formats other map apps produce (issue #279),
        // so someone arriving from Organic Maps / OsmAnd / CoMaps / Takeout keeps their pins
        // instead of only being told the file is the wrong kind.
        val incoming = runCatching { this.json.decodeFromString<List<SavedPlace>>(json) }.getOrNull()
            ?: PlaceImport.parse(json).ifEmpty { null }
            ?: return ImportResult.WrongFormat(ImportFormats.describe(json))
        val current = saved()
        val existing = current.mapTo(HashSet()) { it.id }
        // Two placemarks at one rounded coordinate share an id. Keep the first of them, or every
        // id-keyed action afterwards (unsave, list membership) hits both at once.
        val added = incoming.distinctBy { it.id }.filterNot { it.id in existing }
        if (added.isEmpty()) return ImportResult.NothingNew
        prefs.edit().putString(KEY, this.json.encodeToString(current + added)).apply()
        return ImportResult.Added(added.size)
    }

    private companion object {
        const val KEY = "places"
    }
}
