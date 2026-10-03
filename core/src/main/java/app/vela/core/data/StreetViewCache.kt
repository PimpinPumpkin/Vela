package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.StreetViewPano
import app.vela.core.model.distanceTo
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Offline Street View cache: one metadata JSON + one stitched-equirect JPEG per
 * viewed panorama, under `<dir>/<panoId>.json` / `<panoId>.jpg`. Written after
 * every successful online tile load, served when Street View opens with no
 * network (nearest cached pano to the requested spot). The VM handles the
 * Bitmap↔JPEG step (android.graphics); this store only moves JSON and bytes.
 *
 * No migration story: a broken file reads as a miss. Pruned to [MAX_BYTES]
 * oldest-first on every save.
 */
object StreetViewCache {
    const val MAX_BYTES = 150L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun keyOf(panoId: String): String =
        panoId.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(80) +
            "_" + (panoId.hashCode().toString().replace("-", "m"))

    fun metaFile(dir: File, panoId: String): File = File(dir, keyOf(panoId) + ".json")

    fun imageFile(dir: File, panoId: String): File = File(dir, keyOf(panoId) + ".jpg")

    fun saveMeta(dir: File, pano: StreetViewPano) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            metaFile(dir, pano.panoId).writeText(json.encodeToString(StreetViewPano.serializer(), pano))
        }
    }

    fun saveImage(dir: File, panoId: String, jpeg: ByteArray) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            imageFile(dir, panoId).writeBytes(jpeg)
            prune(dir)
        }
    }

    fun loadMeta(dir: File, panoId: String): StreetViewPano? = runCatching {
        val f = metaFile(dir, panoId)
        if (!f.exists()) return null
        json.decodeFromString(StreetViewPano.serializer(), f.readText())
    }.getOrNull()

    fun loadImage(dir: File, panoId: String): ByteArray? = runCatching {
        val f = imageFile(dir, panoId)
        if (!f.exists()) return null
        f.readBytes()
    }.getOrNull()

    fun has(dir: File, panoId: String): Boolean =
        metaFile(dir, panoId).exists() && imageFile(dir, panoId).exists()

    /** Nearest cached pano to [at] within [maxM] meters (location opens offline). */
    fun nearest(dir: File, at: LatLng, maxM: Double = 100.0): StreetViewPano? = runCatching {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return null
        var best: StreetViewPano? = null
        var bestD = maxM
        for (f in files) {
            val pano = runCatching { json.decodeFromString(StreetViewPano.serializer(), f.readText()) }.getOrNull()
                ?: continue
            if (!imageFile(dir, pano.panoId).exists()) continue
            val d = LatLng(pano.lat, pano.lng).distanceTo(at)
            if (d < bestD) {
                bestD = d
                best = pano
            }
        }
        best
    }.getOrNull()

    fun dirSizeBytes(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun clear(dir: File) {
        runCatching { dir.listFiles()?.forEach { if (it.extension == "json" || it.extension == "jpg") it.delete() } }
    }

    private fun prune(dir: File, maxBytes: Long = MAX_BYTES) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                total -= f.length()
                f.delete()
            }
        }
    }
}
