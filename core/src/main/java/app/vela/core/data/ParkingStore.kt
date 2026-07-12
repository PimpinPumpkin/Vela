package app.vela.core.data

import android.content.Context
import app.vela.core.model.ParkedSpot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The saved parking spot + a capped history of past saves (newest first). The history is
 * the accidental-overwrite safety net: a fresh save never destroys where the car was last
 * time. Stored in the shared `vela_settings` prefs (same file the map/nav toggles use).
 */
@Singleton
class ParkingStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    // ignoreUnknownKeys: a newer build's extra field must not fail the decode here,
    // or the getOrDefault(empty) wipes the data on the next write (see PlaceListStore).
    private val json = Json { ignoreUnknownKeys = true }

    data class Current(val lat: Double, val lng: Double, val savedAtMillis: Long)

    fun current(): Current? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        return Current(lat, lng, prefs.getLong(KEY_AT, 0L))
    }

    fun history(): List<ParkedSpot> =
        runCatching { json.decodeFromString<List<ParkedSpot>>(prefs.getString(KEY_HISTORY, "[]") ?: "[]") }
            .getOrDefault(emptyList())

    /** Saves [spot] as current AND prepends it to history (de-duped by ~1 m cell, capped). */
    fun save(spot: ParkedSpot): List<ParkedSpot> {
        val history = (listOf(spot) + history())
            .distinctBy { "${(it.lat * 1e5).toInt()},${(it.lng * 1e5).toInt()}" }
            .take(HISTORY_MAX)
        prefs.edit()
            .putString(KEY_LAT, spot.lat.toString())
            .putString(KEY_LNG, spot.lng.toString())
            .putLong(KEY_AT, spot.savedAtMillis)
            .putString(KEY_HISTORY, json.encodeToString(history))
            .apply()
        return history
    }

    /** Clears only the CURRENT spot; history stays (the safety net). */
    fun clearCurrent() {
        prefs.edit().remove(KEY_LAT).remove(KEY_LNG).remove(KEY_AT).apply()
    }

    /** Makes a history entry current again (no change to the history list). */
    fun restore(spot: ParkedSpot) {
        prefs.edit()
            .putString(KEY_LAT, spot.lat.toString())
            .putString(KEY_LNG, spot.lng.toString())
            .putLong(KEY_AT, spot.savedAtMillis)
            .apply()
    }

    fun deleteFromHistory(spot: ParkedSpot): List<ParkedSpot> {
        val history = history().filterNot { it == spot }
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(history)).apply()
        return history
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private companion object {
        const val KEY_LAT = "parking_lat"
        const val KEY_LNG = "parking_lng"
        const val KEY_AT = "parking_at"
        const val KEY_HISTORY = "parking_history"
        const val HISTORY_MAX = 20
    }
}
