package app.vela.core.util

import java.io.File

/**
 * Writes that a process kill cannot tear. The text goes to a sibling temp file, is synced, and is
 * renamed over the target (rename is atomic on one filesystem), so a reader sees the old file or
 * the new one, never half of either. Used for the offline stores' index/revision files: a torn
 * index read back as "nothing installed", and the next write saved that empty list for good.
 */
object AtomicFiles {
    fun writeText(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse to rename over an existing file: replace in two steps.
            file.delete()
            if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("could not replace $file") }
        }
    }
}
