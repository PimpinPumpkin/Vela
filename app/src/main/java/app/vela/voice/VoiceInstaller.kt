package app.vela.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets a phone with **no text-to-speech engine** (common on degoogled ROMs) install
 * an open-source one in one tap, so spoken navigation works everywhere without bundling
 * a heavy native synth into Vela. Downloads the latest F-Droid build of the chosen
 * engine and hands the APK to the system installer; the user confirms the install. Once
 * installed it's a normal system engine that all of Vela's existing [VoiceGuide] code
 * already drives.
 */
@Singleton
class VoiceInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    // A body-tolerant client for the APK download (an engine APK can be tens of MB): the shared `http`
    // carries a callTimeout(12s) that scrape-bounds requests but ABORTS a large body mid-stream, which
    // runCatching silently ate → the one-tap install fell back to the web page for no reason. Mirrors
    // the KokoroInstaller/RoutingGraphStore/OverlayTileStore rule (audit 2026-07-06). Manifest fetch
    // stays on `http` (tiny).
    private val downloadHttp by lazy {
        http.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    data class Engine(val pkg: String, val label: String, val note: String)

    /** Offered engines, all FOSS on F-Droid. SherpaTTS runs sherpa-onnx neural models (Kokoro is
     * near-Siri quality) but needs a one-time model download; eSpeak is tiny + self-contained;
     * RHVoice is in between. Best-first. */
    val engines = listOf(
        Engine(
            "org.woheller69.ttsengine", "SherpaTTS - neural (Kokoro)",
            "Best quality by far - near-Siri neural voices. After it installs, open SherpaTTS once and download a voice (the multi-speaker \"kokoro\" model is excellent; a few hundred MB), then pick SherpaTTS here.",
        ),
        Engine(
            "com.github.olga_yakovleva.rhvoice.android", "RHVoice",
            "More natural than eSpeak, lighter than SherpaTTS. After it installs, open RHVoice once to add an English voice.",
        ),
        Engine("com.reecedunn.espeak", "eSpeak NG", "Tiny - speaks as soon as it installs (robotic but clear)."),
    )

    fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Download [pkg]'s latest F-Droid build and launch the system installer. Returns
     * null on success (installer launched), or a short status/error message. Falls
     * back to opening the F-Droid **package page** when the direct APK URL can't be
     * built/fetched - e.g. eSpeak ships **per-ABI split APKs**, so the single
     * `${pkg}_$vc.apk` path 404s and the one-tap download silently failed before. */
    suspend fun installFromFDroid(pkg: String): String? = withContext(Dispatchers.IO) {
        val vc = latestVersionCode(pkg)
        val downloaded = vc != null && runCatching {
            val url = "https://f-droid.org/repo/${pkg}_$vc.apk"
            val dir = File(context.filesDir, "engines").apply { mkdirs() }
            val apk = File(dir, "$pkg.apk")
            val ok = downloadHttp.newCall(Request.Builder().url(url).header("User-Agent", "VelaMaps").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                apk.outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
            if (ok && apk.length() >= 10_000L) { launchInstaller(apk); true } else { apk.delete(); false }
        }.getOrDefault(false)
        if (downloaded) return@withContext null
        // Fallback: hand off to F-Droid's page so the user can grab the right split.
        openWeb("https://f-droid.org/packages/$pkg/")
        "Opened F-Droid - tap Download/Install there"
    }

    private fun openWeb(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // Just one field out of the F-Droid index - a regex avoids pulling a JSON parser
    // into :app (kotlinx.serialization lives in :core).
    private val versionCodeRe = Regex("\"suggestedVersionCode\"\\s*:\\s*(\\d+)")

    private fun latestVersionCode(pkg: String): Long? = runCatching {
        http.newCall(Request.Builder().url("https://f-droid.org/api/v1/packages/$pkg").build()).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            versionCodeRe.find(body)?.groupValues?.get(1)?.toLongOrNull()
        }
    }.getOrNull()

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
