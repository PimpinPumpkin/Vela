package app.vela.core.data

import android.content.Context
import app.vela.core.model.SavedRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The user's saved routes (issue #622), newest first. */
@Singleton
class SavedRouteStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_saved_routes", Context.MODE_PRIVATE)

    // ignoreUnknownKeys: a newer build's extra field must not fail the decode and wipe the list.
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<SavedRoute> =
        runCatching { json.decodeFromString<List<SavedRoute>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    fun add(route: SavedRoute): List<SavedRoute> = write(listOf(route) + all().filterNot { it.id == route.id })

    fun rename(id: String, name: String): List<SavedRoute> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return all()
        return write(all().map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun setPinned(id: String, pinned: Boolean): List<SavedRoute> = write(all().map { if (it.id == id) it.copy(pinned = pinned) else it })

    fun delete(id: String): List<SavedRoute> = write(all().filterNot { it.id == id })

    private fun write(list: List<SavedRoute>): List<SavedRoute> {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
        return list
    }

    private companion object { const val KEY = "routes" }
}
