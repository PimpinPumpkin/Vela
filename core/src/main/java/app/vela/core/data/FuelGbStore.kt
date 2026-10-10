package app.vela.core.data

import app.vela.core.VelaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * The UK fuel price file on the phone (SPEC 5.8). Nothing is fetched until the app asks, which
 * it does only when a gas station inside [FuelGb.inUk] has no price. Then:
 *
 * - the file on disk is parsed if nothing is in memory;
 * - at most every [CHECK_EVERY_MS] the small manifest is read from the `fuel-gb` release, and the
 *   data file is downloaded only when the manifest names a different one (the release has no
 *   conditional requests to lean on, so the manifest's sha256 is the change test);
 * - a failure is retried after [RETRY_AFTER_MS] and never replaces a good file.
 *
 * [dir] is read on every access (the app passes a folder under `StorageLocation.root`). [http]
 * must have no call timeout. Requests carry Vela's own user agent.
 */
class FuelGbStore(
    private val dir: () -> File,
    private val http: OkHttpClient,
    private val baseUrl: String = BASE_URL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val BASE_URL = "https://github.com/PimpinPumpkin/Vela/releases/download/fuel-gb/"
        const val FOLDER = "fuelgb"
        const val DATA_FILE = "fuel-gb.csv.gz"
        const val MANIFEST_FILE = "fuel-gb-manifest.json"
        private const val CHECKED_FILE = "checked"
        const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 15L * 60 * 1000
        private val FILE_NAME = Regex("[A-Za-z0-9._-]+")
    }

    private val mutex = Mutex()
    @Volatile private var data: FuelGbStations? = null
    @Volatile private var failedAtMs = 0L

    /** The stations in memory, or null until [ensure] has loaded them. */
    val current: FuelGbStations? get() = data

    /** Drop the parsed stations (memory pressure). The next [ensure] reads the file again. */
    fun release() { data = null }

    /**
     * Load the file from disk and fetch a newer one when due. True when [current] changed. Never
     * throws; any failure keeps what is there.
     */
    suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { ensureLocked() }.getOrDefault(false) }
    }

    private fun ensureLocked(): Boolean {
        val d = dir().apply { mkdirs() }
        val file = File(d, DATA_FILE)
        var changed = false
        if (data == null && file.exists()) {
            data = runCatching { file.inputStream().use { FuelGb.parseGzip(it) } }.getOrNull()?.takeIf { it.size > 0 }
            changed = data != null
        }
        val now = nowMs()
        val checked = File(d, CHECKED_FILE)
        val lastCheck = runCatching { checked.readText().trim().toLong() }.getOrDefault(0L)
        if (file.exists() && data != null && now - lastCheck in 0 until CHECK_EVERY_MS) return changed
        if (now - failedAtMs in 0 until RETRY_AFTER_MS) return changed

        val remoteText = get(baseUrl + MANIFEST_FILE)?.toString(Charsets.UTF_8)
        val remote = remoteText?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (remote == null) { failedAtMs = now; return changed }
        val localManifest = File(d, MANIFEST_FILE)
        val local = runCatching { Json.parseToJsonElement(localManifest.readText()).jsonObject }.getOrNull()
        val remoteSha = remote.str("fileSha256")
        if (file.exists() && data != null && remoteSha != null && remoteSha == local?.str("fileSha256")) {
            checked.writeText(now.toString())
            return changed
        }
        val name = remote.str("file")?.takeIf { it.matches(FILE_NAME) } ?: DATA_FILE
        val bytes = get(baseUrl + name)
        if (bytes == null || bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte() ||
            (remoteSha != null && sha256(bytes) != remoteSha)
        ) { failedAtMs = now; return changed }
        val parsed = runCatching { FuelGb.parseGzip(ByteArrayInputStream(bytes)) }.getOrNull()
        if (parsed == null || parsed.size == 0) { failedAtMs = now; return changed }

        // Stage beside the target, move the old copy aside, swap, then drop the old copy.
        val tmp = File(d, "$DATA_FILE.tmp").apply { writeBytes(bytes) }
        val old = File(d, "$DATA_FILE.old").apply { delete() }
        if (file.exists() && !file.renameTo(old)) { tmp.delete(); failedAtMs = now; return changed }
        if (!tmp.renameTo(file)) {
            old.renameTo(file); tmp.delete(); failedAtMs = now; return changed
        }
        old.delete()
        localManifest.writeText(remoteText)
        checked.writeText(now.toString())
        data = parsed
        return true
    }

    private fun get(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", VelaConfig.VELA_UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
