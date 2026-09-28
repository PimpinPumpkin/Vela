package app.vela.offline

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Where downloaded regions live (issue #613): internal storage, or the app's own folder on a
 * removable SD card (`getExternalFilesDirs`, no permission needed, removed with the app). Voices,
 * speech models and small caches stay internal. With the card chosen but missing, everything reads
 * and writes internal storage and Settings says the card is gone.
 */
object StorageLocation {
    const val INTERNAL = "internal"
    const val SD = "sd"

    /** The folders a move carries. MapLibre's own database moves through its own API. */
    val FOLDERS = listOf("obf", "poipacks", "places", "basemap", "overlays", "glyphs", "cells")

    val mode = mutableStateOf(INTERNAL)

    private const val KEY = "offline_storage"
    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        mode.value = prefs(context).getString(KEY, INTERNAL) ?: INTERNAL
        app.vela.core.data.OfflineRoot.dir = root(context)
    }

    /** The app's folder on a mounted removable volume, or null. `adb shell setprop
     *  debug.vela.sdtest true` stands in the shared-storage app folder for a card, for phones with
     *  no slot (a virtual disk from `sm set-virtual-disk` is not visible to apps). */
    fun sdDir(context: Context): File? = runCatching {
        val test = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.vela.sdtest") as? String
        }.getOrNull()
        if (test == "true") return@runCatching context.getExternalFilesDir(null)
        context.getExternalFilesDirs(null).drop(1).firstOrNull { d ->
            d != null && Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED &&
                Environment.isExternalStorageRemovable(d)
        }
    }.getOrNull()

    fun root(context: Context): File =
        if (mode.value == SD) sdDir(context) ?: context.filesDir else context.filesDir

    fun cardMissing(context: Context) = mode.value == SD && sdDir(context) == null

    fun rootFor(context: Context, target: String): File? =
        if (target == SD) sdDir(context) else context.filesDir

    internal fun set(context: Context, target: String) {
        prefs(context).edit().putString(KEY, target).apply()
        mode.value = target
        app.vela.core.data.OfflineRoot.dir = root(context)
    }

    /** Bytes the move would carry. */
    fun sizeOf(from: File): Long =
        FOLDERS.sumOf { f -> File(from, f).walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /**
     * Copy every folder from [from] to [to], checking each file's length, then delete the source.
     * Nothing is deleted unless every file copied; a failed copy removes the partial destination
     * and leaves the source as it was. [onProgress] gets 0..100.
     */
    fun move(from: File, to: File, active: () -> Boolean, onProgress: (Int) -> Unit): Boolean {
        val total = sizeOf(from).coerceAtLeast(1)
        var done = 0L
        val copied = mutableListOf<File>()
        val ok = runCatching {
            for (folder in FOLDERS) {
                val src = File(from, folder)
                if (!src.exists()) continue
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    if (!active()) error("canceled")
                    val dst = File(to, f.relativeTo(from).path)
                    dst.parentFile?.mkdirs()
                    f.inputStream().use { input ->
                        dst.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress((done * 100 / total).toInt())
                            }
                        }
                    }
                    copied += dst
                    if (dst.length() != f.length()) error("short copy of ${f.name}")
                }
            }
        }.isSuccess
        if (!ok) {
            copied.forEach { it.delete() }
            return false
        }
        FOLDERS.forEach { File(from, it).deleteRecursively() }
        return true
    }
}
