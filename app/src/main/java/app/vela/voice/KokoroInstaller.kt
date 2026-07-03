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
 * straight into a target dir under `filesDir`, extracting it, reporting 0f..1f progress. Generic over
 * the voice (Kokoro or Piper) — pass the URL + dest dir. Best-effort: any failure wipes the partial
 * model so a retry starts clean. This is what lets Vela run neural voices WITHOUT a standalone app.
 */
@Singleton
class KokoroInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    /** Download [url]'s model into [destDir], extracting the archive's single top-level folder into
     *  it. [onProgress] is the download fraction (0f..1f). [sizeEst] is a fallback total when the
     *  server omits Content-Length. Returns true once extraction completed. */
    suspend fun download(
        url: String,
        destDir: File,
        sizeEst: Long,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "voice.download.tmp")
        val staging = File(context.filesDir, "voice.staging")
        try {
            val fetched = http.newCall(
                Request.Builder().url(url).header("User-Agent", "VelaMaps").build(),
            ).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) return@use false
                val total = body.contentLength().takeIf { it > 0 } ?: sizeEst
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var read = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            read += n
                            onProgress((read.toFloat() / total).coerceIn(0f, 0.98f))
                        }
                    }
                }
                true
            }
            if (!fetched) return@withContext false

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

    companion object {
        // sherpa-onnx tts-models release .tar.bz2 sources + fallback sizes.
        const val KOKORO_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
        const val KOKORO_SIZE = 131_839_838L
        const val PIPER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-hfc_female-medium.tar.bz2"
        const val PIPER_SIZE = 67_228_166L
    }
}
