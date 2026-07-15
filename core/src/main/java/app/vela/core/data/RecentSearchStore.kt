package app.vela.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Most-recent-first list of search queries, persisted locally (capped). */
@Singleton
class RecentSearchStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_recents", Context.MODE_PRIVATE)

    fun recent(): List<String> =
        runCatching { Json.decodeFromString<List<String>>(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val updated = (listOf(q) + recent().filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        prefs.edit().putString(KEY, Json.encodeToString(updated)).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "queries"
        const val MAX = 8
    }
}
