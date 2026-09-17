package app.vela.core.replay

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sharing several recorded trips at once, as ONE zip file.
 *
 * Several separate attachments through ACTION_SEND_MULTIPLE did not reach every messenger (Signal
 * dropped them), and a single file is easier to hand over anyway. Every entry is a trip that has
 * already been through [TripScrub]; this object never sees a raw trace. `:app` does the file and
 * intent work around it.
 */
object TripShareBatch {

    const val MIME = "application/zip"

    /** What a batch share will do, shown before anything leaves the phone. */
    data class Summary(
        val picked: Int,
        val kept: Int,
        val fixesRemoved: Int,
        val fixesKept: Int,
    ) {
        /** Trips too short to keep anything once trimmed; they are left out, never sent raw. */
        val leftOut: Int get() = picked - kept
    }

    /** [reports] holds one entry per picked trip; null = the trip trimmed to nothing (or was unreadable). */
    fun summarize(reports: List<TripScrub.Report?>): Summary {
        val kept = reports.filterNotNull()
        return Summary(
            picked = reports.size,
            kept = kept.size,
            fixesRemoved = kept.sumOf { it.fixesRemoved },
            fixesKept = kept.sumOf { it.fixesAfter },
        )
    }

    /**
     * Zip entry names from each trip's date-time [stamps] ("2026-09-13-1432"). Two drives started
     * in the same minute would collide, so repeats get "-2", "-3" appended. The names never carry
     * a trip's label or destination (the scrub removed those from the body for a reason).
     */
    fun entryNames(stamps: List<String>): List<String> {
        val used = HashMap<String, Int>()
        return stamps.map { s ->
            val n = (used[s] ?: 0) + 1
            used[s] = n
            if (n == 1) "vela-trip-$s.csv" else "vela-trip-$s-$n.csv"
        }
    }

    /** Write [entries] (name to CSV body) into a zip on [out]. The stream is closed afterwards. */
    fun writeZip(entries: List<Pair<String, String>>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
