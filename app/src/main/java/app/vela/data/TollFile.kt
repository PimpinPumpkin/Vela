package app.vela.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import app.vela.core.data.Tolls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The toll price file ([Tolls], SPEC 7, Toll prices): a copy bundled in `assets/tolls.json`, and
 * a newer one from the `tolls` release, which [refresh] downloads to `filesDir/tolls/` when the
 * manifest's version beats what is installed. The newest by version is loaded. The file is small,
 * so it is read whole.
 */
object TollFile {
    private const val BUNDLED = "tolls.json"
    private const val BUNDLED_VER = "tolls_version.txt"

    /** Raised each time a file is loaded, so a route row works out its toll again. */
    val generation = mutableIntStateOf(0)

    private fun dir(context: Context) = File(context.filesDir, "tolls").apply { mkdirs() }
    private fun downloaded(context: Context) = File(dir(context), "tolls.json")
    private fun downloadedVer(context: Context) = File(dir(context), "version.txt")

    private fun bundledVersion(context: Context): Int =
        runCatching { context.assets.open(BUNDLED_VER).bufferedReader().use { it.readText() }.trim().toInt() }.getOrDefault(0)

    private fun downloadedVersion(context: Context): Int {
        if (!downloaded(context).exists()) return -1
        return runCatching { downloadedVer(context).readText().trim().toInt() }.getOrDefault(-1)
    }

    /** Loads the newer of the downloaded and bundled copies, off the main thread. A download older
     *  than the bundled copy is deleted. */
    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        val useDownload = downloaded(context).exists() && downloadedVersion(context) >= bundledVersion(context)
        if (!useDownload) runCatching { downloaded(context).delete(); downloadedVer(context).delete() }
        val text = if (useDownload) runCatching { downloaded(context).readText() }.getOrNull()
            else runCatching { context.assets.open(BUNDLED).bufferedReader().use { it.readText() } }.getOrNull()
        if (text != null && Tolls.load(text)) withContext(Dispatchers.Main) { generation.intValue++ }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Reads the hosted manifest and, when it names a newer version, downloads the file, checks it
     *  loads, and keeps it. Any failure keeps the current file. */
    suspend fun refresh(context: Context, manifestUrl: String) = withContext(Dispatchers.IO) {
        runCatching {
            val manifest = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { if (!it.isSuccessful) return@withContext; it.body?.string().orEmpty() }
            val obj = org.json.JSONObject(manifest)
            val version = obj.optInt("version", -1)
            val url = obj.optString("url", "")
            if (version < 0 || url.isBlank() || version <= maxOf(downloadedVersion(context), bundledVersion(context))) return@withContext
            val text = http.newCall(Request.Builder().url(url).build()).execute()
                .use { if (!it.isSuccessful) return@withContext; it.body?.string().orEmpty() }
            if (!Tolls.load(text)) return@withContext
            val tmp = File(dir(context), "tolls.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(downloaded(context))) { tmp.copyTo(downloaded(context), overwrite = true); tmp.delete() }
            downloadedVer(context).writeText(version.toString())
            withContext(Dispatchers.Main) { generation.intValue++ }
        }
        Unit
    }
}
