package app.vela.core.data

import android.content.Context
import app.vela.core.model.ListPlace
import app.vela.core.model.PlaceList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted user place-lists (issue #1). Newest-first; all mutations return the fresh list. */
@Singleton
class PlaceListStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_lists", Context.MODE_PRIVATE)

    fun lists(): List<PlaceList> =
        runCatching { Json.decodeFromString<List<PlaceList>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    private fun write(lists: List<PlaceList>): List<PlaceList> {
        prefs.edit().putString(KEY, Json.encodeToString(lists)).apply()
        return lists
    }

    /** Creates a list (id is caller-supplied so the UI can select it immediately). */
    fun create(list: PlaceList): List<PlaceList> = write(listOf(list) + lists())

    /** Replaces the list with the same id (rename / icon / colour / description edits). */
    fun update(list: PlaceList): List<PlaceList> =
        write(lists().map { if (it.id == list.id) list else it })

    fun delete(listId: String): List<PlaceList> = write(lists().filterNot { it.id == listId })

    /** Adds [place] to [listId] (idempotent by place id; keeps the existing note if re-added). */
    fun addPlace(listId: String, place: ListPlace): List<PlaceList> = write(
        lists().map { l ->
            if (l.id != listId || l.places.any { it.id == place.id }) l
            else l.copy(places = l.places + place)
        },
    )

    fun removePlace(listId: String, placeId: String): List<PlaceList> = write(
        lists().map { l -> if (l.id != listId) l else l.copy(places = l.places.filterNot { it.id == placeId }) },
    )

    /** Sets (or clears with null) the note on a place across every list it appears in. */
    fun setNote(placeId: String, note: String?): List<PlaceList> = write(
        lists().map { l ->
            l.copy(places = l.places.map { if (it.id == placeId) it.copy(note = note?.ifBlank { null }) else it })
        },
    )

    /** True if [placeId] is in any list (drives the sheet's "in a list" affordances). */
    fun listsContaining(placeId: String): List<PlaceList> =
        lists().filter { l -> l.places.any { it.id == placeId } }

    /** All lists as a portable JSON document (export / backup). */
    fun exportJson(): String = Json.encodeToString(lists())

    /** Merge exported [json] lists in, de-duped by list id (existing lists keep their
     *  places; a brand-new list is appended whole). Returns how many lists were added. */
    fun importMerge(json: String): Int {
        val incoming = runCatching { Json.decodeFromString<List<PlaceList>>(json) }.getOrNull() ?: return 0
        val current = lists()
        val existingIds = current.mapTo(HashSet()) { it.id }
        val added = incoming.filterNot { it.id in existingIds }
        if (added.isEmpty()) return 0
        write(current + added)
        return added.size
    }

    private companion object {
        const val KEY = "lists"
    }
}
