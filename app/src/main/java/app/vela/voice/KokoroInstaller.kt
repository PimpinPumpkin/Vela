package app.vela.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a Vela neural-voice model (a sherpa-onnx `.tar.bz2` from the `tts-models` GitHub release)
 * into a target dir under `filesDir`, extracting it, reporting 0f..1f progress. Best-effort: any
 * failure wipes the partial model. (Named for the original Kokoro voice; today it fetches the Piper
 * catalog models, and the multi-part Kokoro/Matcha plumbing is gone with those voices.)
 */
@Singleton
class KokoroInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
) {
    // The shared client caps a whole CALL at 12 s to bound a hung scrape — but that also kills a
    // multi-tens-of-MB model download (it can't finish the body in 12 s). Derive a download client with
    // NO overall call timeout; a generous per-read socket timeout still catches a truly stalled connection.
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    /** Download [url] into [destDir] (extracting the archive's single top-level folder into it).
     *  [onProgress] is 0f..1f. */
    suspend fun download(
        url: String,
        destDir: File,
        sizeEst: Long,
        onExtracting: () -> Unit = {},
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "voice.download.tmp")
        val staging = File(context.filesDir, "voice.staging")
        try {
            // The download progress bar is the DOWNLOAD only (honest + fast). The bunzip2+untar of the
            // ~67 MB archive is seconds of CPU that no % can meaningfully track, so instead of mapping it
            // into the tail (which read as a download "hanging at 98%" / crawling the last 10%, user
            // 2026-07-07) we flip to a separate "Installing…" phase via [onExtracting].
            if (!stream(url, tmp, sizeEst, 0f, 1f, onProgress)) return@withContext false

            onExtracting()
            staging.deleteRecursively(); staging.mkdirs()
            extractTarBz2(tmp, staging)
            val inner = staging.listFiles()?.firstOrNull { it.isDirectory } ?: staging
            destDir.deleteRecursively()
            if (!inner.renameTo(destDir)) inner.copyRecursively(destDir, overwrite = true)

            onProgress(1f)
            true
        } catch (t: Throwable) {
            destDir.deleteRecursively()
            false
        } finally {
            tmp.delete()
            staging.deleteRecursively()
        }
    }

    /** Stream [url] to [out], reporting progress mapped into the [base, base+span] slice of the bar. */
    private fun stream(url: String, out: File, sizeEst: Long, base: Float, span: Float, onProgress: (Float) -> Unit): Boolean =
        downloadHttp.newCall(Request.Builder().url(url).header("User-Agent", "VelaMaps").build()).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) return@use false
            val total = body.contentLength().takeIf { it > 0 } ?: sizeEst
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        o.write(buf, 0, n)
                        read += n
                        onProgress((base + span * (read.toFloat() / total)).coerceIn(0f, base + span))
                    }
                }
            }
            true
        }

    private fun extractTarBz2(src: File, destDir: File) {
        src.inputStream().buffered().use { fin ->
            BZip2CompressorInputStream(fin).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val out = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
