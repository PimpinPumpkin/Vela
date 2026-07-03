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
 * into a target dir under `filesDir`, extracting it, reporting 0f..1f progress. Generic over the voice
 * (Kokoro / Piper / Matcha). Matcha additionally needs a separate vocoder `.onnx` — pass [extraUrl]/
 * [extraName] and it's fetched into the same dir. Best-effort: any failure wipes the partial model.
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
    /** Download [url] into [destDir] (extracting the archive's single top-level folder into it), and,
     *  if given, an [extraUrl] file saved as [extraName] alongside it. [onProgress] is 0f..1f. */
    suspend fun download(
        url: String,
        destDir: File,
        sizeEst: Long,
        extraUrl: String? = null,
        extraName: String? = null,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "voice.download.tmp")
        val staging = File(context.filesDir, "voice.staging")
        try {
            // Reserve the tail of the progress bar for the vocoder when there's a two-part download.
            val tarSpan = if (extraUrl != null) 0.55f else 0.98f
            if (!stream(url, tmp, sizeEst, 0f, tarSpan, onProgress)) return@withContext false

            staging.deleteRecursively(); staging.mkdirs()
            extractTarBz2(tmp, staging)
            val inner = staging.listFiles()?.firstOrNull { it.isDirectory } ?: staging
            destDir.deleteRecursively()
            if (!inner.renameTo(destDir)) inner.copyRecursively(destDir, overwrite = true)

            if (extraUrl != null && extraName != null) {
                if (!stream(extraUrl, File(destDir, extraName), VOCODER_SIZE_EST, 0.6f, 0.38f, onProgress)) {
                    destDir.deleteRecursively(); return@withContext false
                }
            }
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

    companion object {
        // sherpa-onnx tts-models release .tar.bz2 sources + fallback sizes.
        const val KOKORO_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
        const val KOKORO_SIZE = 349_418_188L
        const val PIPER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2"
        const val PIPER_SIZE = 82_000_000L
        const val MATCHA_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/matcha-icefall-en_US-ljspeech.tar.bz2"
        const val MATCHA_SIZE = 76_739_916L
        // Matcha's vocoder lives in the separate vocoder-models release.
        const val VOCODER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-22khz-univ.onnx"
        const val VOCODER_SIZE_EST = 53_884_024L
    }
}
