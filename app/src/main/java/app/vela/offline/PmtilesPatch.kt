package app.vela.offline

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Applying a delta update to an installed archive, IN PLACE.
 *
 * A rebaked region changes about one tile in a hundred (measured: a week of OpenStreetMap edits
 * moved 1.3% of a state's tiles), so offering a fresh 180 MB download every week is most of a
 * gigabyte a month to say the same thing. A patch carries only the tiles that changed.
 *
 * The write order IS the safety: tile blobs, then the rebuilt directory, then the 127-byte header
 * LAST. Everything before that write is appended past the end of what the old header describes, so
 * an interrupted apply leaves the old archive exactly as it was, just longer. Nothing is ever
 * rewritten, which is why this needs the size of the patch in free space rather than a second copy
 * of the region.
 *
 * The result is then PROVEN rather than assumed: [fingerprint] hashes every tile the archive holds
 * and it has to equal the value the patch carries, which is the fingerprint of a fresh download of
 * that revision. A mismatch is reported as a failure and the caller downloads the region whole, so
 * an archive can never quietly drift away from what the bake published.
 *
 * Producer, and the reference implementation of all of this: `scripts/pmtiles-make-patch.py` and
 * `scripts/pmtiles-apply-patch.py`.
 */
object PmtilesPatch {
    private const val TAG = "VelaDelta"
    private const val HEADER_LEN = 127
    private val MAGIC = "VELAPTCH".toByteArray()
    private const val VERSION = 1

    data class Plan(
        val fromRev: Int,
        val toRev: Int,
        val fromSize: Long,
        val fromHeaderSha: String,
        val tileDataOffset: Long,
        val tileLengths: IntArray,
        val dirLength: Int,
        val toFingerprint: String?,
        val deadBytes: Long,
        /** Where the directory sits in the PATCH. It comes before the tile blobs there and has to
         *  go after them in the archive, so the applier buffers it. */
        val dirOffset: Long,
        /** Where the first tile blob sits in the patch. */
        val bodyOffset: Long,
    )

    sealed class Outcome {
        /** The archive now holds what the bake published, proven by its fingerprint. */
        data class Applied(val tiles: Int, val grewBytes: Long, val deadBytes: Long) : Outcome()
        /** Download the region whole. [why] is short enough to log and to put in a diagnostics export. */
        data class Refused(val why: String) : Outcome()
    }

    /** Reads the patch's header without touching the archive, so a caller can decide (size, revs,
     *  whether it even matches what is installed) before spending anything. */
    fun read(patch: File): Plan? = runCatching {
        RandomAccessFile(patch, "r").use { f ->
            val magic = ByteArray(MAGIC.size)
            f.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            val version = f.read()
            if (version != VERSION) {
                Log.d(TAG, "patch version $version is newer than this build understands")
                return null
            }
            val head = ByteArray(le32(f)).also { f.readFully(it) }
            val json = org.json.JSONObject(String(head))
            val dirLen = le32(f)
            val tiles = json.getJSONArray("tiles")
            Plan(
                fromRev = json.optInt("fromRev"),
                toRev = json.optInt("toRev"),
                fromSize = json.getLong("fromSize"),
                fromHeaderSha = json.getString("fromHeaderSha"),
                tileDataOffset = json.getLong("tileDataOffset"),
                tileLengths = IntArray(tiles.length()) { tiles.getJSONObject(it).getInt("len") },
                dirLength = dirLen,
                toFingerprint = json.optString("toFingerprint").takeIf { it.isNotBlank() },
                deadBytes = json.optLong("deadBytes"),
                dirOffset = f.filePointer,
                bodyOffset = f.filePointer + dirLen,
            )
        }
    }.getOrNull()

    /**
     * Applies [patch] to [archive]. [verify] hashes every tile afterwards, which reads the whole
     * archive once; leave it on, since it is the only thing standing between a bad patch and a
     * subtly wrong map.
     */
    fun apply(archive: File, patch: File, verify: Boolean = true): Outcome {
        val plan = read(patch) ?: return Outcome.Refused("unreadable patch")
        if (archive.length() != plan.fromSize) {
            return Outcome.Refused("archive is ${archive.length()} bytes, patch wants ${plan.fromSize}")
        }
        val head = runCatching {
            RandomAccessFile(archive, "r").use { a -> ByteArray(HEADER_LEN).also { a.readFully(it) } }
        }.getOrNull() ?: return Outcome.Refused("cannot read the archive header")
        if (sha256(head) != plan.fromHeaderSha) return Outcome.Refused("archive is not the revision the patch expects")

        val before = archive.length()
        // Append, PROVE, then commit. The fingerprint is computed against the directory the patch
        // just wrote, while the header still describes the old archive, so a patch that does not
        // produce what it promised costs a truncate rather than a broken map.
        val applied = runCatching {
            RandomAccessFile(patch, "r").use { p ->
                // The patch carries the directory before the tiles (the producer knows its size
                // only after it has laid the tiles out); the archive needs it after them.
                p.seek(plan.dirOffset)
                val dir = ByteArray(plan.dirLength).also { p.readFully(it) }
                p.seek(plan.bodyOffset)
                RandomAccessFile(archive, "rw").use { a ->
                    a.seek(before)
                    val buf = ByteArray(1 shl 16)
                    for (len in plan.tileLengths) copy(p, a, len, buf)
                    val rootAt = a.filePointer
                    a.write(dir)
                    val eof = a.filePointer
                    // Everything the old header describes is still intact at this point. Force it
                    // to disk BEFORE the header moves, or a power cut between the two leaves a
                    // header pointing at a directory that was never written.
                    a.fd.sync()
                    if (verify && plan.toFingerprint != null) {
                        val got = fingerprintAt(archive, rootAt, plan.dirLength.toLong(), plan.tileDataOffset, head[97].toInt() and 0xFF)
                        if (got != plan.toFingerprint) {
                            Log.d(TAG, "patch would produce $got, expected ${plan.toFingerprint}; rolling back")
                            a.channel.truncate(before)
                            a.fd.sync()
                            return Outcome.Refused("fingerprint would be $got, patch says ${plan.toFingerprint}")
                        }
                    }
                    val newHead = head.copyOf()
                    putLe64(newHead, 8, rootAt)
                    putLe64(newHead, 16, plan.dirLength.toLong())
                    putLe64(newHead, 40, 0)   // the patch folds the leaves into one root
                    putLe64(newHead, 48, 0)
                    putLe64(newHead, 64, eof - plan.tileDataOffset)
                    newHead[96] = 0           // no longer clustered: appended tiles are out of order
                    a.seek(0)
                    a.write(newHead)
                    a.fd.sync()
                    eof
                }
            }
        }.getOrElse { return Outcome.Refused("write failed: ${it.javaClass.simpleName}") }

        val grew = applied - before
        Log.d(TAG, "applied ${plan.tileLengths.size} tiles to ${archive.name}: " +
            "rev ${plan.fromRev} -> ${plan.toRev}, grew ${grew / 1024} KB, dead ${plan.deadBytes / 1024} KB")
        return Outcome.Applied(plan.tileLengths.size, grew, plan.deadBytes)
    }

    /** A hash over every tile the archive holds, in id order, independent of where they sit on
     *  disk. Equal fingerprints mean equal maps. Null when the archive cannot be read. */
    fun fingerprint(file: File): String? {
        val h = PmtilesReader.header(file) ?: return null
        return fingerprintAt(file, h.rootOffset, h.rootLength, h.tileDataOffset, h.internalCompression)
    }

    /** The same, against a directory the header does not point at yet. */
    private fun fingerprintAt(file: File, rootOffset: Long, rootLength: Long, tileDataOffset: Long, compression: Int): String? = runCatching {
        val entries = PmtilesReader.entriesAt(file, rootOffset, rootLength, 0L, compression) ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        // Keyed on the PAIR, not a mixed number: a run of identical tiles shares one blob, and a
        // key of offset * 31 + length collides across different blobs, which silently hashes the
        // wrong bytes for some tiles.
        val seen = HashMap<Pair<Long, Long>, ByteArray>()
        RandomAccessFile(file, "r").use { f ->
            for (e in entries) {
                val key = e.offset to e.length
                val tile = seen.getOrPut(key) {
                    val b = ByteArray(e.length.toInt())
                    f.seek(tileDataOffset + e.offset)
                    f.readFully(b)
                    MessageDigest.getInstance("SHA-256").digest(b)
                }
                md.update(le(e.id, 8)); md.update(le(e.runLength, 2)); md.update(tile)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }.getOrNull()

    private fun copy(from: RandomAccessFile, to: RandomAccessFile, len: Int, buf: ByteArray) {
        var left = len
        while (left > 0) {
            val n = from.read(buf, 0, minOf(buf.size, left))
            if (n <= 0) throw java.io.EOFException("patch ended early")
            to.write(buf, 0, n)
            left -= n
        }
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun le(v: Long, bytes: Int) = ByteArray(bytes) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun putLe64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun le32(f: RandomAccessFile): Int {
        val b = ByteArray(4).also { f.readFully(it) }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }
}
