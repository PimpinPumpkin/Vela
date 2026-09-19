package app.vela.offline

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

/**
 * Take the dead space back out of an archive that has been patched.
 *
 * A patch APPENDS, so the tiles it replaced stay on disk with nothing pointing at them. That is the
 * only way an archive that has taken patches differs from a fresh download of the same revision.
 * The obvious fix is to download the region again, and it is the wrong one: every live tile is
 * already on the phone. Writing them back out in tile order and dropping the rest is a local
 * rewrite, no network, and it lands on exactly the layout the bake publishes (header, directory,
 * metadata, tile data in id order, clustered, no leaves, no gaps).
 *
 * `scripts/pmtiles-compact.py` is the same thing on a desktop, and is how the layout was checked
 * against a real published archive: a patched file compacted to within 17 bytes of the fresh
 * download of that revision, with the same fingerprint.
 *
 * It is proven before it is committed, like the patch is: the rewrite goes to a temporary file, the
 * fingerprint has to match the archive it came from, and only then does it replace it. A failure
 * anywhere leaves the patched archive exactly as it was - fatter than it needs to be, and correct.
 */
object PmtilesCompact {
    private const val HEADER_LEN = 127
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2

    sealed class Outcome {
        data class Done(val beforeBytes: Long, val afterBytes: Long) : Outcome()
        data class Refused(val why: String) : Outcome()
    }

    fun compact(file: File): Outcome {
        val h = PmtilesReader.header(file) ?: return Outcome.Refused("unreadable header")
        val entries = PmtilesReader.entries(file) ?: return Outcome.Refused("unreadable directory")
        if (entries.isEmpty()) return Outcome.Refused("no tiles")
        val before = file.length()
        // The rewrite is a second copy until the rename, so there has to be room for one.
        if (file.usableSpace < before + MARGIN_BYTES) return Outcome.Refused("not enough free space")
        val was = PmtilesPatch.fingerprint(file) ?: return Outcome.Refused("could not fingerprint")

        val tmp = File(file.parentFile, "${file.name}.compact")
        val ok = runCatching {
            // New offsets in tile id order. Ids that share a blob keep sharing it: the bake stores
            // one copy for a whole run of near-empty tiles and expanding that here would undo it.
            val placed = HashMap<Pair<Long, Long>, Long>(entries.size * 2)
            val plan = ArrayList<Pair<Long, Long>>(entries.size)
            val newEntries = ArrayList<PmtilesReader.Entry>(entries.size)
            var at = 0L
            for (e in entries) {
                val key = e.offset to e.length
                val where = placed.getOrPut(key) { at.also { plan.add(key); at += e.length } }
                newEntries.add(PmtilesReader.Entry(e.id, where, e.length, e.runLength))
            }
            val directory = writeDirectory(newEntries, h.internalCompression)
                ?: error("cannot write a ${h.internalCompression} directory")
            val meta = ByteArray(h.metaLength.toInt())
            RandomAccessFile(file, "r").use { f ->
                f.seek(h.metaOffset)
                f.readFully(meta)

                val head = ByteArray(HEADER_LEN)
                f.seek(0)
                f.readFully(head)
                val tileOffset = HEADER_LEN.toLong() + directory.size + meta.size
                putLe64(head, 8, HEADER_LEN.toLong())          // root directory
                putLe64(head, 16, directory.size.toLong())
                putLe64(head, 24, HEADER_LEN.toLong() + directory.size)  // metadata
                putLe64(head, 32, meta.size.toLong())
                putLe64(head, 40, 0)                           // no leaf directories
                putLe64(head, 48, 0)
                putLe64(head, 56, tileOffset)
                putLe64(head, 64, at)
                putLe64(head, 72, newEntries.sumOf { it.runLength })  // addressed tiles
                putLe64(head, 80, newEntries.size.toLong())    // tile entries
                putLe64(head, 88, plan.size.toLong())          // distinct blobs
                head[96] = 1                                   // clustered again

                RandomAccessFile(tmp, "rw").use { out ->
                    out.setLength(0)
                    out.write(head)
                    out.write(directory)
                    out.write(meta)
                    val buf = ByteArray(256 * 1024)
                    for ((off, len) in plan) {
                        f.seek(h.tileDataOffset + off)
                        var left = len
                        while (left > 0) {
                            val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) error("archive ended early")
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    out.fd.sync()
                }
            }
            val now = PmtilesPatch.fingerprint(tmp)
            if (now != was) error("fingerprint changed, $was to $now")
            true
        }.getOrElse {
            tmp.delete()
            return Outcome.Refused(it.message ?: it.javaClass.simpleName)
        }
        if (!ok || !tmp.renameTo(file)) {
            tmp.delete()
            return Outcome.Refused("could not replace the archive")
        }
        return Outcome.Done(before, file.length())
    }

    /** The directory as PMTiles writes it: counts and then four varint columns, gzipped.
     *  Shared with the patch applier, which also has to build a directory of its own now. */
    internal fun writeDirectory(entries: List<PmtilesReader.Entry>, compression: Int): ByteArray? {
        val body = java.io.ByteArrayOutputStream(entries.size * 8)
        putVarint(body, entries.size.toLong())
        var last = 0L
        for (e in entries) { putVarint(body, e.id - last); last = e.id }
        for (e in entries) putVarint(body, e.runLength)
        for (e in entries) putVarint(body, e.length)
        // 0 means "directly after the previous entry", which is nearly every entry once the file is
        // clustered and is what keeps the directory of a big region small.
        var prevEnd = -1L
        for (e in entries) {
            putVarint(body, if (e.offset == prevEnd) 0 else e.offset + 1)
            prevEnd = e.offset + e.length
        }
        val raw = body.toByteArray()
        return when (compression) {
            COMPRESSION_NONE -> raw
            COMPRESSION_GZIP -> java.io.ByteArrayOutputStream().also { sink ->
                GZIPOutputStream(sink).use { it.write(raw) }
            }.toByteArray()
            else -> null
        }
    }

    private fun putVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            out.write(if (v != 0L) b or 0x80 else b)
            if (v == 0L) return
        }
    }

    private fun putLe64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    /** Headroom over the archive's own size before a rewrite is worth starting. */
    private const val MARGIN_BYTES = 32L * 1024 * 1024
}
