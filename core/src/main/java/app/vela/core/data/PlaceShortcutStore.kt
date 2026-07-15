package app.vela.core.data

import android.content.Context
import app.vela.core.model.SavedPlace
import app.vela.core.model.ShortcutKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted Home / Work shortcuts (each an optional [SavedPlace]). */
@Singleton
class PlaceShortcutStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_shortcuts", Context.MODE_PRIVATE)

    fun get(kind: ShortcutKind): SavedPlace? =
        prefs.getString(kind.name, null)?.let {
            runCatching { Json.decodeFromString<SavedPlace>(it) }.getOrNull()
        }

    /** Set (or clear, with null) the place for [kind]. */
    fun set(kind: ShortcutKind, place: SavedPlace?) {
        prefs.edit().apply {
            if (place == null) remove(kind.name) else putString(kind.name, Json.encodeToString(place))
        }.apply()
    }
}
