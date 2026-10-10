package app.vela.core.data

import android.content.Context
import app.vela.core.model.LabelPlace
import app.vela.core.model.LatLng
import app.vela.core.model.ListPlace
import app.vela.core.model.Place
import app.vela.core.model.PlaceList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which stored list saving an import refreshes, and the id a new list gets when it refreshes none.
 * The same import saved again is found by the id its source gives ([sourceId]: the custom map's
 * id, the list's link), or, for a list saved before imports carried a source, by the title's id
 * while it still has that title. A list that only shares the name is the user's own, or another
 * map with a default title such as "Untitled map", and is never the target.
 */
fun importTarget(all: List<PlaceList>, title: String, sourceId: String?): Pair<PlaceList?, String> {
    val titleId = "list:import:" + title.hashCode().toString(16)
    val fromSource = sourceId?.let { "list:import:" + it.hashCode().toString(16) }
    val existing = all.firstOrNull { it.id == fromSource } ?: all.firstOrNull { it.id == titleId && it.name == title }
    if (existing != null) return existing to existing.id
    val base = fromSource ?: titleId
    var id = base
    var n = 2
    while (all.any { it.id == id }) id = "$base:${n++}"
    return null to id
}

/**
 * [all] with [listing] written onto every entry kept from the map label [heldId] at [at] that
 * still has no listing ([ListPlace.awaitsListing]). A list that already holds the listing under
 * its own id keeps its entries as they are, so it never holds one place twice. Returns [all]
 * itself when nothing changes.
 */
fun linkListing(all: List<PlaceList>, heldId: String, at: LatLng, listing: Place): List<PlaceList> {
    if (listing.featureId.isNullOrBlank()) return all
    fun held(p: ListPlace) = p.awaitsListing && LabelPlace.same(p.id, p.location, heldId, at)
    var changed = false
    val out = all.map { l ->
        if (l.places.none { held(it) } || l.places.any { it.matches(listing.id, listing.featureId) }) l
        else { changed = true; l.copy(places = l.places.map { if (held(it)) it.linked(listing) else it }) }
    }
    return if (changed) out else all
}

/** Persisted user place-lists (issue #1). Newest-first; all mutations return the fresh list. */
@Singleton
class PlaceListStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_lists", Context.MODE_PRIVATE)

    // ignoreUnknownKeys: a field added by a newer build must not make an older build's
    // decode throw - the getOrDefault(empty) below would then WIPE the data on next write.
    private val json = Json { ignoreUnknownKeys = true }

    // The decoded lists for the stored string they came from. A saved custom map can make the
    // string megabytes, and the search page reads the lists on every keystroke.
    @Volatile private var decoded: Pair<String, List<PlaceList>>? = null

    fun lists(): List<PlaceList> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        decoded?.let { (from, lists) -> if (from == raw) return lists }
        return runCatching { json.decodeFromString<List<PlaceList>>(raw) }
            .onSuccess { decoded = raw to it }
            .getOrDefault(emptyList())
    }

    private fun write(lists: List<PlaceList>): List<PlaceList> {
        val raw = json.encodeToString(lists)
        prefs.edit().putString(KEY, raw).apply()
        decoded = raw to lists
        return lists
    }

    /** Creates a list (id is caller-supplied so the UI can select it immediately). */
    fun create(list: PlaceList): List<PlaceList> = write(listOf(list) + lists())

    /** Replaces the list with the same id (rename / icon / color / description edits). */
    fun update(list: PlaceList): List<PlaceList> =
        write(lists().map { if (it.id == list.id) list else it })

    fun delete(listId: String): List<PlaceList> = write(lists().filterNot { it.id == listId })

    /** Moves [listId] by [delta] positions (negative = up). The stored array order IS the
     *  display order everywhere (Your lists dialog, the search page, the map pins), so a
     *  custom order is just a persisted swap (issue #343). No-op at the ends. */
    fun move(listId: String, delta: Int): List<PlaceList> {
        val cur = lists().toMutableList()
        val i = cur.indexOfFirst { it.id == listId }
        if (i < 0) return cur
        val j = (i + delta).coerceIn(0, cur.size - 1)
        if (j == i) return cur
        val item = cur.removeAt(i)
        cur.add(j, item)
        return write(cur)
    }

    /** Adds [place] to [listId] (idempotent via [ListPlace.matches] — the same chain store
     *  re-resolved under a fresh volatile id must not become a duplicate entry). */
    fun addPlace(listId: String, place: ListPlace): List<PlaceList> = write(
        lists().map { l ->
            if (l.id != listId || l.places.any { it.matches(place.id, place.featureId) }) l
            else l.copy(places = l.places + place)
        },
    )

    /** Removes several places from a list in one write. */
    fun removePlaces(listId: String, placeIds: Set<String>): List<PlaceList> = write(
        lists().map { l -> if (l.id != listId) l else l.copy(places = l.places.filterNot { it.id in placeIds }) },
    )

    /** Adds several places to a list in one write. One the list already holds is not doubled. */
    fun addPlaces(listId: String, places: List<ListPlace>): List<PlaceList> = write(
        lists().map { l ->
            if (l.id != listId) l
            else l.copy(places = l.places + places.filter { p -> l.places.none { it.matches(p.id, p.featureId) } })
        },
    )

    /** Moves several places from one list to another in one write. Nothing happens when the
     *  target is missing, so a place is never removed without landing somewhere. */
    fun movePlaces(fromId: String, placeIds: Set<String>, toId: String): List<PlaceList> {
        val cur = lists()
        val moving = cur.firstOrNull { it.id == fromId }?.places?.filter { it.id in placeIds }.orEmpty()
        if (moving.isEmpty() || fromId == toId || cur.none { it.id == toId }) return cur
        return write(
            cur.map { l ->
                when (l.id) {
                    fromId -> l.copy(places = l.places.filterNot { it.id in placeIds })
                    toId -> l.copy(places = l.places + moving.filter { m -> l.places.none { it.matches(m.id, m.featureId) } })
                    else -> l
                }
            },
        )
    }

    fun removePlace(listId: String, placeId: String, featureId: String? = null): List<PlaceList> = write(
        lists().map { l -> if (l.id != listId) l else l.copy(places = l.places.filterNot { it.matches(placeId, featureId) }) },
    )

    /** Sets (or clears with null) the note on a place across every list it appears in.
     *  Matching by feature id too, not just the volatile place id — a note written on a
     *  re-resolved chain listing (fresh id, same feature id) used to match nothing and
     *  silently vanish (the Safeway bug). */
    fun setNote(placeId: String, note: String?, featureId: String? = null): List<PlaceList> = write(
        lists().map { l ->
            l.copy(places = l.places.map { if (it.matches(placeId, featureId)) it.copy(note = note?.ifBlank { null }) else it })
        },
    )

    /** Sets/clears a place's own map icon in every list holding it (issue #629). */
    fun setIcon(placeId: String, icon: String?, featureId: String? = null): List<PlaceList> = write(
        lists().map { l ->
            l.copy(places = l.places.map { if (it.matches(placeId, featureId)) it.copy(icon = icon?.ifBlank { null }) else it })
        },
    )

    /** The place's entry in every list, under [name]: a list entry can be renamed like a saved
     *  place (issue 736). Returns the lists; nothing is written when no list holds the place. */
    fun rename(placeId: String, name: String, featureId: String? = null): List<PlaceList> {
        val trimmed = name.trim()
        val lists = lists()
        if (trimmed.isEmpty() || lists.none { l -> l.places.any { it.matches(placeId, featureId) } }) return lists
        return write(lists.map { l -> l.copy(places = l.places.map { if (it.matches(placeId, featureId)) it.copy(name = trimmed) else it }) })
    }

    /** Writes [listing] onto the entries kept from the label [heldId] at [at] ([linkListing]).
     *  Nothing is written when no list holds one: a saved custom map makes the write megabytes. */
    fun link(heldId: String, at: LatLng, listing: Place): List<PlaceList> {
        val cur = lists()
        val linked = linkListing(cur, heldId, at, listing)
        return if (linked === cur) cur else write(linked)
    }

    /** The lists holding this place (drives the sheet's "in a list" affordances). */
    fun listsContaining(placeId: String, featureId: String? = null): List<PlaceList> =
        lists().filter { l -> l.places.any { it.matches(placeId, featureId) } }

    /** All lists as a portable JSON document (export / backup). */
    fun exportJson(): String = json.encodeToString(lists())

    /** Merge exported [json] lists in, de-duped by list id (existing lists keep their
     *  places; a brand-new list is appended whole). Returns how many lists were added. */
    /** Merge lists from an exported file; reports which outcome happened (issue #287, see
     *  [ImportResult] - "not our format" and "nothing new" are different answers). */
    fun importMerge(json: String): ImportResult {
        val incoming = runCatching { this.json.decodeFromString<List<PlaceList>>(json) }.getOrNull()
            ?: return ImportResult.WrongFormat(ImportFormats.describe(json))
        val current = lists()
        val existingIds = current.mapTo(HashSet()) { it.id }
        val added = incoming.filterNot { it.id in existingIds }
        if (added.isEmpty()) return ImportResult.NothingNew
        write(current + added)
        return ImportResult.Added(added.size)
    }

    private companion object {
        const val KEY = "lists"
    }
}
