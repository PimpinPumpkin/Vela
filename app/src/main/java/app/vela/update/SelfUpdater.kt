package app.vela.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app updater, the PipePipe/NewPipe pattern: check the newest GitHub release, offer it
 * when it's newer than this build, download the APK and hand it to the SYSTEM installer.
 * The OS enforces the update contract from there (same package + same signing key, user
 * confirms the install dialog), so this never sideloads anything the platform wouldn't
 * accept as an update of the installed app. Obtainium users can keep using Obtainium; the
 * launch check is a Settings toggle.
 *
 * Version scheme (see CI): release tag `v0.<minor>.<run>` = versionCode `run` (the GitHub run
 * number, global and monotonic per repo), so the tag alone tells us if the release is newer. The
 * APK asset is the single `.apk` on the release.
 */
@Singleton
class SelfUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    data class UpdateInfo(
        val versionName: String,   // "0.2.213"
        val versionCode: Int,      // 2213
        val apkUrl: String,
        val sizeBytes: Long,
        val notes: String,
    )

    // The APK is ~80 MB - same no-call-timeout rule as every large download (the shared
    // client's 12 s scrape cap would abort the body mid-read, silently).
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Newest release if it's newer than this build, else null. Null on any error too
     * (the check is best-effort; a launch must never block or complain about it). */
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val json = http.newCall(
                Request.Builder()
                    .url("https://api.github.com/repos/alltechdev/vela-dpad/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build(),
            ).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
            val o = JSONObject(json)
            val tag = o.getString("tag_name") // v0.<minor>.<run>
            // Parse the RUN, not a hardcoded minor: the line moved 0.2 -> 0.3 once already and a
            // prefix-pinned parse would have silently stopped updating anyone on the old parse.
            val run = Regex("""^v0\.\d+\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@runCatching null
            val code = run
            if (code <= currentVersionCode) return@runCatching null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return@runCatching null
            UpdateInfo(
                versionName = tag.removePrefix("v"),
                versionCode = code,
                apkUrl = apk.getString("browser_download_url"),
                sizeBytes = apk.optLong("size"),
                notes = o.optString("body"),
            )
        }.getOrNull()
    }

    /** Download [info]'s APK to filesDir/updates/. 0..100 progress. Null on failure. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        // One update on disk at a time - an old half-download or a superseded APK is junk.
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, "vela-${info.versionCode}.apk")
        runCatching {
            downloadHttp.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body!!.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                resp.body!!.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (100 * read / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            }
            // An APK is a zip - cheap magic check so a truncated/error body never reaches
            // the installer (it would fail there too, but with a scarier dialog).
            check(dest.length() > 4 && dest.inputStream().use { s ->
                val m = ByteArray(2); s.read(m); m[0] == 'P'.code.toByte() && m[1] == 'K'.code.toByte()
            }) { "downloaded file is not an APK" }
            dest
        }.getOrElse { dest.delete(); null }
    }

    /** Hand [apk] to the system package installer (user confirms; OS verifies signature). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
