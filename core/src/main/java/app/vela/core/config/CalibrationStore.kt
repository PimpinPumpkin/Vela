package app.vela.core.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the active [Calibration] and refreshes it from the public repo.
 *
 * On construction it loads a cached copy (if newer than the bundled [DEFAULT]),
 * else uses [DEFAULT] — so the app always starts with a working config, offline.
 * [refresh] (called once at startup) pulls `calibration.json` from the repo over
 * HTTPS; it adopts the remote only if it parses, every endpoint host is on the
 * allowlist (so a tampered file can't redirect requests off Google/OSM), and the
 * version is newer. The newest config thus reaches every user within a launch or
 * two — no APK update — which is the whole point.
 */
@Singleton
class CalibrationStore @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "calibration.json")

    @Volatile
    private var active: Calibration = loadCached() ?: Calibration.DEFAULT

    @Volatile
    private var refreshed = false

    /** The config every scraper call reads. Never null; never blocks. */
    fun current(): Calibration = active

    suspend fun refresh() {
        if (refreshed) return
        refreshed = true
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(REMOTE_URL).header("User-Agent", "VelaMaps").build()
                val body = http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: return@runCatching
                val remote = parse(body) ?: return@runCatching
                if (remote.version > active.version && isAllowed(remote)) {
                    runCatching { cacheFile.writeText(body) }
                    active = remote
                }
            }
        }
    }

    private fun loadCached(): Calibration? = runCatching {
        if (!cacheFile.exists()) return null
        parse(cacheFile.readText())?.takeIf { it.version >= Calibration.DEFAULT.version && isAllowed(it) }
    }.getOrNull()

    /** Lenient JSON → Calibration: any missing field falls back to [DEFAULT], so
     *  a remote file can override just the one thing that drifted. */
    private fun parse(body: String): Calibration? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        fun str(k: String, fallback: String): String =
            (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback
        val version = (o["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@runCatching null
        val d = Calibration.DEFAULT
        // Field-index paths: each value is a JSON array of ints; merge the remote
        // ones over the bundled defaults so a file can override just one path.
        val remotePaths = (o["paths"] as? JsonObject)?.mapNotNull { (k, v) ->
            val list = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            if (!list.isNullOrEmpty()) k to list else null
        }?.toMap().orEmpty()
        Calibration(
            version = version,
            searchEndpoint = str("searchEndpoint", d.searchEndpoint),
            searchPb = str("searchPb", d.searchPb),
            directionsEndpoint = str("directionsEndpoint", d.directionsEndpoint),
            directionsPb = str("directionsPb", d.directionsPb),
            reviewsEndpoint = str("reviewsEndpoint", d.reviewsEndpoint),
            reviewsPb = str("reviewsPb", d.reviewsPb),
            sessionWarmUrl = str("sessionWarmUrl", d.sessionWarmUrl),
            photosEndpoint = str("photosEndpoint", d.photosEndpoint),
            photosProto = str("photosProto", d.photosProto),
            paths = Calibration.DEFAULT_PATHS + remotePaths,
        )
    }.getOrNull()

    /** Security gate: every endpoint must point at an allow-listed host, so a
     *  compromised config can never exfiltrate requests to an attacker's server. */
    private fun isAllowed(c: Calibration): Boolean {
        val hosts = listOf(c.searchEndpoint, c.directionsEndpoint, c.reviewsEndpoint, c.photosEndpoint, c.sessionWarmUrl)
            .map { runCatching { URI(it).host }.getOrNull() }
        return hosts.all { it != null && it in ALLOWED_HOSTS }
    }

    private companion object {
        const val REMOTE_URL = "https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/calibration.json"
        val ALLOWED_HOSTS = setOf("www.google.com", "google.com")
    }
}
