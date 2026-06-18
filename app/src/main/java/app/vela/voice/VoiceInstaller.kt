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
    data class Engine(val pkg: String, val label: String, val note: String)

    /** Offered engines, both GPL/FOSS on F-Droid. eSpeak is self-contained; RHVoice is
     *  more natural but needs a one-time in-app voice download. */
    val engines = listOf(
        Engine("com.reecedunn.espeak", "eSpeak NG", "Tiny — speaks as soon as it installs (robotic but clear)."),
        Engine(
            "com.github.olga_yakovleva.rhvoice.android", "RHVoice",
            "More natural. After it installs, open RHVoice once to add an English voice.",
        ),
    )

    fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Download [pkg]'s latest F-Droid build and launch the system installer. Returns
     *  null on success, or a short error message. */
    suspend fun installFromFDroid(pkg: String): String? = withContext(Dispatchers.IO) {
        val vc = latestVersionCode(pkg) ?: return@withContext "Couldn't reach F-Droid"
        val url = "https://f-droid.org/repo/${pkg}_$vc.apk"
        val dir = File(context.filesDir, "engines").apply { mkdirs() }
        val apk = File(dir, "$pkg.apk")
        val ok = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "VelaMaps").build()).execute().use { resp ->
                val body = resp.body ?: return@use false
                if (!resp.isSuccessful) return@use false
                apk.outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
        }.getOrDefault(false)
        if (!ok || apk.length() < 10_000L) return@withContext "Download failed"
        launchInstaller(apk)
        null
    }

    // Just one field out of the F-Droid index — a regex avoids pulling a JSON parser
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
