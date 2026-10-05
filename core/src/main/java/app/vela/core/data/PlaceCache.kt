package app.vela.core.data

import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.distanceTo
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Offline place cache: one JSON file per place (details + first review page),
 * written on every successful online load, read when the network is gone.
 * Photos ride Coil's own disk cache (prefetched alongside); text menus are NOT
 * parsed anywhere yet, so there is nothing of them to cache.
 *
 * Records live under `<dir>/<key>.json` where the key is the most stable id
 * available (placeId, then featureId, then the row id), sanitized. Stale past
 * [MAX_AGE_MS]. No migration story: a broken file reads as a miss.
 */
@Serializable
data class CachedPlace(
    val place: Place,
    val reviews: List<Review> = emptyList(),
    val savedAtMs: Long = 0L,
)

object PlaceCache {
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    fun keyOf(place: Place): String {
        val raw = place.placeId?.takeIf { it.isNotBlank() }
            ?: place.featureId?.takeIf { it.isNotBlank() }
            ?: place.id
        return raw.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(80) +
            "_" + (raw.hashCode().toString().replace("-", "m"))
    }

    fun save(dir: File, place: Place, reviews: List<Review>) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val rec = CachedPlace(place, reviews, System.currentTimeMillis())
            File(dir, keyOf(place) + ".json").writeText(json.encodeToString(CachedPlace.serializer(), rec))
        }
    }

    fun load(dir: File, place: Place, maxAgeMs: Long = MAX_AGE_MS): CachedPlace? = runCatching {
        val direct = File(dir, keyOf(place) + ".json")
        if (direct.exists()) {
            val rec = json.decodeFromString(CachedPlace.serializer(), direct.readText())
            if (System.currentTimeMillis() - rec.savedAtMs <= maxAgeMs) return rec
        }
        // Key-scheme drift: a reopened saved/recent place carries only the row id
        // while the online record was keyed by placeId/featureId. Fall back to
        // scanning for the same place id (user 2026-09-28: offline open missed).
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            val rec = runCatching { json.decodeFromString(CachedPlace.serializer(), f.readText()) }.getOrNull()
            if (rec != null && System.currentTimeMillis() - rec.savedAtMs <= maxAgeMs && (rec.place.id == place.id || sameSpot(rec.place, place))) return rec
        }
        null
    }.getOrNull()

    /** An offline search answers from the downloaded data, whose ids are not Google's: the same
     *  name within [SAME_SPOT_M] is the same place. */
    private fun sameSpot(a: Place, b: Place): Boolean =
        a.location.distanceTo(b.location) <= SAME_SPOT_M &&
            app.vela.core.util.PlaceNames.normalized(a.name).let { it.isNotEmpty() && it == app.vela.core.util.PlaceNames.normalized(b.name) }

    fun dirSizeBytes(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Keeps the store bounded: files older than [maxAgeMs] go, then the oldest past [maxFiles]. */
    fun trim(dir: File, maxFiles: Int = MAX_FILES, maxAgeMs: Long = MAX_AGE_MS) {
        runCatching {
            val now = System.currentTimeMillis()
            val files = dir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() } ?: return
            files.forEachIndexed { i, f -> if (i >= maxFiles || now - f.lastModified() > maxAgeMs) f.delete() }
        }
    }
    const val MAX_FILES = 400
    const val SAME_SPOT_M = 60.0

    fun clear(dir: File) {
        runCatching { dir.listFiles()?.forEach { if (it.extension == "json") it.delete() } }
    }
}
