package app.vela.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
 * Version scheme (see CI): release tag `v0.<minor>.<run>` = versionCode `2000 + run` (the run
 * number is global and monotonic across minor bumps), so the tag alone tells us if the release
 * is newer. The APK asset is the single `.apk` on the release.
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

    // The APK is ~80 MB — same no-call-timeout rule as every large download (the shared
    // client's 12 s scrape cap would abort the body mid-read, silently).
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        /** Releases fetched for the cumulative What's-new notes, newest first; one request each. */
        const val HISTORY_MAX_RELEASES = 8

        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_NIGHTLY = "nightly"
        const val CHANNEL_CANARY = "canary"

        /** The picked update channel, migrating the old boolean nightly toggle in place. */
        fun channel(prefs: android.content.SharedPreferences): String =
            prefs.getString("update_channel", null)
                ?: if (prefs.getBoolean("update_nightly", false)) CHANNEL_NIGHTLY else CHANNEL_STABLE
    }

    /** Newest release on [channel] if it's newer than this build, else null. Null on any error
     *  too (the check is best-effort; a launch must never block or complain about it).
     *  stable = releases/latest; nightly = highest-code v0.* prerelease; canary = the rolling
     *  fixed-tag `canary` release (versionCode read from its notes, since the tag never
     *  changes), falling back to the newest nightly when that is ahead so a stale canary
     *  never strands its users behind the fleet. */
    suspend fun check(currentVersionCode: Int, channel: String = CHANNEL_STABLE): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            fun releaseToInfo(o: JSONObject): UpdateInfo? {
                val tag = o.getString("tag_name") // v0.<minor>.<run>
                // Parse the RUN, not a hardcoded minor: the line moved 0.2 -> 0.3 once already and a
                // prefix-pinned parse would have silently stopped updating anyone on the old parse.
                val run = Regex("""^v0\.\d+\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val code = 2000 + run
                val assets = o.getJSONArray("assets")
                val apk = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
                return UpdateInfo(tag.removePrefix("v"), code, apk.getString("browser_download_url"), apk.optLong("size"), o.optString("body"))
            }
            var requests = 0; var bytes = 0L
            val started = System.currentTimeMillis()
            fun getJson(url: String): String = http.newCall(
                Request.Builder().url(url).header("Accept", "application/vnd.github+json").build(),
            ).execute().use { r ->
                requests++
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body!!.string().also { bytes += it.length }
            }
            fun logCheck(result: UpdateInfo?) = android.util.Log.d(
                "VelaUpdate",
                "check channel=$channel installed=$currentVersionCode -> " + (result?.let { "${it.versionName} (${it.versionCode})" } ?: "nothing newer") +
                    " requests=$requests bytes=$bytes in ${System.currentTimeMillis() - started} ms",
            )
            // The rolling canary release: the tag is always "canary", so the version comes from
            // the versionName/versionCode lines CI writes into the release notes each push.
            fun canaryInfo(): UpdateInfo? = runCatching {
                val o = JSONObject(getJson("https://api.github.com/repos/PimpinPumpkin/Vela/releases/tags/canary"))
                val body = o.optString("body")
                val code = Regex("""versionCode:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val name = Regex("""versionName:\s*(\S+)""").find(body)?.groupValues?.get(1) ?: "canary"
                val assets = o.getJSONArray("assets")
                val apk = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
                UpdateInfo(name, code, apk.getString("browser_download_url"), apk.optLong("size"), body)
            }.getOrNull()
            // THE RELEASES LIST IS NEVER FETCHED (2026-09-22). The repository's data releases
            // (obf-regions, places-overlays, basemap-tiles, road-features) each list ~450 assets,
            // about 780 KB of JSON apiece, and since the world bakes they sort into the top of the
            // list: one check pulled 4 to 9 MB over cellular and parsed it with org.json on the
            // phone, which is what "checking for updates is slow" was. The app-release TAGS come
            // from the refs endpoint instead (~200 KB for 550 tags, no bodies, no assets) and a
            // release is fetched one tag at a time (~15 KB each), at most a dozen per check.
            fun appRuns(): List<Int> = runCatching {
                val arr = JSONArray(getJson("https://api.github.com/repos/PimpinPumpkin/Vela/git/matching-refs/tags/v0."))
                (0 until arr.length()).mapNotNull { i ->
                    val ref = arr.getJSONObject(i).optString("ref")
                    Regex("""^refs/tags/v0\.\d+\.(\d+)$""").find(ref)?.groupValues?.get(1)?.toIntOrNull()
                }.distinct().sortedDescending()
            }.getOrDefault(emptyList())
            fun releaseForRun(run: Int, minor: Int? = null): JSONObject? = runCatching {
                // The tag's minor is not in the run number; try the current line first, then the
                // older ones (the line moved 0.2 -> 0.3 -> 0.4 already).
                val minors = listOfNotNull(minor) + listOf(4, 3, 2).filter { it != minor }
                minors.firstNotNullOfOrNull { m ->
                    runCatching { JSONObject(getJson("https://api.github.com/repos/PimpinPumpkin/Vela/releases/tags/v0.$m.$run")) }.getOrNull()
                }
            }.getOrNull()
            fun nightlyInfo(): UpdateInfo? {
                // The newest app tag that has a published, non-draft release (a nightly, or a stable
                // that was a nightly): the highest run is the newest either way.
                for (run in appRuns().take(3)) {
                    val o = releaseForRun(run) ?: continue
                    if (o.optBoolean("draft")) continue
                    return releaseToInfo(o) ?: continue
                }
                return null
            }
            val candidate = when (channel) {
                CHANNEL_CANARY -> listOfNotNull(canaryInfo(), nightlyInfo()).maxByOrNull { it.versionCode }
                CHANNEL_NIGHTLY -> nightlyInfo()
                else -> releaseToInfo(JSONObject(getJson("https://api.github.com/repos/PimpinPumpkin/Vela/releases/latest")))
            }
            val picked = candidate?.takeIf { it.versionCode > currentVersionCode } ?: run { logCheck(null); return@runCatching null }
            // Every release between the one installed and the one offered, newest first (issue
            // #330): a phone that skipped a few releases gets their notes too, not just the
            // last. Canary's rolling tag carries its own list already, and a failure here
            // falls back to the single release's notes.
            if (channel == CHANNEL_CANARY) { logCheck(picked); return@runCatching picked }
            // The releases between the installed and the offered one, one small fetch each,
            // capped so a phone many releases behind does not spend its API allowance.
            val history = runCatching {
                val minor = picked.versionName.substringAfter("0.").substringBefore(".").toIntOrNull()
                appRuns()
                    .filter { run -> 2000 + run in (currentVersionCode + 1)..picked.versionCode }
                    .take(HISTORY_MAX_RELEASES)
                    .mapNotNull { run -> releaseForRun(run, minor) }
                    .filterNot { it.optBoolean("draft") }
                    .filter { it.optBoolean("prerelease") == (channel != CHANNEL_STABLE) }
                    .mapNotNull { o -> releaseToInfo(o)?.let { it to o.optString("body") } }
            }.getOrDefault(emptyList())
            picked.copy(notes = cumulativeNotes(history, currentVersionCode, picked)).also { logCheck(it) }
        }.onFailure { android.util.Log.w("VelaUpdate", "check failed: $it") }.getOrNull()
    }


    /** Download [info]'s APK to filesDir/updates/. 0..100 progress. Null on failure or when
     *  [active] flips false (user cancel - the partial file is deleted by the failure path). */
    suspend fun download(info: UpdateInfo, active: () -> Boolean = { true }, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        // One update on disk at a time — an old half-download or a superseded APK is junk.
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
                            if (!active()) error("canceled")
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
            // An APK is a zip — cheap magic check so a truncated/error body never reaches
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

/** The notes to show for [picked] (issue #330): every release in [history] with a code above
 *  [currentVersionCode] and at or below the picked one, newest first, each under its version,
 *  when there are at least two; else the picked release's own notes. */
internal fun cumulativeNotes(history: List<Pair<SelfUpdater.UpdateInfo, String>>, currentVersionCode: Int, picked: SelfUpdater.UpdateInfo): String {
    val between = history
        .filter { (info, _) -> info.versionCode > currentVersionCode && info.versionCode <= picked.versionCode }
        .distinctBy { it.first.versionCode }
        .sortedByDescending { it.first.versionCode }
    if (between.size < 2) return picked.notes
    return between.joinToString("\n\n") { (info, body) -> "${info.versionName}\n\n${body.trim()}" }
}
