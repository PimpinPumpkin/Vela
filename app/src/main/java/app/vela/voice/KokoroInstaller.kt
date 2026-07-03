package app.vela.voice

import android.content.Context
import app.vela.core.voice.VelaKokoro
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
 * Downloads the Kokoro neural-TTS model (~126 MB `.tar.bz2` from the sherpa-onnx `tts-models` GitHub
 * release) straight into `filesDir/kokoro`, extracting it, and reports 0f..1f progress so Settings can
 * show a real bar. Best-effort: any failure wipes the partial model so a retry starts clean.
 *
 * This is what lets Vela run the neural voice WITHOUT the standalone SherpaTTS app — Vela fetches the
 * exact model itself. The runtime that plays it (sherpa-onnx) is bundled; only the model is remote.
 */
@Singleton
class KokoroInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    fun isInstalled(): Boolean = VelaKokoro.isReady(context)

    /** Download + extract the model. [onProgress] is the download fraction (0f..1f). Returns true
     *  once the model is present and usable. */
    suspend fun download(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val dir = VelaKokoro.modelDir(context)
        val tmp = File(context.filesDir, "kokoro.download.tmp")
        val staging = File(context.filesDir, "kokoro.staging")
        try {
            val fetched = http.newCall(
                Request.Builder().url(MODEL_URL).header("User-Agent", "VelaMaps").build(),
            ).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) return@use false
                val total = body.contentLength().takeIf { it > 0 } ?: TAR_SIZE_EST
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

            // Extract into a staging dir, then promote the archive's single top-level folder
            // (kokoro-int8-multi-lang-v1_0/) to be `dir` itself.
            staging.deleteRecursively(); staging.mkdirs()
            extractTarBz2(tmp, staging)
            val inner = staging.listFiles()?.firstOrNull { it.isDirectory } ?: staging
            dir.deleteRecursively()
            if (!inner.renameTo(dir)) inner.copyRecursively(dir, overwrite = true)
            onProgress(1f)
            VelaKokoro.isReady(context)
        } catch (t: Throwable) {
            dir.deleteRecursively()
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

    private companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
        const val TAR_SIZE_EST = 131_839_838L
    }
}
