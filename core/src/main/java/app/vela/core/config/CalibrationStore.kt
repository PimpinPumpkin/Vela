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
 * On construction it loads a cached copy (if signature-valid and newer than the
 * bundled [DEFAULT]), else uses [DEFAULT] — so the app always starts with a
 * working config, offline. [refresh] (called once at startup) pulls
 * `calibration.json` + its detached `calibration.json.sig` over HTTPS and adopts
 * the remote only if:
 *   1. the **signature verifies** against [PINNED_PUBLIC_KEY] (so only the holder
 *      of the private key — kept out of the repo — can push config/notices/code),
 *   2. every endpoint host is on the allowlist (a tampered file can't redirect
 *      requests off Google), and
 *   3. the version is newer.
 * The newest config thus reaches every user within a launch or two — no APK
 * update — which is the whole point. Because the bundle is signed, it can safely
 * carry not just pb/endpoint/path config but user-facing [Notice]s and a sandboxed
 * JavaScript transform bundle ([Calibration.transformsJs]).
 */
@Singleton
class CalibrationStore @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "calibration.json")
    private val sigFile = File(context.filesDir, "calibration.json.sig")

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
                val body = fetch(REMOTE_URL) ?: return@runCatching
                val sig = fetch(SIG_URL) ?: return@runCatching
                // Reject anything we didn't sign — this is what makes pushing CODE safe.
                if (!verifySignature(body.toByteArray(Charsets.UTF_8), sig)) return@runCatching
                val remote = parse(body) ?: return@runCatching
                if (remote.version > active.version && isAllowed(remote)) {
                    runCatching {
                        cacheFile.writeText(body)
                        sigFile.writeText(sig)
                    }
                    active = remote
                }
            }
        }
    }

    private fun fetch(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "VelaMaps").build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun loadCached(): Calibration? = runCatching {
        if (!cacheFile.exists() || !sigFile.exists()) return null
        val body = cacheFile.readText()
        // Re-verify the cache too — a cache written by an older (unsigned) build, or
        // tampered on disk, falls back to the bundled DEFAULT for one launch.
        if (!verifySignature(body.toByteArray(Charsets.UTF_8), sigFile.readText())) return null
        parse(body)?.takeIf { it.version >= Calibration.DEFAULT.version && isAllowed(it) }
    }.getOrNull()

    /** ECDSA-P256/SHA-256 verify against the detached [sigBase64] using the pinned key. */
    private fun verifySignature(content: ByteArray, sigBase64: String): Boolean =
        BundleSignature.verify(content, sigBase64, PINNED_PUBLIC_KEY)

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
        val notices = (o["notices"] as? JsonArray)?.mapNotNull { el ->
            val n = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String): String? = (n[k] as? JsonPrimitive)?.content
            val id = s("id") ?: return@mapNotNull null
            val title = s("title") ?: return@mapNotNull null
            Notice(
                id = id,
                level = s("level") ?: Notice.LEVEL_INFO,
                title = title,
                body = s("body") ?: "",
                url = s("url"),
            )
        }.orEmpty()
        val transformsJs = (o["transformsJs"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
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
            notices = notices,
            transformsJs = transformsJs,
            defaultVoiceSpeaker = (o["defaultVoiceSpeaker"] as? JsonPrimitive)?.content?.toIntOrNull() ?: d.defaultVoiceSpeaker,
            defaultVoiceSpeed = (o["defaultVoiceSpeed"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: d.defaultVoiceSpeed,
            defaultVoiceId = (o["defaultVoiceId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: d.defaultVoiceId,
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
        const val SIG_URL = "https://raw.githubusercontent.com/PimpinPumpkin/Vela/main/calibration.json.sig"
        val ALLOWED_HOSTS = setOf("www.google.com", "google.com")

        // EC P-256 public key (SPKI, base64) — the private half lives only in
        // ~/.vela-signing/vela-calibration.key, never in the repo. Embedding the
        // PUBLIC key is safe; it just lets the app reject bundles we didn't sign.
        const val PINNED_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuz8/zxOJFhVqKco74fkmzrLlyPra4/pTEUm7lmue/Kig0T497fcs+hjhZkaSqVZAwloNrr0+0ILi7yATmU+d3g=="
    }
}
